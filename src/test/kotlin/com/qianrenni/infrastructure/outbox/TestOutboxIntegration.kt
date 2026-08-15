package com.qianrenni.infrastructure.outbox

import com.qianrenni.testutil.*

import com.qianrenni.common.appConfig
import com.qianrenni.infrastructure.storage.ContentStoreService
import com.qianrenni.modules.author.RequestUpdateBookChapter
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.common.ReportEnum
import com.qianrenni.models.tables.*
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.insertTestChapter
import com.qianrenni.testutil.withTestApplication
import io.ktor.server.application.Application
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.Path
import kotlin.test.*

/**
 * Outbox / ContentStore 双存储与定时任务集成测试。
 *
 * 覆盖"DB 元数据 + 文件内容"双写路径：各服务在事务内登记 outbox → 手动 [OutboxService.processPending]
 * 消费 → 断言文件内容与 outbox 状态；以及 [com.qianrenni.workers] 的聚合与发布定时任务。
 * 测试模式下 OutboxService 不启动 channel 消费（见 testConfigure），消费时机完全确定。
 */
class TestOutboxIntegration {

    // ================= 辅助 =================

    /** 与 OutboxService.processOne 相同的路径拼接规则读取文件内容 */
    private suspend fun Application.readStore(storeDir: String, storeName: String, contentId: Int): String =
        ContentStoreService(name = storeName, baseDir = Path(appConfig.contentDir, storeDir).toString())
            .use { it.readChapter(contentId) }

    private suspend fun Application.outboxStatus(recordId: Int): Pair<OutboxStatus, Int> =
        databaseManager.suspendedTransaction(readOnly = true) {
            val row = FileSyncOutboxTable.selectAll().where { FileSyncOutboxTable.id eq recordId }.single()
            row[FileSyncOutboxTable.status] to row[FileSyncOutboxTable.retryCount]
        }

    private suspend fun Application.pendingOutboxIds(): List<Int> =
        databaseManager.suspendedTransaction(readOnly = true) {
            FileSyncOutboxTable.selectAll().where { FileSyncOutboxTable.status eq OutboxStatus.PENDING }
                .orderBy(FileSyncOutboxTable.id to SortOrder.ASC)
                .map { it[FileSyncOutboxTable.id].value }
        }

    /** 建立作者与书籍绑定（AuthorService.checkAuthor 依赖 AuthorBookTable 记录） */
    private suspend fun Application.bindAuthor(userId: Int, bookId: Int) {
        databaseManager.suspendedTransaction {
            AuthorBookTable.insert {
                it[AuthorBookTable.userId] = userId
                it[AuthorBookTable.bookId] = bookId
            }
        }
    }

