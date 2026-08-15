package com.qianrenni.modules.book

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.models.tables.ChapterReadStatisticsTable
import com.qianrenni.models.tables.ShelfTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.insertTestChapter
import com.qianrenni.testutil.withTestApplication
import org.jetbrains.exposed.sql.insert
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BookService 集成测试：书籍列表/目录/分类/搜索/统计，以及章节内容缓存读取。
 * 依赖 Redis 缓存（db15，每个测试前已 flushdb 复位）。
 */
class TestBookService {

    @Test
    fun `getBookList 只返回已发布激活书籍`() = withTestApplication {
        val published = insertTestBook(name = "已发布")
        val inactive = insertTestBook(name = "已下架", isActive = false)
        val reviewing = insertTestBook(name = "审核中", status = BookStatus.REVIEWING)

        val books = bookService.getBookList(listOf(published, inactive, reviewing))
        assertEquals(1, books.size)
        assertEquals("已发布", books[0].name)
    }

    @Test
    fun `getBookCatalog 按顺序返回已发布激活章节`() = withTestApplication {
        val bookId = insertTestBook()
        insertTestChapter(bookId, title = "第三章", order = 3f)
        insertTestChapter(bookId, title = "第一章", order = 1f)
        insertTestChapter(bookId, title = "第二章", order = 2f)
        insertTestChapter(bookId, title = "未发布", order = 4f, status = BookStatus.REVIEWING)
        insertTestChapter(bookId, title = "已删", order = 5f, isActive = false)

        val catalog = bookService.getBookCatalog(bookId)
        assertEquals(3, catalog.size)
        assertEquals(listOf("第一章", "第二章", "第三章"), catalog.map { it.title })
    }

    @Test
    fun `getBookSelect 分类筛选与分页`() = withTestApplication {
        insertTestBook(name = "书A", category = "玄幻")
        insertTestBook(name = "书B", category = "玄幻")
        insertTestBook(name = "书C", category = "玄幻")
        insertTestBook(name = "书D", category = "都市")
        insertTestBook(name = "未发布书", category = "玄幻", status = BookStatus.REVIEWING)

        val page1 = bookService.getBookSelect("玄幻", offSet = 0, limit = 2)
        assertEquals(2, page1.size)
        val page2 = bookService.getBookSelect("玄幻", offSet = 2, limit = 2)
        assertEquals(1, page2.size, "玄幻分类应只有 3 本已发布书籍")
        assertTrue(bookService.getBookSelect("都市", 0, 10).size == 1)
    }

    @Test
    fun `getReadCount 汇总章节阅读量`() = withTestApplication {
        val bookId = insertTestBook()
        val chapterId1 = insertTestChapter(bookId, title = "第1章", order = 1f)
        val chapterId2 = insertTestChapter(bookId, title = "第2章", order = 2f)
        databaseManager.suspendedTransaction {
            listOf(
                Triple(chapterId1, 5, 10),
                Triple(chapterId1, 7, 20),
                Triple(chapterId2, 3, 8),
            ).forEach { (cid, unique, pv) ->
                ChapterReadStatisticsTable.insert {
                    it[ChapterReadStatisticsTable.bookId] = bookId
                    it[ChapterReadStatisticsTable.chapterId] = cid
                    it[ChapterReadStatisticsTable.hourStart] = LocalDateTime.now()
                    it[ChapterReadStatisticsTable.uniqueReaderCount] = unique
                    it[ChapterReadStatisticsTable.pageViewCount] = pv
                    it[ChapterReadStatisticsTable.totalDuration] = 60f
                }
            }
        }
        assertEquals(38, bookService.getReadCount(bookId))
    }

    @Test
    fun `getFavoriteCount 返回书架收藏数`() = withTestApplication {
        val bookId = insertTestBook()
        val user1 = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val user2 = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        databaseManager.suspendedTransaction {
            listOf(user1 to bookId, user2 to bookId).forEach { (uid, bid) ->
                ShelfTable.insert {
                    it[ShelfTable.userId] = uid
                    it[ShelfTable.bookId] = bid
                }
            }
        }
        assertEquals(2, bookService.getFavoriteCount(bookId))
    }

    @Test
    fun `getBookChapter 章节不存在抛异常`() = withTestApplication {
        val bookId = insertTestBook()
        assertFailsWith<IllegalArgumentException> { bookService.getBookChapter(chapterId = 999999, bookId = bookId) }
    }

    @Test
    fun `getSearchBook 按书名或作者搜索`() = withTestApplication {
        insertTestBook(name = "剑来", author = "烽火戏诸侯")
        insertTestBook(name = "雪中悍刀行", author = "烽火戏诸侯")

        val byName = bookService.getSearchBook("剑来")
        assertEquals(1, byName.size)
        assertEquals("剑来", byName[0].name)

        val byAuthor = bookService.getSearchBook("烽火")
        assertEquals(2, byAuthor.size)
    }

    @Test
    fun `getCategory 返回去重分类列表`() = withTestApplication {
        insertTestBook(name = "书A", category = "玄幻")
        insertTestBook(name = "书B", category = "玄幻")
        insertTestBook(name = "书C", category = "都市")

        val categories = bookService.getCategory()
        assertTrue(categories.containsAll(listOf("玄幻", "都市")))
        assertEquals(2, categories.size)
    }

    @Test
    fun `getBookCount 返回已登记书籍总数`() = withTestApplication {
        insertTestBook()
        insertTestBook()
        assertEquals(2, bookService.getBookCount())
    }

    @Test
    fun `getRecommendBook 只推荐已发布书籍`() = withTestApplication {
        insertTestBook(name = "推荐书")
        insertTestBook(name = "未发布", status = BookStatus.REVIEWING)
        insertTestBook(name = "已下架", isActive = false)

        val recommends = bookService.getRecommendBook("")
        assertEquals(1, recommends.size)
        assertEquals("推荐书", recommends[0].name)
    }
}
