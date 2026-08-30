package com.qianrenni.modules.user

import com.qianrenni.common.AppConfig
import com.qianrenni.common.util.TokenGenerator
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.models.tables.FullUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 扫码登录二维码状态机：PENDING → SCANNED → CONFIRMED →（网页兑换后销毁）；CANCELLED / EXPIRED 为终态 */
@Serializable
enum class QrLoginStatus {
    /** 待扫描 */
    PENDING,

    /** App 已扫描，等待用户在手机上确认 */
    SCANNED,

    /** 用户已在手机上确认，网页可领取一次性 confirmToken */
    CONFIRMED,

    /** 用户在手机上取消了本次登录 */
    CANCELLED,

    /** 二维码过期或不存在 */
    EXPIRED,
}

/** Redis 中 `qr_login:<qrId>` 的值：二维码票据状态 */
@Serializable
data class QrLoginState(
    val status: QrLoginStatus,
    /** 确认后写入，用于 exchange 时签发令牌 */
    val userId: Int? = null,
    /** 网页端在 create 时上报的客户端描述，App 确认框展示用 */
    val client: String? = null,
)

/**
 * 扫码登录业务。
 *
 * 流程：网页 create 出码 → App scan → App confirm → 网页轮询 status
 * 领取一次性 confirmToken → 网页 exchange 兑换正式令牌。
 *
 * 安全设计：
 * - 真实令牌只经 exchange 签发，绝不通过 status 轮询接口下发；
 * - confirmToken 以 SET NX 原子"铸币"，并发轮询只会拿到同一个 token，
 *   exchange 再以 GETDEL 原子消费——即使轮询响应丢失也无法重放；
 * - 全部状态存 Redis 并带 TTL，过期即失效，无需落库。
 */
