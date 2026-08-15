package com.qianrenni.common.util

import com.google.code.kaptcha.impl.DefaultKaptcha
import com.google.code.kaptcha.util.Config
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

object KaptchaImageGenerator {
    private val kaptcha = DefaultKaptcha().apply {
        val properties = Properties().apply {
            // 1. 图片尺寸
            setProperty("kaptcha.image.width", "120")
            setProperty("kaptcha.image.height", "80")
        }
        config = Config(properties)
    }

    fun generate(text: String): ByteArray {
        // kaptcha 自动生成文本+图片
        val image = kaptcha.createImage(text)
        val buffer = ByteArrayOutputStream()
        ImageIO.write(image, "png", buffer)
        return buffer.toByteArray()
    }
}