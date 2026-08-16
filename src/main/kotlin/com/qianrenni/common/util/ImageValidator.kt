package com.qianrenni.common.util

import java.io.File

/**
 * 图片内容校验工具：通过文件头（魔数）校验内容是否真的为受支持图片，
 * 防止任意内容（HTML/脚本等）伪装成图片上传到静态目录（M6）。
 */
object ImageValidator {

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
    private val GIF = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte())
    // WebP: "RIFF"...."WEBP"（RIFF 头 4 字节 + 尺寸 4 字节 + WEBP 4 字节）

    /**
     * 校验文件是否为受支持的图片（JPEG/PNG/GIF/WebP）。
     * @throws IllegalArgumentException 文件不存在、过小或不是受支持的图片格式
     */
    fun requireImage(file: File) {
        if (!file.exists() || file.length() < 12) {
            throw IllegalArgumentException("图片文件无效")
        }
        val header = file.inputStream().use { it.readNBytes(12) }
        val ok = startsWith(header, JPEG) ||
            startsWith(header, PNG) ||
            startsWith(header, GIF) ||
            isWebp(header)
        if (!ok) {
            throw IllegalArgumentException("仅支持 JPEG/PNG/GIF/WebP 格式图片")
        }
    }

    private fun startsWith(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        for (i in magic.indices) {
            if (data[i] != magic[i]) return false
        }
        return true
    }

    private fun isWebp(header: ByteArray): Boolean =
        header.size >= 12 &&
            header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && // R I
            header[2] == 0x46.toByte() && header[3] == 0x46.toByte() && // F F
            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() && // W E
            header[10] == 0x42.toByte() && header[11] == 0x50.toByte()  // B P
}
