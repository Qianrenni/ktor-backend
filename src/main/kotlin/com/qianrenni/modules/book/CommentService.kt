package com.qianrenni.modules.book

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.infrastructure.storage.ChapterStoreFactory
import com.qianrenni.modules.user.UserService
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.models.tables.*
import com.qianrenni.common.PageResult
import org.jetbrains.exposed.sql.*

class CommentService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val outboxService: OutboxService,
    private val userService: UserService,
    private val chapterStoreFactory: ChapterStoreFactory,
) {


    /**
     * 创建/更新书评（UPSERT：每用户每书一条）
     */
    suspend fun createBookReview(userId: Int, bookId: Int, content: String) {
        require(content.isNotBlank()) { "评论内容不能为空" }
        require(content.length <= 300) { "评论内容不能超过300字" }

        // 检查书籍是否存在
        databaseManager.suspendedTransaction(readOnly = true) {
            val bookExists = BookTable.selectAll().where { BookTable.id eq bookId }
                .firstOrNull()
            require(bookExists != null) { "书籍不存在" }
        }
        databaseManager.suspendedTransaction {
            // 插入新书评
            val id = BookCommentTable.insertAndGetId {
                it[BookCommentTable.bookId] = bookId
                it[BookCommentTable.userId] = userId
                it[BookCommentTable.status] = BookStatus.PUBLISHED
            }.value
            // 同一事务内登记评论内容写入，由 Outbox worker 异步执行
            outboxService.write(
                storeDir = "comment/book",
                storeName = bookId.toString(),
                contentId = id,
                op = OutboxOp.UPDATE,
                content = content
            )
        }
    }

    /**
     * 分页获取书评
     */
    suspend fun getBookReviews(bookId: Int, page: Int, size: Int, parentId: Int?): PageResult<BookComment> {
        val offset = (page - 1) * size
        return databaseManager.suspendedTransaction(readOnly = true) {
            val total = BookCommentTable.selectAll().where {
                (BookCommentTable.bookId eq bookId) and
                        (BookCommentTable.status eq BookStatus.PUBLISHED)
            }.also { query ->
                parentId?.let {
                    query.andWhere { BookCommentTable.parentId eq it }
                }
            }.count()
            val contentStoreService = chapterStoreFactory.open("comment/book", bookId.toString())
            val rows = BookCommentTable
                .innerJoin(UserTable, { BookCommentTable.userId }, { UserTable.id })
                .select(
                    BookCommentTable.id,
                    BookCommentTable.bookId,
                    BookCommentTable.userId,
                    UserTable.userName,
                    UserTable.avatar,
                    BookCommentTable.status,
                    BookCommentTable.createdAt,
                    BookCommentTable.updatedAt,
                    BookCommentTable.parentId
                )
                .where {
                    (BookCommentTable.bookId eq bookId) and
                            (BookCommentTable.status eq BookStatus.PUBLISHED)
                }
                .orderBy(BookCommentTable.createdAt, SortOrder.DESC)
                .offset(offset.toLong())
                .limit(size)
                .map {
                    val content = contentStoreService.readChapter(it[BookCommentTable.id].value)
                    it.toBookComment(
                        it[UserTable.userName],
                        userService.getUserAvatar(it[BookCommentTable.userId]),
                        content
                    )
                }
            contentStoreService.close()
            PageResult(total = total.toInt(), items = rows, page = page, size = size)
        }
    }

    /**
     * 获取当前用户的书评
     */
    suspend fun getMyBookReview(userId: Int, bookId: Int): BookComment? {
        val contentStore = chapterStoreFactory.open("comment/book", bookId.toString())
        val result = databaseManager.suspendedTransaction(readOnly = true) {
            BookCommentTable
                .innerJoin(UserTable, { BookCommentTable.userId }, { UserTable.id })
                .select(
                    BookCommentTable.id,
                    BookCommentTable.bookId,
                    BookCommentTable.userId,
                    UserTable.userName,
                    UserTable.avatar,
                    BookCommentTable.status,
                    BookCommentTable.createdAt,
                    BookCommentTable.updatedAt,
                    BookCommentTable.parentId
                )
                .where {
                    (BookCommentTable.bookId eq bookId) and
                            (BookCommentTable.userId eq userId)
                }
                .map {
                    val content = contentStore.readChapter(it[BookCommentTable.id].value)
                    it.toBookComment(
                        it[UserTable.userName],
                        userService.getUserAvatar(it[BookCommentTable.userId]),
                        content
                    )
                }
        }
        return result.firstOrNull()
    }

    /**
     * 删除自己的书评（软删除）
     */
    suspend fun deleteMyReview(userId: Int, bookId: Int) {
        databaseManager.suspendedTransaction {
            BookCommentTable.update({
                (BookCommentTable.bookId eq bookId) and
                        (BookCommentTable.userId eq userId)
            }) {
                it[BookCommentTable.status] = BookStatus.DELETED
            }
        }
    }

    /**
     * 创建/更新章节行评论（UPSERT，基于 line 唯一）
     */
    suspend fun upsertLineComment(userId: Int, bookId: Int, chapterId: Int, line: Int, content: String) {
        require(content.isNotBlank()) { "评论内容不能为空" }
        require(content.length <= 2000) { "评论内容不能超过2000字" }
        require(line >= 0) { "非法的行号" }

        databaseManager.suspendedTransaction {
            val id = BookChapterCommentTable.insertAndGetId {
                it[BookChapterCommentTable.chapterId] = chapterId
                it[BookChapterCommentTable.line] = line
                it[BookChapterCommentTable.userId] = userId
                it[BookChapterCommentTable.status] = BookStatus.PUBLISHED
            }.value
            // 同一事务内登记评论内容写入，由 Outbox worker 异步执行
            // storeName 使用 chapterId，与读取路径 getChapterComments 保持一致
            outboxService.write(
                storeDir = "comment/chapter",
                storeName = chapterId.toString(),
                contentId = id,
                op = OutboxOp.UPDATE,
                content = content
            )
        }
    }

    /**
     * 删除章节行评论
     */
    suspend fun deleteLineComment(id: Int) {
        databaseManager.suspendedTransaction {
            BookCommentTable.update({
                BookChapterCommentTable.id eq id
            }) {
                it[BookChapterCommentTable.status] = BookStatus.DELETED
            }
        }
    }

    /**
     * 获取某章所有有评论的行 Map<line, BookComment>
     */
    suspend fun getChapterComments(chapterId: Int): Map<Int, List<BookChapterComment>> {
        val store = chapterStoreFactory.open("comment/chapter", chapterId.toString())
        val rows = databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterCommentTable
                .innerJoin(UserTable, { BookChapterCommentTable.userId }, { UserTable.id })
                .select(
                    BookChapterCommentTable.id,
                    BookChapterCommentTable.chapterId,
                    BookChapterCommentTable.userId,
                    UserTable.userName,
                    BookChapterCommentTable.status,
                    BookChapterCommentTable.createdAt,
                    BookChapterCommentTable.updatedAt,
                    BookChapterCommentTable.parentId,
                    BookChapterCommentTable.line
                )
                .where {
                    (BookChapterCommentTable.chapterId eq chapterId) and
                            (BookChapterCommentTable.status eq BookStatus.PUBLISHED)
                }
                .map {
                    val content = store.readChapter(it[BookChapterCommentTable.id].value)
                    it.toBookChapterComment(
                        userAvatar = userService.getUserAvatar(it[BookChapterCommentTable.userId]),
                        userName = it[UserTable.userName],
                        content = content
                    )

                }
        }
        store.close()
        return rows.groupBy { it.line }
    }
}

