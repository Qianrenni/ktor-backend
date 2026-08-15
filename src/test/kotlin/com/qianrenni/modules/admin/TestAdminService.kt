package com.qianrenni.modules.admin

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.models.tables.BookTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.bindAuthor
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.withTestApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AdminService 集成测试：用户管理（分页/详情/状态）、书籍管理（分页/详情/更新/上下架）。
 */
class TestAdminService {

    @Test
    fun `getUsers 分页与关键词过滤`() = withTestApplication {
        val all = adminService.getUsers(page = 1, size = 10)
        assertEquals(4, all.total, "种子数据应有 4 个用户")
        assertEquals(4, all.items.size)
        assertTrue(all.items.all { it.roles.isNotEmpty() }, "每个用户应返回角色")

        val filtered = adminService.getUsers(page = 1, size = 10, keyword = "author")
        assertEquals(1, filtered.total)
        assertEquals(TestUsers.AUTHOR_NAME, filtered.items[0].user.userName)

        val byEmail = adminService.getUsers(page = 1, size = 10, keyword = "user1@test.com")
        assertEquals(1, byEmail.total)
    }

    @Test
    fun `getUserDetail 返回用户与角色`() = withTestApplication {
        val detail = adminService.getUserDetail(TestUsers.userIds.getValue(TestUsers.USER_NAME))
        assertEquals(TestUsers.USER_NAME, detail.user.userName)
        assertTrue(detail.roles.isNotEmpty())
        assertEquals(TestUsers.userIds.getValue(TestUsers.USER_NAME), detail.roles.first().userId)
    }

    @Test
    fun `getUserDetail 用户不存在抛异常`() = withTestApplication {
        assertFailsWith<IllegalStateException> { adminService.getUserDetail(999999) }
    }

    @Test
    fun `updateUserStatus 禁用后无法登录查询`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        adminService.updateUserStatus(userId, isActive = false)
        // 禁用用户 getUserById 抛异常（与 UserService 语义一致）
        assertFailsWith<IllegalArgumentException> { userService.getUserById(userId) }

        adminService.updateUserStatus(userId, isActive = true)
        // 恢复后可查询
        assertEquals(TestUsers.USER_NAME, userService.getUserById(userId).userName)
    }

    @Test
    fun `getAdminBooks 返回全部书籍并统计作者数`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(name = "管理书籍")
        bindAuthor(authorId, bookId)
        insertTestBook(name = "无作者书籍", isActive = false)

        val page = adminService.getAdminBooks(page = 1, size = 10)
        assertEquals(2, page.total, "管理员视图不过滤状态")
        val managed = page.items.first { it.id == bookId }
        assertEquals(1, managed.authorCount)
        val noAuthor = page.items.first { it.id != bookId }
        assertEquals(0, noAuthor.authorCount)
    }

    @Test
    fun `getAdminBooks 关键词过滤`() = withTestApplication {
        insertTestBook(name = "剑来", author = "烽火")
        insertTestBook(name = "雪中", author = "烽火")
        insertTestBook(name = "其他", author = "别人")

        val byName = adminService.getAdminBooks(page = 1, size = 10, keyword = "剑来")
        assertEquals(1, byName.total)
        val byAuthor = adminService.getAdminBooks(page = 1, size = 10, keyword = "烽火")
        assertEquals(2, byAuthor.total)
    }

    @Test
    fun `getAdminBookDetail 返回书籍与作者数`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(name = "详情书")
        bindAuthor(authorId, bookId)

        val detail = adminService.getAdminBookDetail(bookId)
        assertEquals("详情书", detail.name)
        assertEquals(1, detail.authorCount)
    }

    @Test
    fun `getAdminBookDetail 书籍不存在抛异常`() = withTestApplication {
        assertFailsWith<IllegalStateException> { adminService.getAdminBookDetail(999999) }
    }

    @Test
    fun `updateAdminBook 无作者关联可修改`() = withTestApplication {
        val bookId = insertTestBook(name = "原书名", category = "玄幻")

        adminService.updateAdminBook(bookId, name = "新书名", category = "都市")

        val book = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq bookId }.single()
        }
        assertEquals("新书名", book[BookTable.name])
        assertEquals("都市", book[BookTable.category])
    }

    @Test
    fun `updateAdminBook 有作者关联拒绝修改`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(name = "作者的书")
        bindAuthor(authorId, bookId)

        assertFailsWith<IllegalArgumentException> {
            adminService.updateAdminBook(bookId, name = "不应生效")
        }
    }

    @Test
    fun `toggleBookActiveStatus 切换激活状态`() = withTestApplication {
        val bookId = insertTestBook()

        adminService.toggleBookActiveStatus(bookId, isActive = false)
        assertEquals(0, bookService.getBookList(listOf(bookId)).size, "下架后不出现在书籍列表")

        adminService.toggleBookActiveStatus(bookId, isActive = true)
        assertEquals(1, bookService.getBookList(listOf(bookId)).size, "恢复后重新可见")
    }

    @Test
    fun `toggleBookActiveStatus 书籍不存在抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            adminService.toggleBookActiveStatus(999999, isActive = false)
        }
    }
}
