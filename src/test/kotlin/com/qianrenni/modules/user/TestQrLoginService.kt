package com.qianrenni.modules.user

import com.qianrenni.bootstrap.services
import com.qianrenni.models.tables.FullUser
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.withTestApplication
import io.ktor.server.application.Application
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * QrLoginService 集成测试：扫码登录状态机、一次性 confirmToken 铸币与原子兑换。
 * 依赖 H2 种子数据 + Redis（db15，存二维码票据状态）。
 */
class TestQrLoginService {

    private fun Application.qr() = services.user.qrLoginService

    private suspend fun Application.seedUser(): FullUser =
        services.user.userService.getUserById(TestUsers.userIds.getValue(TestUsers.USER_NAME))

    @Test
    fun `完整扫码登录流程 状态机流转与令牌兑换`() = withTestApplication {
        val service = qr()
        val user = seedUser()

        // 创建二维码票据
        val created = service.createQr(client = "网页端 Chrome")
        assertTrue(created.qrId.isNotBlank())
        assertTrue(created.expireSeconds > 0)

        // 待扫描
        val pending = service.getStatus(created.qrId)
        assertEquals(QrLoginStatus.PENDING, pending.status)
        assertEquals("网页端 Chrome", pending.client)

        // App 扫描 → SCANNED，返回网页端信息
        val scanned = service.scan(created.qrId)
        assertEquals("网页端 Chrome", scanned.client)
        assertEquals(QrLoginStatus.SCANNED, service.getStatus(created.qrId).status)

        // 重复扫描拒绝
        assertFailsWith<IllegalArgumentException> { service.scan(created.qrId) }

        // App 确认 → CONFIRMED
        service.confirm(user, created.qrId)

        // 轮询领取一次性 confirmToken；票据销毁后宽限期内读回同一个 token
        val first = service.getStatus(created.qrId)
        assertEquals(QrLoginStatus.CONFIRMED, first.status)
        val confirmToken = first.confirmToken
        assertTrue(!confirmToken.isNullOrBlank(), "CONFIRMED 状态必须返回 confirmToken")
        val second = service.getStatus(created.qrId)
        assertEquals(confirmToken, second.confirmToken, "宽限期内的重复轮询应读回同一个 confirmToken")

        // 兑换正式令牌
        val tokens = service.exchange(confirmToken)
        assertEquals(user.id, tokens.user.id)
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        // confirmToken 一次性：二次兑换拒绝
        assertFailsWith<IllegalArgumentException> { service.exchange(confirmToken) }
    }

    @Test
    fun `scan 过期或不存在的二维码抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { qr().scan("not-exist-qr-id") }
    }

    @Test
    fun `confirm 未扫描直接确认被拒绝`() = withTestApplication {
        val service = qr()
        val user = seedUser()
        val created = service.createQr(client = null)
        assertFailsWith<IllegalArgumentException> { service.confirm(user, created.qrId) }
    }

    @Test
    fun `cancel 后状态为 CANCELLED 且不可再扫描`() = withTestApplication {
        val service = qr()
        val created = service.createQr(client = null)
        service.scan(created.qrId)
        service.cancel(created.qrId)
        assertEquals(QrLoginStatus.CANCELLED, service.getStatus(created.qrId).status)
        assertFailsWith<IllegalArgumentException> { service.scan(created.qrId) }
    }

    @Test
    fun `cancel 过期二维码静默成功`() = withTestApplication {
        // 不存在/已过期的票据取消时不报错（幂等）
        qr().cancel("not-exist-qr-id")
    }

    @Test
    fun `exchange 非法 confirmToken 抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { qr().exchange("not-exist-confirm-token") }
    }

    @Test
    fun `getStatus 不存在的二维码返回 EXPIRED`() = withTestApplication {
        val status = qr().getStatus("not-exist-qr-id")
        assertEquals(QrLoginStatus.EXPIRED, status.status)
    }

    @Test
    fun `不同二维码票据互相隔离`() = withTestApplication {
        val service = qr()
        val a = service.createQr(client = null)
        val b = service.createQr(client = null)
        assertNotEquals(a.qrId, b.qrId)
        service.scan(a.qrId)
        // b 仍是 PENDING，不受 a 的扫描影响
        assertEquals(QrLoginStatus.PENDING, service.getStatus(b.qrId).status)
    }
}
