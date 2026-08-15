package com.qianrenni.modules.user

import com.qianrenni.testutil.*

import com.qianrenni.modules.user.RequestTokenGet
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.infrastructure.database.redisManager
import com.qianrenni.models.tables.UserTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.withTestApplication
import com.qianrenni.common.util.PasswordUtils
import com.qianrenni.common.util.TokenGenerator
import kotlinx.coroutines.future.await
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UserService 集成测试：用户查询、注册、登录（验证码）、改密、忘记密码。
 * 依赖 H2 种子数据 + Redis（db15，用于验证码）。
 */
class TestUserService {

    private suspend fun seedCaptcha(application: io.ktor.server.application.Application, text: String = "AbCd"): String {
        val captchaId = TokenGenerator.uuid()
        application.redisManager.getAsyncCommands().setex(captchaId, 120, text).await()
        return captchaId
    }

    /** FullUser.toFullUser 不包含 password 字段，密码哈希需直查 DB */
    private suspend fun io.ktor.server.application.Application.dbPassword(email: String): String =
        databaseManager.suspendedTransaction(readOnly = true) {
            UserTable.selectAll().where { UserTable.email eq email }.single()[UserTable.password]
        }

    @Test
    fun `getUserById 返回种子用户及其权限段`() = withTestApplication {
        val user = userService.getUserById(TestUsers.userIds.getValue(TestUsers.USER_NAME))
        assertEquals(TestUsers.USER_NAME, user.userName)
        assertTrue(user.isActive)
        assertTrue(user.right.isNotEmpty(), "普通用户应拥有 USER 角色对应的权限段")
    }

    @Test
    fun `getUserById 用户不存在抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { userService.getUserById(999999) }
    }

    @Test
    fun `getUserById 禁用用户抛异常`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        databaseManager.suspendedTransaction {
            UserTable.update({ UserTable.id eq userId }) { it[UserTable.isActive] = false }
        }
        assertFailsWith<IllegalArgumentException> { userService.getUserById(userId) }
    }

    @Test
    fun `getUserCount 返回种子用户数量`() = withTestApplication {
        assertEquals(4, userService.getUserCount())
    }

    @Test
    fun `createUser 注册新用户并持久化密码哈希`() = withTestApplication {
        userService.createUser("新读者", "reader@test.com", "reader123")
        val user = userService.getUserByEmail("reader@test.com")
        assertEquals("新读者", user.userName)
        assertTrue(PasswordUtils.verify("reader123", dbPassword("reader@test.com")), "数据库应保存可验证的密码哈希")
    }

    @Test
    fun `createUser 邮箱已被注册抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            userService.createUser(TestUsers.USER_NAME, "user1@test.com", "whatever")
        }
    }

    @Test
    fun `login 验证码错误抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            userService.login(
                xCaptchaId = "not_exist_id",
                requestTokenGet = RequestTokenGet(
                    userName = TestUsers.USER_NAME,
                    password = TestUsers.USER_PASSWORD,
                    captcha = "xxxx"
                )
            )
        }
    }

    @Test
    fun `login 密码错误抛异常`() = withTestApplication {
        val captchaId = seedCaptcha(this)
        assertFailsWith<IllegalArgumentException> {
            userService.login(
                xCaptchaId = captchaId,
                requestTokenGet = RequestTokenGet(
                    userName = "user1@test.com",
                    password = "wrong-password",
                    captcha = "AbCd"
                )
            )
        }
    }

    @Test
    fun `login 成功返回用户与权限段`() = withTestApplication {
        val captchaId = seedCaptcha(this)
        // login 以邮箱作为账号查询
        val user = userService.login(
            xCaptchaId = captchaId,
            requestTokenGet = RequestTokenGet(
                userName = "user1@test.com",
                password = TestUsers.USER_PASSWORD,
                captcha = "AbCd"
            )
        )
        assertEquals(TestUsers.USER_NAME, user.userName)
        assertTrue(user.right.isNotEmpty())
        // 验证码一次性：重放应失败
        assertFailsWith<IllegalArgumentException> {
            userService.login(
                xCaptchaId = captchaId,
                requestTokenGet = RequestTokenGet(
                    userName = "user1@test.com",
                    password = TestUsers.USER_PASSWORD,
                    captcha = "AbCd"
                )
            )
        }
    }

    @Test
    fun `updatePassword 旧密码错误抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            userService.updatePassword("user1@test.com", "wrong-old", "newpass1")
        }
    }

    @Test
    fun `updatePassword 修改成功`() = withTestApplication {
        assertTrue(userService.updatePassword("user1@test.com", TestUsers.USER_PASSWORD, "newpass1"))
        assertTrue(PasswordUtils.verify("newpass1", dbPassword("user1@test.com")))
        assertFalse(PasswordUtils.verify(TestUsers.USER_PASSWORD, dbPassword("user1@test.com")))
    }

    @Test
    fun `getUserByEmail 用户不存在抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { userService.getUserByEmail("nobody@test.com") }
    }

    @Test
    fun `forgotPassword 验证码错误抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            userService.forgotPassword("user1@test.com", "reset123", "000000")
        }
    }

    @Test
    fun `forgotPassword 验证码正确重置密码`() = withTestApplication {
        val code = captchaService.getVerifyCode("forgot_password:user1@test.com")
        assertTrue(userService.forgotPassword("user1@test.com", "reset123", code))
        assertTrue(PasswordUtils.verify("reset123", dbPassword("user1@test.com")))
    }

    @Test
    fun `getUserAvatar 返回静态头像地址`() = withTestApplication {
        assertTrue(userService.getUserAvatar(1).endsWith("/static/guga.webp"))
    }
}
