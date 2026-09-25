package com.qianrenni.modules.user

import com.qianrenni.common.AppConfig
import com.qianrenni.common.util.TokenGenerator
import com.qianrenni.infrastructure.cache.CacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 二维码登录会话状态。 */
@Serializable
enum class QrLoginStatus {
    /** 二维码已生成，等待手机端扫描 */
    @SerialName("pending")
    PENDING,

    /** 手机端已扫描，等待用户在手机上确认 */
    @SerialName("scanned")
    SCANNED,

    /** 手机端已确认，网页端可兑换令牌 */
    @SerialName("confirmed")
    CONFIRMED,

    /** 手机端已取消 */
    @SerialName("canceled")
    CANCELED,
}

/** 手机端对二维码票据的处置动作。 */
@Serializable
enum class QrLoginAction {
    @SerialName("scan")
    SCAN,

    @SerialName("confirm")
    CONFIRM,

    @SerialName("cancel")
    CANCEL,
}

/**
 * 扫码登录会话（存于 Redis 的 JSON 值）。
 *
 * @param status 当前状态
 * @param userId 已扫码的手机端用户 ID；[QrLoginStatus.PENDING] 时为 null
 */
@Serializable
data class QrLoginSession(
    val status: QrLoginStatus = QrLoginStatus.PENDING,
    val userId: Int? = null,
)

/**
 * 二维码内容的编码约定。
 *
 * 用自定义 scheme 承载票据，而非直接把裸票据字符串印进二维码：
 * 扫码端可以据此判断「这是不是本系统的登录码」，避免任意二维码文本被当成登录票据。
 * 手机端解析实现见 android-user 的 `util/QrLoginPayload.kt`（需与本约定保持一致）。
 */
object QrLoginPayload {
    const val SCHEME = "gugareading"
    const val HOST = "qr-login"
    const val PARAM_TICKET = "ticket"

    fun build(ticket: String): String = "$SCHEME://$HOST?$PARAM_TICKET=$ticket"
}

/**
 * 扫码登录服务：管理二维码票据的「生成 → 扫描 → 确认 → 兑换」状态机。
 *
 * 会话状态存放在 Redis（`qr_login:<ticket>`），带短 TTL：
 * - 票据为 32 字节高熵随机串，不可猜测；
 * - 兑换使用 [CacheService.cacheGetAndDelete] 原子取出，天然一次性、防重放；
 * - 确认动作校验会话归属，别人的码无法被确认。
 */
class QrLoginService(
    private val config: AppConfig,
    private val cacheService: CacheService,
    private val userService: UserService,
    private val tokenIssuer: TokenIssuer,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 票据有效期（秒），由 `QR_LOGIN_EXPIRE` 配置。 */
    val expireSeconds: Long = config.qrLoginExpire.toLong()

    /** 生成新的登录二维码票据，返回「票据 + 二维码内容」。 */
    suspend fun createSession(): QrLoginTicket {
        val ticket = TokenGenerator.secureRandom(byteLength = 32)
        writeSession(ticket, QrLoginSession())
        return QrLoginTicket(ticket = ticket, qrContent = QrLoginPayload.build(ticket))
    }

    /**
     * 查询票据状态（网页端轮询用）。
     *
     * 票据不存在（从未生成 / 已过期 / 已被兑换）时返回 null，由调用方转成明确错误。
     */
    suspend fun getStatus(ticket: String): QrLoginStatus? = readSession(ticket)?.status

    /** 手机端扫描二维码：登记「待用户确认」。 */
    suspend fun scan(ticket: String, userId: Int): QrLoginStatus {
        val session = requireSession(ticket)
        if (session.status != QrLoginStatus.PENDING) {
            // 同一用户重复上报（网络重试/重复识别）按幂等处理，别人扫已用过的码则拒绝
            if (session.status == QrLoginStatus.SCANNED && session.userId == userId) {
                return QrLoginStatus.SCANNED
            }
            throw IllegalArgumentException("二维码已失效，请刷新后重试")
        }
        writeSession(ticket, session.copy(status = QrLoginStatus.SCANNED, userId = userId))
        return QrLoginStatus.SCANNED
    }

    /** 手机端确认登录：仅扫描者本人可确认自己扫到的码。 */
    suspend fun confirm(ticket: String, userId: Int): QrLoginStatus {
        val session = requireSession(ticket, userId)
        if (session.status != QrLoginStatus.SCANNED) {
            throw IllegalArgumentException("请先扫描二维码再确认登录")
        }
        writeSession(ticket, session.copy(status = QrLoginStatus.CONFIRMED))
        return QrLoginStatus.CONFIRMED
    }

    /** 手机端取消登录。 */
    suspend fun cancel(ticket: String, userId: Int): QrLoginStatus {
        val session = requireSession(ticket, userId)
        writeSession(ticket, session.copy(status = QrLoginStatus.CANCELED, userId = null))
        return QrLoginStatus.CANCELED
    }

    /**
     * 网页端用已确认的票据兑换令牌。
     *
     * 票据原子取出并删除，因此同一张码只能兑换一次；兑换后网页必须重新取码。
     */
    suspend fun exchange(ticket: String): ResponseTokenData {
        val raw = cacheService.cacheGetAndDelete(ticketKey(ticket))
            ?: throw IllegalArgumentException("二维码已过期，请刷新后重试")
        val session = decodeSession(raw)
        val userId = session.userId
        if (session.status != QrLoginStatus.CONFIRMED || userId == null) {
            throw IllegalArgumentException("请在手机上确认登录后再试")
        }
        return tokenIssuer.issue(userService.getUserById(userId))
    }

    private suspend fun readSession(ticket: String): QrLoginSession? =
        cacheService.cacheGetSimple(ticketKey(ticket))?.let(::decodeSession)

    private suspend fun requireSession(ticket: String, userId: Int? = null): QrLoginSession {
        val session = readSession(ticket)
            ?: throw IllegalArgumentException("二维码已过期，请刷新后重试")
        if (userId != null && session.userId != userId) {
            throw IllegalArgumentException("二维码不属于当前账号，请重新扫描")
        }
        return session
    }

    private suspend fun writeSession(ticket: String, session: QrLoginSession) {
        cacheService.cacheSetSimple(
            key = ticketKey(ticket),
            value = json.encodeToString(QrLoginSession.serializer(), session),
            expire = expireSeconds,
        )
    }

    /** 反序列化失败（脏数据/格式变更）时按「全新的待扫描会话」处理，等价于票据无效。 */
    private fun decodeSession(raw: String): QrLoginSession =
        runCatching { json.decodeFromString(QrLoginSession.serializer(), raw) }
            .getOrElse { QrLoginSession() }

    private fun ticketKey(ticket: String) = "qr_login:$ticket"
}

/** 新建的二维码票据：[qrContent] 为印在二维码里的内容，[ticket] 供网页端轮询状态。 */
data class QrLoginTicket(
    val ticket: String,
    val qrContent: String,
)
