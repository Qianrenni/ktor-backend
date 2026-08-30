package com.qianrenni.modules.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qianrenni.common.AppConfig
import com.qianrenni.common.util.TokenGenerator
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.models.tables.FullUser
import java.util.*

/**
 * 访问/刷新令牌签发器。
 *
 * 从 AuthController 中抽出：密码登录（/token/get、/token/refresh）与扫码登录
 * （/token/qr/exchange）共用同一签发逻辑，保证各途径签发的令牌格式与
 * Redis 存储方式一致。
 */
class TokenIssuer(
    private val config: AppConfig,
    private val cacheService: CacheService,
) {
    /**
     * 为用户签发一对新令牌（access_token + refresh_token）。
     * @param user 已通过身份校验的用户
     */
    suspend fun issue(user: FullUser): ResponseTokenData {
        // 生成 access_token
        val accessToken = JWT.create()
            .withAudience(config.audience)
            .withIssuer(config.issuer)
            .withSubject(user.id.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + config.accessTokenExpire * 1000))
            .sign(Algorithm.HMAC256(config.secretKey))

        // 生成 refresh_token 并存储到 Redis
        val refreshToken = TokenGenerator.secureRandom(byteLength = 48)
        cacheService.cacheSetSimple(
            key = refreshToken,
            value = user.id.toString(),
            expire = config.refreshTokenExpire.toLong()
        )
        return ResponseTokenData(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = user
        )
    }
}
