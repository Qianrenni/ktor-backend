package com.qianrenni.common

import com.qianrenni.testutil.*

import com.qianrenni.common.util.KaptchaImageGenerator
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KaptchaImageGenerator 单元测试：验证验证码图片生成（PNG 格式、可解码）。
 */
class TestKaptchaImageGenerator {

    @Test
    fun `生成指定文本的 PNG 图片`() {
        val bytes = KaptchaImageGenerator.generate("A1B2")
        assertTrue(bytes.isNotEmpty())
        // 可解码为图片
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertTrue(image != null, "输出应为有效图片")
        assertEquals(120, image.width)
        assertEquals(80, image.height)
    }

    @Test
    fun `不同文本生成不同图片`() {
        val a = KaptchaImageGenerator.generate("AAAA")
        val b = KaptchaImageGenerator.generate("BBBB")
        assertTrue(!a.contentEquals(b), "不同文本应生成不同图片字节")
    }
}
