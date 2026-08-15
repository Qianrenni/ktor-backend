package com.qianrenni.modules.book

import com.qianrenni.testutil.*

import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.withTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ShelfService 集成测试：书架添加、查询（过滤非激活书籍）、删除。
 */
class TestShelfService {

    @Test
    fun `add 后 get 返回书架项`() = withTestApplication {
        val bookId = insertTestBook()
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        shelfService.add(bookId, userId)

        val shelves = shelfService.get(userId)
        assertEquals(1, shelves.size)
        assertEquals(bookId, shelves[0].bookId)
        assertEquals(userId, shelves[0].userId)
    }

    @Test
    fun `get 过滤非激活书籍`() = withTestApplication {
        val activeBookId = insertTestBook(name = "激活书籍")
        val inactiveBookId = insertTestBook(name = "下架书籍", isActive = false)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        shelfService.add(activeBookId, userId)
        shelfService.add(inactiveBookId, userId)

        val shelves = shelfService.get(userId)
        assertEquals(1, shelves.size, "非激活书籍不应出现在书架中")
        assertEquals(activeBookId, shelves[0].bookId)
    }

    @Test
    fun `get 区分不同用户的书架`() = withTestApplication {
        val bookId = insertTestBook()
        val user1 = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val user2 = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)

        shelfService.add(bookId, user1)

        assertTrue(shelfService.get(user1).isNotEmpty())
        assertTrue(shelfService.get(user2).isEmpty())
    }

    @Test
    fun `delete 移除书架项`() = withTestApplication {
        val bookId = insertTestBook()
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        shelfService.add(bookId, userId)
        shelfService.delete(bookId, userId)

        assertTrue(shelfService.get(userId).isEmpty())
    }

    @Test
    fun `add 不存在的书籍抛异常`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        // 外键约束：H2 REFERENTIAL_INTEGRITY 开启，插入不存在的 bookId 应失败
        kotlin.test.assertFailsWith<Exception> { shelfService.add(999999, userId) }
    }
}
