package com.qianrenni.modules.user

import com.qianrenni.common.AppConfig
import com.qianrenni.common.util.TokenGenerator
import com.qianrenni.infrastructure.cache.CacheService

/**
 * 设备登录凭据服务：支撑「手机端指纹解锁登录」。
 *
 * 为什么不能复用 refresh_token：refresh_token 默认只有 30 分钟有效期（`REFRESH_TOKEN_EXPIRE`），
 * 用户隔天打开 App 必然失效，指纹解锁会退化成「每天都要输一次密码」。
 * 因此密码登录成功后（用户开启指纹解锁时）由本服务额外签发一枚**长期设备凭据**：
 *
 * - 凭据为 48 字节高熵随机串，只存于 Redis（`device_login:<token>` → userId），有效期 `DEVICE_TOKEN_EXPIRE`（默认 30 天）；
 * - 手机端把它放进 Keystore 加密存储，必须通过指纹校验才会取出使用；
 * - 每次用凭据换令牌都**轮换**凭据（旧的立即失效），限制凭据泄漏后的重放窗口；
 * - 用户关闭指纹解锁或退出登录时可通过 [revoke] 主动撤销，且只能撤销属于自己的凭据。
 */
class DeviceLoginService(
    private val config: AppConfig,
    private val cacheService: CacheService,
    private val userService: UserService,
    private val tokenIssuer: TokenIssuer,
) {

    /** 设备凭据有效期（秒）。 */
    val expireSeconds: Long = config.deviceTokenExpire.toLong()

    /** 为已登录用户签发一枚新的设备凭据（开启指纹解锁时调用）。 */
    suspend fun issue(userId: Int): DeviceCredential {
        val token = TokenGenerator.secureRandom(byteLength = 48)
        cacheService.cacheSetSimple(
            key = keyOf(token),
            value = userId.toString(),
            expire = expireSeconds,
        )
        return DeviceCredential(deviceToken = token, expiresIn = expireSeconds)
    }

    /**
     * 用设备凭据换取令牌（指纹校验通过后由手机端调用）。
     *
     * 凭据一次性轮换：校验通过后旧凭据立即删除，并签发新凭据随响应返回，
     * 手机端负责覆盖保存。
     */
    suspend fun login(deviceToken: String): DeviceLoginResult {
        val userId = cacheService.cacheGetSimple(keyOf(deviceToken))?.toIntOrNull()
            ?: throw IllegalArgumentException("指纹登录已失效，请使用账号密码重新登录")
        // 先删旧凭据，避免同一枚凭据被并发兑换出多份令牌
        cacheService.cacheDelete(keyOf(deviceToken))

        val user = userService.getUserById(userId)
        val rotatedToken = TokenGenerator.secureRandom(byteLength = 48)
        cacheService.cacheSetSimple(
            key = keyOf(rotatedToken),
            value = userId.toString(),
            expire = expireSeconds,
        )

        return DeviceLoginResult(
            credential = DeviceCredential(deviceToken = rotatedToken, expiresIn = expireSeconds),
            tokens = tokenIssuer.issue(user),
        )
    }

    /**
     * 撤销设备凭据（关闭指纹解锁 / 退出登录）。
     *
     * 凭据不存在时静默返回——重复撤销是幂等的；凭据属于他人时不做任何删除。
     */
    suspend fun revoke(deviceToken: String, userId: Int) {
        val owner = cacheService.cacheGetSimple(keyOf(deviceToken)) ?: return
        if (owner.toIntOrNull() != userId) {
            return
        }
        cacheService.cacheDelete(keyOf(deviceToken))
    }

    private fun keyOf(token: String) = "device_login:$token"
}

/** 设备凭据及其有效期。 */
data class DeviceCredential(
    val deviceToken: String,
    val expiresIn: Long,
)

/** 用设备凭据换到的结果：轮换后的新凭据 + 新令牌。 */
data class DeviceLoginResult(
    val credential: DeviceCredential,
    val tokens: ResponseTokenData,
)
