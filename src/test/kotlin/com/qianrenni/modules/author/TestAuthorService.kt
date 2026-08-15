package com.qianrenni.modules.author

import com.qianrenni.testutil.*

import com.qianrenni.common.appConfig
import com.qianrenni.modules.author.RequestUpdateBookChapter
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.models.tables.AuditBookChapterTable
import com.qianrenni.models.tables.AuditBookTable
import com.qianrenni.models.tables.BookChapterTable
import com.qianrenni.models.tables.BookTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.bindAuthor
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.readTestStore
import com.qianrenni.testutil.withTestApplication
import io.ktor.server.application.Application
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AuthorService 集成测试：作者书籍 CRUD（含封面与 outbox 双写）、章节提交审核、审核员分派。
 */
class TestAuthorService {

    /** 预创建书籍静态目录（updateBook 修订版场景构造源封面） */
    private fun Application.ensureBookStaticDir(bookId: Int) {
        File(appConfig.staticDir + "/book/$bookId").mkdirs()
    }

    private suspend fun Application.chapterIds(bookId: Int): List<Int> =
        databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.bookId eq bookId }
                .map { it[BookChapterTable.id].value }
        }

    @Test
    fun `createBook 创建待提交书籍并绑定作者与封面`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val cover = File.createTempFile("cover", ".webp")
        cover.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))

        authorService.createBook(authorId, "我的新书", "作者", "标签", "描述", "玄幻", cover)

        val books = authorService.getBook(authorId, emptyList())
        assertEquals(1, books.size)
        assertEquals("我的新书", books[0].name)
        assertEquals(BookStatus.PENDING, books[0].status)
        // 封面落盘（createBook 内部已建目录，对应生产修复）
        assertTrue(File(appConfig.staticDir + "/book/${books[0].id}/cover.webp").exists())
    }

    @Test
    fun `getBook 只返回该作者的书籍`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val otherId = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        val bookId = insertTestBook(name = "共享书")
        bindAuthor(authorId, bookId)
        bindAuthor(otherId, bookId)

        val own = authorService.getBook(authorId, emptyList())
        assertEquals(1, own.size)
        // 非绑定作者查不到
        val stranger = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        assertTrue(authorService.getBook(stranger, emptyList()).isEmpty())
    }

    @Test
    fun `updateBook 更新待提交书籍`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(name = "旧书名", status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)

        authorService.updateBook(authorId, bookId, "新书名", "新作者", "标签", "描述", "玄幻", null)

        val books = authorService.getBook(authorId, listOf(bookId))
        assertEquals(1, books.size)
        assertEquals("新书名", books[0].name)
        assertEquals(BookStatus.PENDING, books[0].status, "更新后应保持待提交状态")
    }

    @Test
    fun `updateBook 对已发布书籍创建负 id 修订版`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(name = "已发布原书")
        bindAuthor(authorId, bookId)
        // 预建源封面（生产缺陷：updateBook 无 mkdirs 且源封面缺失时 copyTo 抛 IO 异常）
        ensureBookStaticDir(bookId)
        File(appConfig.staticDir + "/book/$bookId/cover.webp").writeBytes(byteArrayOf(1))

        authorService.updateBook(authorId, bookId, "修订版", "作者", "", "描述", "玄幻", null)

        val books = authorService.getBook(authorId, emptyList())
        assertEquals(2, books.size, "应保留原书并新增 -bookId 修订版")
        val draft = books.first { it.id < 0 }
        assertEquals("修订版", draft.name)
        assertEquals(-bookId, draft.id)
        // 修订版封面复制自原书
        assertTrue(File(appConfig.staticDir + "/book/${-bookId}/cover.webp").exists())
    }

    @Test
    fun `deleteBook 删除待提交书籍并墓碑章节内容`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)
        authorService.updateBookChapter(RequestUpdateBookChapter(bookId, "第一章", 1f, "章节内容"), authorId)
        val chapterId = chapterIds(bookId).single()
        outboxService.processPending()
        assertEquals("章节内容", readTestStore("book", bookId.toString(), chapterId))

        authorService.deleteBook(authorId, bookId)
        outboxService.processPending()

        assertEquals("", readTestStore("book", bookId.toString(), chapterId), "章节内容应被墓碑")
        val count = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq bookId }.count()
        }
        assertEquals(0, count, "书籍行应已删除")
        assertTrue(chapterIds(bookId).isEmpty(), "章节行应已删除")
    }

    @Test
    fun `deleteBook 只能删除 PENDING 书籍`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PUBLISHED)
        bindAuthor(authorId, bookId)

        authorService.deleteBook(authorId, bookId)

        val count = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq bookId }.count()
        }
        assertEquals(1, count, "已发布书籍不应被删除")
    }

    @Test
    fun `checkAuthor 非绑定作者抛异常`() = withTestApplication {
        val bookId = insertTestBook()
        val stranger = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        assertFailsWith<IllegalArgumentException> { authorService.checkAuthor(stranger, bookId) }
    }

    @Test
    fun `getAuthorCount 返回作者资料数量`() = withTestApplication {
        // 种子数据未写入 author 表
        assertEquals(0, authorService.getAuthorCount())
    }

    @Test
    fun `getDraftChapter 只返回未发布章节`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)
        authorService.updateBookChapter(RequestUpdateBookChapter(bookId, "草稿章节", 1f, "内容"), authorId)

        val drafts = authorService.getDraftChapter(authorId)
        assertEquals(1, drafts.size)
        assertEquals("草稿章节", drafts[0].title)
    }

    @Test
    fun `updateStatusChapter 提交审核并分派审核员`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)
        authorService.updateBookChapter(RequestUpdateBookChapter(bookId, "第一章", 1f, "内容"), authorId)
        val chapterId = chapterIds(bookId).single()

        authorService.updateStatusChapter(authorId, bookId, chapterId)

        val status = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where { BookChapterTable.id eq chapterId }.single()[BookChapterTable.status]
        }
        assertEquals(BookStatus.REVIEWING, status)
        val auditCount = databaseManager.suspendedTransaction(readOnly = true) {
            AuditBookChapterTable.selectAll()
                .where { AuditBookChapterTable.bookChapterId eq chapterId }.count()
        }
        assertEquals(1, auditCount, "章节应分派给一名审核员")
    }

    @Test
    fun `updateStatusBook 提交书籍审核并分派审核员`() = withTestApplication {
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook(status = BookStatus.PENDING)
        bindAuthor(authorId, bookId)

        authorService.updateStatusBook(authorId, bookId)

        val status = databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll().where { BookTable.id eq bookId }.single()[BookTable.status]
        }
        assertEquals(BookStatus.REVIEWING, status)
        val auditCount = databaseManager.suspendedTransaction(readOnly = true) {
            AuditBookTable.selectAll().where { AuditBookTable.bookId eq bookId }.count()
        }
        assertEquals(1, auditCount, "书籍应分派给一名审核员")
    }
}
