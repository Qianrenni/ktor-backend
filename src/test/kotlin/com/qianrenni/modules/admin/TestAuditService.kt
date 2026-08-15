package com.qianrenni.modules.admin

import com.qianrenni.testutil.*

import com.qianrenni.common.appConfig
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.infrastructure.storage.ContentStoreService
import com.qianrenni.models.tables.AuditBookChapterTable
import com.qianrenni.models.tables.AuditBookTable
import com.qianrenni.models.tables.BookChapterTable
import com.qianrenni.models.tables.BookTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.insertTestChapter
import com.qianrenni.testutil.withTestApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AuditService 集成测试：审核人校验、待审书籍/章节查询、审核通过/拒绝。
 */
class TestAuditService {

    private suspend fun io.ktor.server.application.Application.seedAuditBook(bookId: Int, userId: Int) {
        databaseManager.suspendedTransaction {
            AuditBookTable.insert {
                it[AuditBookTable.bookId] = bookId
                it[AuditBookTable.userId] = userId
            }
        }
    }

    private suspend fun io.ktor.server.application.Application.seedAuditChapter(chapterId: Int, userId: Int) {
        databaseManager.suspendedTransaction {
            AuditBookChapterTable.insert {
                it[AuditBookChapterTable.bookChapterId] = chapterId
                it[AuditBookChapterTable.userId] = userId
            }
        }
    }

    @Test
    fun `checkAuditor 通过审核关系校验`() = withTestApplication {
        val bookId = insertTestBook()
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        seedAuditBook(bookId, reviewerId)

        auditService.checkAuditor(reviewerId, bookId) // 不抛
        assertFailsWith<IllegalArgumentException> { auditService.checkAuditor(userId, bookId) }
    }

    @Test
    fun `getAuditorCount 返回审核员数量`() = withTestApplication {
        assertEquals(1, auditService.getAuditorCount(), "种子数据应只有 reviewer1 一名审核员")
    }

    @Test
    fun `checkAuditBook 返回书籍审核人`() = withTestApplication {
        val bookId = insertTestBook()
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditBook(bookId, reviewerId)

        assertEquals(reviewerId, auditService.checkAuditBook(bookId))
        assertEquals(null, auditService.checkAuditBook(999999))
    }

    @Test
    fun `checkAuditChapter 返回章节审核人`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditChapter(chapterId, reviewerId)

        assertEquals(reviewerId, auditService.checkAuditChapter(chapterId))
        assertEquals(null, auditService.checkAuditChapter(999999))
    }

    @Test
    fun `getAbsentAuditor 返回审核员`() = withTestApplication {
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        assertEquals(reviewerId, auditService.getAbsentAuditor())
    }

    @Test
    fun `getAuditBooks 返回审核人负责的书籍`() = withTestApplication {
        val bookId1 = insertTestBook(name = "书1")
        val bookId2 = insertTestBook(name = "书2")
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditBook(bookId1, reviewerId)

        val books = auditService.getAuditBooks(reviewerId, emptyList())
        assertEquals(1, books.size)
        assertEquals("书1", books[0].name)

        val filtered = auditService.getAuditBooks(reviewerId, listOf(bookId2))
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `getAuditChapters 返回审核人负责的章节`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId, title = "待审章节")
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditChapter(chapterId, reviewerId)

        val chapters = auditService.getAuditChapters(reviewerId)
        assertEquals(1, chapters.size)
        assertEquals("待审章节", chapters[0].title)
    }

    @Test
    fun `getAuditChaptersByOrder 按序号返回章节并校验审核人`() = withTestApplication {
        val bookId = insertTestBook()
        val chapter2 = insertTestChapter(bookId, title = "第二章", order = 2f)
        val chapter1 = insertTestChapter(bookId, title = "第一章", order = 1f)
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditBook(bookId, reviewerId)

        val chapters = auditService.getAuditChaptersByOrder(reviewerId, bookId, listOf(2f, 1f))
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })

        // 非审核人访问被拒绝
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        assertFailsWith<IllegalArgumentException> {
            auditService.getAuditChaptersByOrder(userId, bookId, listOf(1f))
        }
    }

    @Test
    fun `updateBookChapter 审核通过与拒绝`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId, status = BookStatus.REVIEWING)
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditBook(bookId, reviewerId)

        auditService.updateBookChapter(reviewerId, bookId, chapterId, isPass = true)
        val approved = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll()
                .where { BookChapterTable.id eq chapterId }
                .single()[BookChapterTable.status]
        }
        assertEquals(BookStatus.APPROVED, approved)

        auditService.updateBookChapter(reviewerId, bookId, chapterId, isPass = false)
        val rejected = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll()
                .where { BookChapterTable.id eq chapterId }
                .single()[BookChapterTable.status]
        }
        assertEquals(BookStatus.REJECTED, rejected)
    }

    @Test
    fun `updateBook 审核通过与拒绝`() = withTestApplication {
        val bookId = insertTestBook(status = BookStatus.REVIEWING)
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditBook(bookId, reviewerId)

        auditService.updateBook(reviewerId, bookId, isPass = true)
        auditService.updateBook(reviewerId, bookId, isPass = false)

        val status = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll()
                .where { BookTable.id eq bookId }
                .single()[BookTable.status]
        }
        assertEquals(BookStatus.REJECTED, status)
    }

    @Test
    fun `getAuditContentChapter 返回章节内容`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId, title = "第一章", order = 1f)
        val reviewerId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        seedAuditBook(bookId, reviewerId)

        // 先写入章节内容文件（ContentStore 直接写入），再通过审核路径读取
        ContentStoreService(name = bookId.toString(), baseDir = appConfig.contentDir + "/book").use { store ->
            store.update(chapterId, "审核中看到的章节内容")
        }

        val contents = auditService.getAuditContentChapter(reviewerId, bookId, listOf(1f))
        assertEquals(listOf("审核中看到的章节内容"), contents)
    }
}
