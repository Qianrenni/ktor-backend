package com.qianrenni.modules.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qianrenni.common.appConfig
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.infrastructure.database.redisManager
import com.qianrenni.models.tables.UserTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.deviceLoginService
import com.qianrenni.testutil.withTestApplication
import io.ktor.server.application.Application
import kotlinx.coroutines.future.await
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DeviceLoginService 集成测试：指纹解锁登录所用的长期设备凭据。
 * 依赖 Redis（db15）保存凭据，H2 种子用户提供登录主体。
 */
class TestDeviceLoginService {

    private fun user1() = TestUsers.userIds.getValue(TestUsers.USER_NAME)
    private fun user2() = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)

    /** 直接删除凭据 key，等价于凭据过期。 */
    private suspend fun expireCredential(application: Application, token: String) {
        application.redisManager.getAsyncCommands().del("device_login:$token").await()
    }

    @Test
    fun `issue 签发高熵凭据并登记有效期`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())

        assertTrue(credential.deviceToken.isNotBlank())
        assertTrue(credential.expiresIn > 0)
        assertEquals(credential.expiresIn, deviceLoginService.expireSeconds)
        // 凭据落 Redis，值为 userId
        assertEquals(
            user1().toString(),
            redisManager.getAsyncCommands().get("device_login:${credential.deviceToken}").await()
        )
    }

    @Test
    fun `每次签发凭据互不相同`() = withTestApplication {
        assertNotEquals(
            deviceLoginService.issue(user1()).deviceToken,
            deviceLoginService.issue(user1()).deviceToken
        )
    }

    @Test
    fun `login 用凭据换到可验证的令牌`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())

        val result = deviceLoginService.login(credential.deviceToken)

        assertEquals(user1(), result.tokens.user.id)
        assertEquals(TestUsers.USER_NAME, result.tokens.user.userName)
        // refresh_token 落在 Redis，指向同一用户
        assertEquals(
            user1().toString(),
            redisManager.getAsyncCommands().get(result.tokens.refreshToken).await()
        )
        val decoded = JWT.require(Algorithm.HMAC256(appConfig.secretKey))
            .withAudience(appConfig.audience)
            .withIssuer(appConfig.issuer)
            .build()
            .verify(result.tokens.accessToken)
        assertEquals(user1().toString(), decoded.subject)
    }

    @Test
    fun `login 轮换凭据 旧凭据立即失效`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())

        val result = deviceLoginService.login(credential.deviceToken)

        assertNotEquals(credential.deviceToken, result.credential.deviceToken)
        // 旧凭据已被删除
        assertFailsWith<IllegalArgumentException> {
            deviceLoginService.login(credential.deviceToken)
        }
        // 新凭据可用
        assertEquals(user1(), deviceLoginService.login(result.credential.deviceToken).tokens.user.id)
    }

    @Test
    fun `login 未知凭据抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            deviceLoginService.login("not-a-real-device-token")
        }
    }

    @Test
    fun `login 过期凭据抛异常`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())
        expireCredential(this, credential.deviceToken)

        assertFailsWith<IllegalArgumentException> {
            deviceLoginService.login(credential.deviceToken)
        }
    }

    @Test
    fun `login 用户被禁用时抛异常`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())
        databaseManager.suspendedTransaction {
            UserTable.update({ UserTable.id eq user1() }) { it[UserTable.isActive] = false }
        }

        assertFailsWith<IllegalArgumentException> {
            deviceLoginService.login(credential.deviceToken)
        }
    }

    @Test
    fun `revoke 撤销后凭据立即失效`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())

        deviceLoginService.revoke(credential.deviceToken, user1())

        assertFailsWith<IllegalArgumentException> {
            deviceLoginService.login(credential.deviceToken)
        }
    }

    @Test
    fun `revoke 不能撤销他人的凭据`() = withTestApplication {
        val credential = deviceLoginService.issue(user1())

        deviceLoginService.revoke(credential.deviceToken, user2())

        // 凭据仍可正常登录
        assertEquals(user1(), deviceLoginService.login(credential.deviceToken).tokens.user.id)
    }

    @Test
    fun `revoke 未知凭据时静默返回`() = withTestApplication {
        // 幂等：重复撤销 / 撤销不存在的凭据都不应抛异常
        deviceLoginService.revoke("not-a-real-device-token", user1())
        val credential = deviceLoginService.issue(user1())
        deviceLoginService.revoke(credential.deviceToken, user1())
        deviceLoginService.revoke(credential.deviceToken, user1())
    }

    @Test
    fun `一个用户可以同时持有多台设备的凭据`() = withTestApplication {
        val phone = deviceLoginService.issue(user1())
        val tablet = deviceLoginService.issue(user1())

        assertEquals(user1(), deviceLoginService.login(phone.deviceToken).tokens.user.id)
        assertEquals(user1(), deviceLoginService.login(tablet.deviceToken).tokens.user.id)
    }
}
