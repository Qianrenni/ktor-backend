package com.qianrenni.common

import com.qianrenni.testutil.*

import com.qianrenni.common.util.TokenGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TokenGenerator 单元测试：UUID / URL 安全 Base64 / NanoId 三种 Token 生成器。
 */
class TestTokenGenerator {

    @Test
    fun `uuid 生成 32 位无连字符十六进制`() {
        val token = TokenGenerator.uuid()
        assertEquals(32, token.length)
        assertFalse(token.contains("-"))
        assertTrue(token.matches(Regex("[0-9a-fA-F]{32}")), "应为十六进制字符: $token")
    }

    @Test
    fun `uuid 每次生成不同值`() {
        val a = TokenGenerator.uuid()
        val b = TokenGenerator.uuid()
        assertFalse(a == b)
    }

    @Test
    fun `secureRandom 默认长度 22 且仅含 URL 安全字符`() {
        val token = TokenGenerator.secureRandom()
        assertEquals(22, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")), "应仅含 URL 安全字符: $token")
    }

    @Test
    fun `secureRandom 自定义字节长度`() {
        assertEquals(11, TokenGenerator.secureRandom(byteLength = 8).length, "8 字节 → 11 位 Base64")
        assertEquals(43, TokenGenerator.secureRandom(byteLength = 32).length, "32 字节 → 43 位 Base64")
    }

    @Test
    fun `nanoId 默认长度 16 且不含易混淆字符`() {
        val token = TokenGenerator.nanoId()
        assertEquals(16, token.length)
        // 字母表排除了 0 O o 1 I i l L
        assertFalse(token.any { it in "0Oo1IilL" }, "不应包含易混淆字符: $token")
        assertTrue(token.matches(Regex("[A-Za-z0-9]+")))
    }

    @Test
    fun `nanoId 自定义长度`() {
        val token = TokenGenerator.nanoId(length = 32)
        assertEquals(32, token.length)
    }

    @Test
    fun `nanoId 长度 1 也能生成`() {
        val token = TokenGenerator.nanoId(length = 1)
        assertEquals(1, token.length)
    }
}
