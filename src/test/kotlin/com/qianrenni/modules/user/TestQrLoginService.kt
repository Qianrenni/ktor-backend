package com.qianrenni.modules.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qianrenni.common.appConfig
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.infrastructure.database.redisManager
import com.qianrenni.models.tables.UserTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.qrLoginService
import com.qianrenni.testutil.withTestApplication
import io.ktor.server.application.Application
import kotlinx.coroutines.future.await
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QrLoginService 集成测试：票据状态机（生成 → 扫描 → 确认 → 兑换）与安全约束。
 * 依赖 Redis（db15）保存票据会话，H2 种子用户提供登录主体。
 */
class TestQrLoginService {

    private fun user1() = TestUsers.userIds.getValue(TestUsers.USER_NAME)
    private fun user2() = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)

    /** 直接删除票据 key，等价于票据过期。 */
    private suspend fun expireTicket(application: Application, ticket: String) {
        application.redisManager.getAsyncCommands().del("qr_login:$ticket").await()
    }

    @Test
    fun `createSession 生成票据且状态为 pending`() = withTestApplication {
        val created = qrLoginService.createSession()

        assertTrue(created.ticket.isNotBlank())
        // 二维码内容按约定编码，手机端可从其中解析出票据
        assertEquals(QrLoginPayload.build(created.ticket), created.qrContent)
        assertEquals(QrLoginStatus.PENDING, qrLoginService.getStatus(created.ticket))
        assertTrue(qrLoginService.expireSeconds > 0)
    }

    @Test
    fun `每次生成票据互不相同`() = withTestApplication {
        assertNotEquals(qrLoginService.createSession().ticket, qrLoginService.createSession().ticket)
    }

    @Test
    fun `getStatus 票据不存在返回 null`() = withTestApplication {
        assertNull(qrLoginService.getStatus("not-exist-ticket"))
    }

    @Test
    fun `scan 记录扫码用户并可被网页端看到`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket

        assertEquals(QrLoginStatus.SCANNED, qrLoginService.scan(ticket, user1()))
        assertEquals(QrLoginStatus.SCANNED, qrLoginService.getStatus(ticket))
    }

    @Test
    fun `同一用户重复 scan 幂等`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())

        assertEquals(QrLoginStatus.SCANNED, qrLoginService.scan(ticket, user1()))
    }

    @Test
    fun `已被他人扫描的票据拒绝再次扫描`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())

        assertFailsWith<IllegalArgumentException> { qrLoginService.scan(ticket, user2()) }
    }

    @Test
    fun `已确认的票据拒绝再次扫描`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())
        qrLoginService.confirm(ticket, user1())

        assertFailsWith<IllegalArgumentException> { qrLoginService.scan(ticket, user2()) }
    }

    @Test
    fun `未扫描直接 confirm 抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket

        assertFailsWith<IllegalArgumentException> { qrLoginService.confirm(ticket, user1()) }
    }

    @Test
    fun `非扫码用户 confirm 抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())

        assertFailsWith<IllegalArgumentException> { qrLoginService.confirm(ticket, user2()) }
    }

    @Test
    fun `cancel 后状态为 canceled 且不可兑换`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())

        assertEquals(QrLoginStatus.CANCELED, qrLoginService.cancel(ticket, user1()))
        assertEquals(QrLoginStatus.CANCELED, qrLoginService.getStatus(ticket))
        assertFailsWith<IllegalArgumentException> { qrLoginService.exchange(ticket) }
    }

    @Test
    fun `scan 过期票据抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        expireTicket(this, ticket)

        assertFailsWith<IllegalArgumentException> { qrLoginService.scan(ticket, user1()) }
    }

    @Test
    fun `exchange 未确认票据抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())

        assertFailsWith<IllegalArgumentException> { qrLoginService.exchange(ticket) }
    }

    @Test
    fun `confirm 后 exchange 签发该用户的可用令牌`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())
        qrLoginService.confirm(ticket, user1())

        val tokens = qrLoginService.exchange(ticket)

        assertEquals(user1(), tokens.user.id)
        assertEquals(TestUsers.USER_NAME, tokens.user.userName)
        // refresh_token 落在 Redis，指向同一用户
        assertEquals(user1().toString(), redisManager.getAsyncCommands().get(tokens.refreshToken).await())
        // access_token 可被应用密钥验签，且 subject 为登录用户
        val decoded = JWT.require(Algorithm.HMAC256(appConfig.secretKey))
            .withAudience(appConfig.audience)
            .withIssuer(appConfig.issuer)
            .build()
            .verify(tokens.accessToken)
        assertEquals(user1().toString(), decoded.subject)
    }

    @Test
    fun `exchange 一次性 重复兑换抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())
        qrLoginService.confirm(ticket, user1())
        assertNotNull(qrLoginService.exchange(ticket))

        assertFailsWith<IllegalArgumentException> { qrLoginService.exchange(ticket) }
        assertNull(qrLoginService.getStatus(ticket))
    }

    @Test
    fun `exchange 过期票据抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())
        qrLoginService.confirm(ticket, user1())
        expireTicket(this, ticket)

        assertFailsWith<IllegalArgumentException> { qrLoginService.exchange(ticket) }
    }

    @Test
    fun `exchange 用户被禁用时抛异常`() = withTestApplication {
        val ticket = qrLoginService.createSession().ticket
        qrLoginService.scan(ticket, user1())
        qrLoginService.confirm(ticket, user1())
        databaseManager.suspendedTransaction {
            UserTable.update({ UserTable.id eq user1() }) { it[UserTable.isActive] = false }
        }

        assertFailsWith<IllegalArgumentException> { qrLoginService.exchange(ticket) }
    }
}
