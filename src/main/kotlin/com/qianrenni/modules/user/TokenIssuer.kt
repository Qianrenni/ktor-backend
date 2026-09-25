package com.qianrenni.modules.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qianrenni.common.AppConfig
import com.qianrenni.common.util.TokenGenerator
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.models.tables.FullUser
import java.util.Date

/**
 * 令牌签发器：集中 access_token / refresh_token 的生成与落库逻辑。
 *
 * 登录、刷新令牌、扫码登录三条路径都要签发令牌，集中在此处避免 JWT 签名与
 * refresh_token 写入 Redis 的逻辑重复（调整过期时间/算法时只需改一处）。
 */
class TokenIssuer(
    private val config: AppConfig,
    private val cacheService: CacheService,
) {

    /**
     * 为用户签发一对令牌：
     * - access_token 为 JWT（无状态，过期时间来自配置）；
     * - refresh_token 为高熵随机串，存于 Redis（值为 userId），刷新时轮换。
     */
    suspend fun issue(user: FullUser): ResponseTokenData {
        val accessToken = JWT.create()
            .withAudience(config.audience)
            .withIssuer(config.issuer)
            .withSubject(user.id.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + config.accessTokenExpire * 1000L))
            .sign(Algorithm.HMAC256(config.secretKey))

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