    private suspend fun Application.bookStatus(bookId: Int): BookStatus =
        databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq bookId }.single()[BookTable.status]
        }

    // ================= Outbox 消费语义 =================

    @Test
    fun `UPDATE 内容经 outbox 落盘并标记 SUCCESS`() = withTestApplication {
        val bookId = insertTestBook()
        databaseManager.suspendedTransaction {
            outboxService.write("book", bookId.toString(), 101, OutboxOp.UPDATE, "第一章内容")
        }
        val recordId = pendingOutboxIds().single()

        assertEquals(1, outboxService.processPending())

        assertEquals("第一章内容", readStore("book", bookId.toString(), 101))
        assertEquals(OutboxStatus.SUCCESS to 0, outboxStatus(recordId))
        assertEquals(0, outboxService.processPending(), "SUCCESS 记录不应再次消费")
    }

    @Test
    fun `UPDATE 缺少内容 重试耗尽标记 FAILED`() = withTestApplication {
        val bookId = insertTestBook()
        databaseManager.suspendedTransaction {
            outboxService.write("book", bookId.toString(), 202, OutboxOp.UPDATE, content = null)
        }
        val recordId = pendingOutboxIds().single()

        repeat(5) { outboxService.processPending() }

        assertEquals(OutboxStatus.FAILED to 5, outboxStatus(recordId), "MAX_RETRY=5 次后应标记 FAILED")
        // 内容从未写入：readChapter 对不存在的 id 抛异常（与墓碑返回空串语义不同）
        assertFailsWith<IllegalStateException> { readStore("book", bookId.toString(), 202) }
    }

    @Test
    fun `DELETE 墓碑使内容不可读`() = withTestApplication {
        val bookId = insertTestBook()
        ContentStoreService(name = bookId.toString(), baseDir = Path(appConfig.contentDir, "book").toString())
            .use { it.update(303, "将被删除的内容") }
        databaseManager.suspendedTransaction {
            outboxService.write("book", bookId.toString(), 303, OutboxOp.DELETE)
        }
        val recordId = pendingOutboxIds().single()

        assertEquals(1, outboxService.processPending())

        assertEquals("", readStore("book", bookId.toString(), 303))
        assertEquals(OutboxStatus.SUCCESS to 0, outboxStatus(recordId))
    }

    // ================= 服务层双写路径 =================

    @Test
    fun `updateBookChapter 内容经 outbox 落盘可回读`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)

        authorService.updateBookChapter(RequestUpdateBookChapter(bookId, "第一章", 1f, "第一章内容"), authorId)
        val chapterId = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.bookId eq bookId }.single()[BookChapterTable.id].value
        }

        // 未消费前文件不存在（DB 元数据已提交，文件待 outbox 写入）——
        // readChapter 对未写入的 contentId 抛异常
        assertFailsWith<IllegalStateException> { readStore("book", bookId.toString(), chapterId) }
        assertEquals(1, outboxService.processPending())

        assertEquals("第一章内容", authorService.getChapterContent(authorId, bookId, listOf(chapterId))[0])
    }

    @Test
    fun `deleteBookChapter 删除 PENDING 章节并墓碑内容`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)

        authorService.updateBookChapter(RequestUpdateBookChapter(bookId, "第一章", 1f, "第一章内容"), authorId)
        val chapterId = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.bookId eq bookId }.single()[BookChapterTable.id].value
        }
        outboxService.processPending()
        assertEquals("第一章内容", readStore("book", bookId.toString(), chapterId))

        authorService.deleteBookChapter(authorId, bookId, chapterId)
        outboxService.processPending()

        assertEquals("", readStore("book", bookId.toString(), chapterId))
        val chapterCount = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.id eq chapterId }.count()
        }
        assertEquals(0, chapterCount, "章节行应已删除")
    }

    @Test
    fun `createBookReview 内容经 outbox 落盘可回读`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()

        commentService.createBookReview(userId, bookId, "好书推荐，值得一读")
        assertEquals(1, outboxService.processPending())

        val review = commentService.getMyBookReview(userId, bookId)
        assertNotNull(review)
        assertEquals("好书推荐，值得一读", review!!.content)
    }

    @Test
    fun `upsertLineComment 内容经 outbox 落盘可回读`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)

        commentService.upsertLineComment(userId, bookId, chapterId, line = 10, content = "这一行写得精彩")
        assertEquals(1, outboxService.processPending())

        val comments = commentService.getChapterComments(chapterId)
        assertEquals("这一行写得精彩", comments.getValue(10).single().content)
    }

    @Test
    fun `uploadBookWithTxt 全链路创建书籍章节与内容`() = withTestApplication {
        val txtFile = File.createTempFile("upload-book", ".txt")
        txtFile.writeText("第一章 初入江湖\n江湖内容第一行\n第二章 终章\n尾声内容")

        val bookId = adminService.uploadBookWithTxt(
            name = "测试之书", author = "测试作者", description = "", category = "玄幻",
            tags = "热血", coverFile = null, txtFile = txtFile
        )
        assertEquals(2, outboxService.processPending())

        val chapters = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.bookId eq bookId }
                .orderBy(BookChapterTable.order to SortOrder.ASC)
                .map { it.toBookCatalogItem() }
        }
        assertEquals(2, chapters.size)
        assertEquals("初入江湖", chapters[0].title)
        assertEquals("终章", chapters[1].title)
        assertEquals("江湖内容第一行", readStore("book", bookId.toString(), chapters[0].id))
        assertEquals("尾声内容", readStore("book", bookId.toString(), chapters[1].id))
    }

    // ================= workers 定时任务 =================

    @Test
    fun `publishBook 直接发布 APPROVED 书籍与章节`() = withTestApplication {
        val bookId = insertTestBook(status = BookStatus.APPROVED)
        insertTestChapter(bookId, title = "第一章", order = 1f, status = BookStatus.APPROVED)

        bookService.publishBook()

        assertEquals(BookStatus.PUBLISHED, bookStatus(bookId))
        val chapterStatus = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.bookId eq bookId }
                .single()[BookChapterTable.status]
        }
        assertEquals(BookStatus.PUBLISHED, chapterStatus)
    }

    @Test
    fun `publishBook 负数章节合并到正数章节并墓碑源章节`() = withTestApplication {
        val bookId = insertTestBook(status = BookStatus.APPROVED)
        val targetId = insertTestChapter(bookId, title = "旧版第一章", order = 1f, status = BookStatus.APPROVED)
        val sourceId = insertTestChapter(bookId, title = "新版第一章", order = -1f, status = BookStatus.APPROVED)
        // 预写源章节内容（模拟作者已提交的新内容）
        ContentStoreService(name = bookId.toString(), baseDir = Path(appConfig.contentDir, "book").toString())
            .use { it.update(sourceId, "新版第一章内容") }

        bookService.publishBook()

        // 源章节行已删、目标章节保留；文件操作待 outbox 消费
        val chapterIds = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.bookId eq bookId }.map { it[BookChapterTable.id].value }
        }
        assertEquals(listOf(targetId), chapterIds)
        assertEquals(BookStatus.PUBLISHED, bookStatus(bookId))

        outboxService.processPending()

        assertEquals("新版第一章内容", readStore("book", bookId.toString(), targetId), "目标章节内容应被源章节覆盖")
        assertEquals("", readStore("book", bookId.toString(), sourceId), "源章节内容应被墓碑")
        val successCount = databaseManager.suspendedTransaction(readOnly = true) {
            FileSyncOutboxTable.selectAll().where { FileSyncOutboxTable.status eq OutboxStatus.SUCCESS }.count().toInt()
        }
        assertEquals(2, successCount, "UPDATE 目标 + DELETE 源 两条记录都应成功")
    }

    @Test
    fun `publishBook 负数书籍合并到目标书籍`() = withTestApplication {
        val targetBookId = insertTestBook(name = "原书名")
        databaseManager.suspendedTransaction {
            BookTable.insert {
                it[BookTable.id] = -targetBookId
                it[BookTable.name] = "新书名"
                it[BookTable.author] = "新作者"
                it[BookTable.description] = "新描述"
                it[BookTable.category] = "玄幻"
                it[BookTable.tags] = ""
                it[BookTable.totalChapter] = 0
                it[BookTable.wordsCount] = 0
                it[BookTable.isActive] = true
                it[BookTable.isEnded] = false
                it[BookTable.status] = BookStatus.APPROVED
            }
        }

        bookService.publishBook()

        val target = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq targetBookId }.single().toBook(appConfig.serverUrl)
        }
        assertEquals(BookStatus.PUBLISHED, target.status)
        assertEquals("新书名", target.name)
        assertEquals("新作者", target.author)
        val negativeCount = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq -targetBookId }.count()
        }
        assertEquals(0, negativeCount, "临时书籍行应被删除")
    }

    @Test
    fun `publishReviewTimeoutBooks 只发布审核超时书籍`() = withTestApplication {
        val overdueId = insertTestBook(name = "超时书", status = BookStatus.REVIEWING)
        val freshId = insertTestBook(name = "新书", status = BookStatus.REVIEWING)
        databaseManager.suspendedTransaction {
            BookTable.update({ BookTable.id eq overdueId }) {
                it[BookTable.updatedAt] = LocalDateTime.now().minusDays(4)
            }
        }

        bookService.publishReviewTimeoutBooks()

        assertEquals(BookStatus.PUBLISHED, bookStatus(overdueId))
        assertEquals(BookStatus.REVIEWING, bookStatus(freshId), "未超时书籍不应被自动发布")
    }

    @Test
    fun `aggregateUserReadStatistics 聚合 PV UV 时长并清理事件`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)
        val u1 = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val u2 = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val hour = LocalDateTime.of(2026, 8, 1, 10, 0)
        databaseManager.suspendedTransaction {
            fun event(userId: Int, time: LocalDateTime, type: ReportEnum) {
                UserReadEventTable.insert {
                    it[UserReadEventTable.userId] = userId
                    it[UserReadEventTable.bookId] = bookId
                    it[UserReadEventTable.chapterId] = chapterId
                    it[UserReadEventTable.eventTime] = time
                    it[UserReadEventTable.eventType] = type
                }
            }
            event(u1, hour, ReportEnum.ENTER)
            event(u1, hour.plusMinutes(5), ReportEnum.EXIT)
            event(u2, hour.plusMinutes(1), ReportEnum.ENTER)
            event(u2, hour.plusMinutes(11), ReportEnum.EXIT)
        }

        statisticsService.aggregateUserReadStatistics(hour.plusHours(1))

        val stats = databaseManager.suspendedTransaction(readOnly = true) {
            ChapterReadStatisticsTable.selectAll().toList()
        }
        assertEquals(1, stats.size)
        assertEquals(bookId, stats[0][ChapterReadStatisticsTable.bookId])
        assertEquals(chapterId, stats[0][ChapterReadStatisticsTable.chapterId])
        assertEquals(hour, stats[0][ChapterReadStatisticsTable.hourStart])
        assertEquals(2, stats[0][ChapterReadStatisticsTable.pageViewCount], "PV=2 个 ENTER 事件")
        assertEquals(2, stats[0][ChapterReadStatisticsTable.uniqueReaderCount], "UV=2 个用户")
        assertEquals(900f, stats[0][ChapterReadStatisticsTable.totalDuration], "300s + 600s")
        val eventCount = databaseManager.suspendedTransaction(readOnly = true) {
            UserReadEventTable.selectAll().count()
        }
        assertEquals(0, eventCount, "已聚合事件应被清理")
    }

    @Test
    fun `aggregateUserReadStatistics 无事件不产生统计`() = withTestApplication {
        val hour = LocalDateTime.of(2026, 8, 1, 10, 0)
        statisticsService.aggregateUserReadStatistics(hour.plusHours(1))
        val count = databaseManager.suspendedTransaction(readOnly = true) {
            ChapterReadStatisticsTable.selectAll().count()
        }
        assertEquals(0, count)
    }
}
