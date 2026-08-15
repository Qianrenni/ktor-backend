package com.qianrenni.modules.book

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.models.tables.BookChapterCommentTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.insertTestBook
import com.qianrenni.testutil.insertTestChapter
import com.qianrenni.testutil.readTestStore
import com.qianrenni.testutil.withTestApplication
import org.jetbrains.exposed.sql.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CommentService 集成测试：书评分页查询、书评软删除、章节行评论查询；
 * 以及两个已知缺陷的固化测试（getMyBookReview 不过滤已删除、deleteLineComment 跨表条件）。
 */
class TestCommentService {

    @Test
    fun `getBookReviews 分页返回已发布书评及内容`() = withTestApplication {
        val u1 = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val u2 = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook()
        commentService.createBookReview(u1, bookId, "评论一")
        commentService.createBookReview(u2, bookId, "评论二")
        outboxService.processPending()

        val page = commentService.getBookReviews(bookId, page = 1, size = 10, parentId = null)
        assertEquals(2, page.total)
        assertEquals(setOf("评论一", "评论二"), page.items.map { it.content }.toSet())
        assertEquals("评论一", page.items.first { it.userId == u1 }.content)
    }

    @Test
    fun `getBookReviews 分页生效`() = withTestApplication {
        val u1 = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val u2 = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val u3 = TestUsers.userIds.getValue(TestUsers.REVIEWER_NAME)
        val bookId = insertTestBook()
        listOf(u1 to "评论A", u2 to "评论B", u3 to "评论C").forEach { (uid, content) ->
            commentService.createBookReview(uid, bookId, content)
        }
        outboxService.processPending()

        val page1 = commentService.getBookReviews(bookId, page = 1, size = 2, parentId = null)
        val page2 = commentService.getBookReviews(bookId, page = 2, size = 2, parentId = null)
        assertEquals(3, page1.total)
        assertEquals(2, page1.items.size)
        assertEquals(1, page2.items.size)
    }

    @Test
    fun `deleteMyReview 软删除后分页不再可见`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()
        commentService.createBookReview(userId, bookId, "将被删除的评论")
        outboxService.processPending()

        commentService.deleteMyReview(userId, bookId)

        val page = commentService.getBookReviews(bookId, page = 1, size = 10, parentId = null)
        assertEquals(0, page.total, "软删除后已发布书评列表不应包含该评论")
    }

    @Test
    fun `getMyBookReview 软删除后返回 null`() = withTestApplication {
        // 修复后：getMyBookReview 只返回已发布书评，软删除后返回 null，
        // 供前端正确判断「写书评/编辑/删除」入口。
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()
        commentService.createBookReview(userId, bookId, "评论内容")
        outboxService.processPending()

        commentService.deleteMyReview(userId, bookId)

        val review = commentService.getMyBookReview(userId, bookId)
        assertNull(review)
    }

    @Test
    fun `createBookReview 重复发布更新内容而非新增`() = withTestApplication {
        // 修复后：同一用户对同一本书重复发布应只保留一条已发布书评，内容更新为最新。
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()
        commentService.createBookReview(userId, bookId, "第一版")
        outboxService.processPending()
        commentService.createBookReview(userId, bookId, "第二版")
        outboxService.processPending()

        val page = commentService.getBookReviews(bookId, page = 1, size = 10, parentId = null)
        assertEquals(1, page.total, "同一用户对同一本书重复发布应只保留一条书评")
        assertEquals("第二版", page.items.single().content, "内容应更新为最新版本")
    }

    @Test
    fun `deleteLineComment 删除自己的行评论后不再可见`() = withTestApplication {
        // 修复后：deleteLineComment 正确更新 BookChapterCommentTable，并校验章节归属。
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)
        commentService.upsertLineComment(userId, bookId, chapterId, line = 1, content = "行评论")
        outboxService.processPending()
        val commentId = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterCommentTable.selectAll().single()[BookChapterCommentTable.id].value
        }

        commentService.deleteLineComment(userId, chapterId, commentId)

        val comments = commentService.getChapterComments(chapterId)
        assertTrue(comments.isEmpty(), "删除后该章行评论不应包含已删除评论")
    }

    @Test
    fun `listBookReviews 管理端过滤与内容读取`() = withTestApplication {
        val u1 = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val u2 = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook()
        commentService.createBookReview(u1, bookId, "评论一")
        commentService.createBookReview(u2, bookId, "评论二")
        outboxService.processPending()

        val all = commentService.listBookReviews(bookId = null, page = 1, size = 10, status = null, keyword = null)
        assertEquals(2, all.total)

        val byBook = commentService.listBookReviews(bookId = bookId, page = 1, size = 10, status = null, keyword = null)
        assertEquals(2, byBook.total)

        val byUser = commentService.listBookReviews(bookId = null, page = 1, size = 10, status = null, keyword = TestUsers.USER_NAME)
        assertEquals(1, byUser.total)
        assertEquals("评论一", byUser.items.single().content)
    }

    @Test
    fun `forceDeleteBookReview 软删除并登记墓碑`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val bookId = insertTestBook()
        commentService.createBookReview(userId, bookId, "将被强制删除")
        outboxService.processPending()
        val commentId = commentService.getMyBookReview(userId, bookId)!!.id

        commentService.forceDeleteBookReview(commentId)
        outboxService.processPending()

        val page = commentService.getBookReviews(bookId, page = 1, size = 10, parentId = null)
        assertEquals(0, page.total)
        assertNull(commentService.getMyBookReview(userId, bookId))
    }

    @Test
    fun `upsertLineComment 后 getChapterComments 按行分组返回`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val otherUser = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val bookId = insertTestBook()
        val chapterId = insertTestChapter(bookId)
        commentService.upsertLineComment(userId, bookId, chapterId, line = 5, content = "第五行评论")
        commentService.upsertLineComment(otherUser, bookId, chapterId, line = 5, content = "第五行另一评论")
        commentService.upsertLineComment(userId, bookId, chapterId, line = 8, content = "第八行评论")
        outboxService.processPending()

        val comments = commentService.getChapterComments(chapterId)
        assertEquals(2, comments[5]!!.size, "同一行多条评论应归为一组")
        assertEquals(1, comments[8]!!.size)
        assertEquals("第八行评论", comments[8]!!.single().content)
    }
}
