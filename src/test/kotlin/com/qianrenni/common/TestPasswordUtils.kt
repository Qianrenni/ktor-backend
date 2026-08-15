package com.qianrenni.common

import com.qianrenni.testutil.*

import com.qianrenni.common.util.PasswordUtils
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PasswordUtils 单元测试：Bcrypt 哈希与校验。
 */
class TestPasswordUtils {

    @Test
    fun `hash 生成的哈希可被 verify 通过`() {
        val hashed = PasswordUtils.hash("my-secret-password")
        assertTrue(PasswordUtils.verify("my-secret-password", hashed))
    }

    @Test
    fun `错误密码校验失败`() {
        val hashed = PasswordUtils.hash("my-secret-password")
        assertFalse(PasswordUtils.verify("wrong-password", hashed))
    }

    @Test
    fun `相同密码不同盐产生不同哈希`() {
        val a = PasswordUtils.hash("same-password")
        val b = PasswordUtils.hash("same-password")
        assertFalse(a == b, "Bcrypt 加盐，相同密码哈希应不同")
    }

    @Test
    fun `空密码可校验`() {
        val hashed = PasswordUtils.hash("")
        assertTrue(PasswordUtils.verify("", hashed))
    }

    @Test
    fun `verify 对非法哈希返回 false 而非崩溃`() {
        assertFalse(PasswordUtils.verify("x", "not-a-valid-hash"))
    }
}
