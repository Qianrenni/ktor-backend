package com.qianrenni.infrastructure.mail

import com.qianrenni.testutil.*

import com.qianrenni.testutil.withTestApplication
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * EmailService 集成测试。
 * 测试环境 SMTP 指向 127.0.0.1:1（不可达），sendEmail 应优雅失败返回 false 而非抛出。
 */
class TestEmailService {

    @Test
    fun `sendEmail 到不可达 SMTP 返回 false`() = withTestApplication {
        val sent = emailService.sendEmail(
            toEmails = listOf("nobody@example.com"),
            subject = "测试邮件",
            body = "测试内容"
        )
        assertFalse(sent, "SMTP 不可达时发送应返回 false（内部已捕获异常）")
    }
}
