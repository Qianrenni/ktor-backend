package com.qianrenni.common.util

import java.security.SecureRandom
import java.util.*
import kotlin.math.ceil

object TokenGenerator {

    private val secureRandom = SecureRandom()

    // URL安全的Base64编码器,去除末尾的填充符 '='
    private val urlSafeBase64 = Base64.getUrlEncoder().withoutPadding()

    /**
     * 生成 32 位无连字符的 UUID Token
     * 示例: 550e8400e29b41d4a716446655440000
     * 特点：标准、绝对唯一、安全性极高,但长度固定为 32 字符。
     */
    fun uuid(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    /**
     * 生成 URL 安全的 Base64 随机 Token (推荐,速度最快)
     * 示例: V2VsbERvbmUxMjM0NTY3ODkw (22位)
     * 特点：生成速度极快,长度短。包含大小写字母、数字以及 `-` 和 `_`。
     *
     * @param byteLength 随机字节数,默认 16 (会生成 22 位长度的字符串)
     */
    fun secureRandom(byteLength: Int = 16): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return urlSafeBase64.encodeToString(bytes)
    }

    /**
     * 生成纯字母数字的短 Token (类似 NanoId,最友好)
     * 示例: aB3dE5fG7hJ9kL1m (16位)
     * 特点：剔除了易混淆字符(如 0/O, 1/l/I),纯字母数字,非常适合在 URL 参数或短信中传递。
     *
     * @param length Token 长度,默认 16
     */
    fun nanoId(length: Int = 16): String {
        // 剔除了 0, O, o, 1, I, i, l, L 等易混淆字符 (共 56 个字符)
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray()

        // 计算位掩码,用于快速截取随机字节
        val mask = (1 shl (32 - Integer.numberOfLeadingZeros(alphabet.size - 1))) - 1
        val sb = StringBuilder(length)

        // 1.6 是经验系数,用于减少 SecureRandom.nextBytes 的调用次数,提升性能
        val step = ceil(1.6 * mask * length / alphabet.size).toInt()

        while (sb.length < length) {
            val bytes = ByteArray(step)
            secureRandom.nextBytes(bytes)
            for (b in bytes) {
                val index = b.toInt() and mask
                if (index < alphabet.size) {
                    sb.append(alphabet[index])
                    if (sb.length == length) break
                }
            }
        }
        return sb.toString()
    }
}