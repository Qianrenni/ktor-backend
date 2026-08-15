package com.qianrenni.modules.user

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.database.redisManager
import com.qianrenni.testutil.withTestApplication
import kotlinx.coroutines.future.await
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CaptchaService 集成测试：验证码文本生成、数字验证码（忘记密码场景）、图片验证码。
 * 依赖 Redis（db15，每个测试前 flushdb 复位）。
 */
class TestCaptchaService {

    @Test
    fun `generateCaptchaText 按指定长度生成字母数字`() = withTestApplication {
        assertEquals(4, captchaService.generateCaptchaText().length)
        assertEquals(6, captchaService.generateCaptchaText(6).length)
        val text = captchaService.generateCaptchaText(8)
        assertTrue(text.all { it.isLetterOrDigit() }, "验证码应只包含字母和数字")
    }

    @Test
    fun `getVerifyCode 返回验证码且可验证通过`() = withTestApplication {
        val code = captchaService.getVerifyCode("forgot_password:test@test.com", length = 6)
        assertEquals(6, code.length)
        assertTrue(captchaService.verifyCode("forgot_password:test@test.com", code))
    }

    @Test
    fun `getVerifyCode 已有未过期验证码时抛异常`() = withTestApplication {
        captchaService.getVerifyCode("otp:user")
        assertFailsWith<IllegalArgumentException> { captchaService.getVerifyCode("otp:user") }
    }

    @Test
    fun `verifyCode 错误答案返回 false 且不消耗验证码`() = withTestApplication {
        val code = captchaService.getVerifyCode("otp:retry")
        assertFalse(captchaService.verifyCode("otp:retry", "wrong"))
        // 验证码未被删除，正确答案仍可通过
        assertTrue(captchaService.verifyCode("otp:retry", code))
    }

    @Test
    fun `verifyCode 不存在 key 返回 false`() = withTestApplication {
        assertFalse(captchaService.verifyCode("no_such_key", "123456"))
    }

    @Test
    fun `verifyCaptcha 未知 id 返回 false`() = withTestApplication {
        assertFalse(captchaService.verifyCaptcha("AbCd", "no_such_id"))
    }

    @Test
    fun `getCaptcha 生成图片验证码并可验证`() = withTestApplication {
        val (captchaId, image) = captchaService.getCaptcha()
        assertTrue(image.isNotEmpty())
        // 从 Redis 读取实际验证码文本，模拟用户正确输入
        val storedText = redisManager.getAsyncCommands().get(captchaId).await()
        assertTrue(storedText != null, "验证码应写入 Redis")
        assertTrue(captchaService.verifyCaptcha(storedText!!, captchaId))
        // 一次性使用后失效
        assertFalse(captchaService.verifyCaptcha(storedText, captchaId))
    }
}