class QrLoginService(
    private val config: AppConfig,
    private val cacheService: CacheService,
    private val userService: UserService,
    private val tokenIssuer: TokenIssuer,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val QR_KEY_PREFIX = "qr_login:"
        const val CONFIRM_KEY_PREFIX = "qr_confirm:"
        const val MINT_KEY_PREFIX = "qr_mint:"
    }

    /** 网页端创建二维码票据 */
    suspend fun createQr(client: String?): ResponseQrCreate {
        val qrId = TokenGenerator.secureRandom(byteLength = 32)
        writeState(
            qrId = qrId,
            state = QrLoginState(status = QrLoginStatus.PENDING, client = client?.take(100)),
        )
        return ResponseQrCreate(qrId = qrId, expireSeconds = config.qrLoginExpire)
    }

    /** 网页端轮询二维码状态；CONFIRMED 时领取一次性 confirmToken */
    suspend fun getStatus(qrId: String): ResponseQrStatus {
        val raw = cacheService.cacheGetSimple(qrKey(qrId))
        if (raw == null) {
            // 票据已销毁：若 confirmToken 仍在宽限期内（TTL 未到），允许网页读回同一个
            // token 以应对轮询响应丢失；否则视为过期
            val minted = cacheService.cacheGetSimple(mintKey(qrId))
            return if (minted != null) {
                ResponseQrStatus(status = QrLoginStatus.CONFIRMED, confirmToken = minted)
            } else {
                ResponseQrStatus(status = QrLoginStatus.EXPIRED)
            }
        }
        val state = decodeState(raw)
        if (state.status == QrLoginStatus.CONFIRMED && state.userId != null) {
            val confirmToken = mintConfirmToken(qrId, state.userId)
            return if (confirmToken != null) {
                ResponseQrStatus(
                    status = QrLoginStatus.CONFIRMED,
                    client = state.client,
                    confirmToken = confirmToken,
                )
            } else {
                // 铸币异常（极端并发/Redis 故障），按过期处理让网页刷新重试
                ResponseQrStatus(status = QrLoginStatus.EXPIRED)
            }
        }
        return ResponseQrStatus(status = state.status, client = state.client)
    }

    /** App 扫码：PENDING → SCANNED，返回网页端信息供确认框展示 */
    suspend fun scan(qrId: String): ResponseQrScan {
        val state = readState(qrId) ?: throw IllegalArgumentException("二维码已过期，请刷新后重试")
        if (state.status != QrLoginStatus.PENDING) {
            throw IllegalArgumentException("二维码已被使用或状态异常，请在网页端刷新后重试")
        }
        writeState(qrId = qrId, state = state.copy(status = QrLoginStatus.SCANNED))
        return ResponseQrScan(client = state.client)
    }

    /** App 确认：SCANNED → CONFIRMED，记录确认用户 */
    suspend fun confirm(user: FullUser, qrId: String) {
        val state = readState(qrId) ?: throw IllegalArgumentException("二维码已过期，请刷新后重试")
        if (state.status != QrLoginStatus.SCANNED) {
            throw IllegalArgumentException("请先扫描网页端二维码")
        }
        writeState(qrId = qrId, state = state.copy(status = QrLoginStatus.CONFIRMED, userId = user.id))
    }

    /** App 取消：置为 CANCELLED 终态，网页轮询后提示并停止 */
    suspend fun cancel(qrId: String) {
        val state = readState(qrId) ?: return // 已过期则无需取消
        if (state.status == QrLoginStatus.CONFIRMED || state.status == QrLoginStatus.CANCELLED) {
            return // 已确认的票据不可再取消
        }
        writeState(qrId = qrId, state = state.copy(status = QrLoginStatus.CANCELLED))
    }

    /** 网页端用一次性 confirmToken 原子兑换正式令牌 */
    suspend fun exchange(confirmToken: String): ResponseTokenData {
        val userId = cacheService.cacheGetDeleteSimple(confirmKey(confirmToken))
            ?: throw IllegalArgumentException("登录确认已失效，请重新扫码")
        val user = userService.getUserById(userId.toInt())
        return tokenIssuer.issue(user)
    }

    /**
     * 铸币一次性 confirmToken：SET NX 保证并发轮询只认首个写入，
     * 同时把 qrId → confirmToken 的映射写入宽限期 key，读回路径见 [getStatus]。
     */
    private suspend fun mintConfirmToken(qrId: String, userId: Int): String? {
        val confirmToken = TokenGenerator.secureRandom(byteLength = 32)
        val stored = cacheService.cacheSetNxSimple(
            key = confirmKey(confirmToken),
            value = userId.toString(),
            expire = config.qrConfirmExpire.toLong(),
        )
        if (!stored) return null
        val first = cacheService.cacheSetNxSimple(
            key = mintKey(qrId),
            value = confirmToken,
            expire = config.qrConfirmExpire.toLong(),
        )
        if (!first) {
            // 并发轮询已铸过币：读回同一个 token，保证所有人看到同一个确认结果
            return cacheService.cacheGetSimple(mintKey(qrId))
        }
        // 票据一次性：confirmToken 领取入口建立后即销毁二维码，阻断重复领取
        cacheService.cacheDelete(qrKey(qrId))
        return confirmToken
    }

    private suspend fun readState(qrId: String): QrLoginState? =
        cacheService.cacheGetSimple(qrKey(qrId))?.let { decodeState(it) }

    /**
     * 写回状态。转换时重置 TTL（无法从 CacheService 读剩余 TTL），
     * 票据最长存续时间因此略有延长，但状态机本身已限制各状态可达路径。
     */
    private suspend fun writeState(qrId: String, state: QrLoginState) {
        cacheService.cacheSetSimple(
            key = qrKey(qrId),
            value = json.encodeToString(QrLoginState.serializer(), state),
            expire = config.qrLoginExpire.toLong(),
        )
    }

    private fun decodeState(raw: String): QrLoginState =
        json.decodeFromString(QrLoginState.serializer(), raw)

    private fun qrKey(qrId: String) = QR_KEY_PREFIX + qrId

    private fun confirmKey(confirmToken: String) = CONFIRM_KEY_PREFIX + confirmToken

    private fun mintKey(qrId: String) = MINT_KEY_PREFIX + qrId
}
