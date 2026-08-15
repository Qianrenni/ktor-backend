package com.qianrenni.modules.book

import com.qianrenni.testutil.*

import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.withTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ReadProgressService 集成测试：阅读进度插入、同书 UPSERT、删除。
 */
class TestReadProgressService {

    @Test
    fun `add 插入阅读进度并可查询`() = withTestApplication {
        val bookId = insertTestBook()
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        readProgressService.add(userId, bookId, lastChapterId = 10, lastPosition = 500)

        val progress = readProgressService.get(userId)
        assertEquals(1, progress.size)
        assertEquals(bookId, progress[0].bookId)
        assertEquals(10, progress[0].lastChapterId)
        assertEquals(500, progress[0].lastPosition)
    }

    @Test
    fun `add 同书多次调用为 UPSERT 不产生重复`() = withTestApplication {
        val bookId = insertTestBook()
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        readProgressService.add(userId, bookId, lastChapterId = 10, lastPosition = 500)
        readProgressService.add(userId, bookId, lastChapterId = 20, lastPosition = 1200)

        val progress = readProgressService.get(userId)
        assertEquals(1, progress.size, "同一用户同一书籍只应保留一条进度")
        assertEquals(20, progress[0].lastChapterId)
        assertEquals(1200, progress[0].lastPosition)
    }

    @Test
    fun `get 过滤非激活书籍`() = withTestApplication {
        val inactiveBookId = insertTestBook(name = "已下架", isActive = false)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        readProgressService.add(userId, inactiveBookId, lastChapterId = 1, lastPosition = 10)

        assertTrue(readProgressService.get(userId).isEmpty())
    }

    @Test
    fun `delete 移除阅读进度`() = withTestApplication {
        val bookId = insertTestBook()
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        readProgressService.add(userId, bookId, lastChapterId = 1, lastPosition = 10)
        readProgressService.delete(userId, bookId)

        assertTrue(readProgressService.get(userId).isEmpty())
    }
}
