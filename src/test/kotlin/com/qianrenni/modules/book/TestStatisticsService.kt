package com.qianrenni.modules.book

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.ReportEnum
import com.qianrenni.models.tables.UserReadEventTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.insertTestChapter
import com.qianrenni.testutil.withTestApplication
import org.jetbrains.exposed.sql.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * StatisticsService 集成测试：阅读事件登记（user_read_event）。
 */
class TestStatisticsService {

    @Test
    fun `addUserReadEvent 插入阅读事件`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        statisticsService.addUserReadEvent(userId, bookId, chapterId, ReportEnum.ENTER)

        val rows = databaseManager.suspendedTransaction(readOnly = true) {
            UserReadEventTable.selectAll().where { UserReadEventTable.userId eq userId }.toList()
        }
        assertEquals(1, rows.size)
        assertEquals(bookId, rows[0][UserReadEventTable.bookId])
        assertEquals(chapterId, rows[0][UserReadEventTable.chapterId])
        assertEquals(ReportEnum.ENTER, rows[0][UserReadEventTable.eventType])
    }

    @Test
    fun `addUserReadEvent 记录多种事件类型`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        statisticsService.addUserReadEvent(userId, bookId, chapterId, ReportEnum.ENTER)
        statisticsService.addUserReadEvent(userId, bookId, chapterId, ReportEnum.HEARTBEAT)

        val count = databaseManager.suspendedTransaction(readOnly = true) {
            UserReadEventTable.selectAll().where { UserReadEventTable.userId eq userId }.count()
        }
        assertEquals(2, count.toInt())
    }
}
