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
     * 创建/更新书评（UPSERT：每用户每书仅保留一条已发布书评）。
     * 已存在已发布书评则复用其 contentId 更新内容（Outbox UPDATE 追加并覆盖索引，
     * 读取端始终拿到最新内容）；否则插入新书评。
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
            val existing = BookCommentTable.selectAll()
                .where {
                    (BookCommentTable.bookId eq bookId) and
                            (BookCommentTable.userId eq userId) and
                            (BookCommentTable.status eq BookStatus.PUBLISHED)
                }
                .orderBy(BookCommentTable.updatedAt, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
            if (existing != null) {
                // 已有已发布书评：更新内容（复用同一 contentId）
                outboxService.write(
                    storeDir = "comment/book",
                    storeName = bookId.toString(),
                    contentId = existing[BookCommentTable.id].value,
                    op = OutboxOp.UPDATE,
                    content = content
                )
            } else {
                // 首次发布：插入新书评，同一事务内登记内容写入，由 Outbox worker 异步执行
                val id = BookCommentTable.insertAndGetId {
                    it[BookCommentTable.bookId] = bookId
                    it[BookCommentTable.userId] = userId
                    it[BookCommentTable.status] = BookStatus.PUBLISHED
                }.value
                outboxService.write(
                    storeDir = "comment/book",
                    storeName = bookId.toString(),
                    contentId = id,
                    op = OutboxOp.UPDATE,
                    content = content
                )
            }
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
     * 获取当前用户已发布的书评（软删除后返回 null，供前端判断「写书评/编辑/删除」入口）
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
                            (BookCommentTable.userId eq userId) and
                            (BookCommentTable.status eq BookStatus.PUBLISHED)
                }
                .orderBy(BookCommentTable.updatedAt, SortOrder.DESC)
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
            // 安全加固（L9）：真正的 UPSERT —— 同章节同行号同用户仅保留一条已发布评论，复用其 contentId
            val existing = BookChapterCommentTable
                .selectAll()
                .where {
                    (BookChapterCommentTable.chapterId eq chapterId) and
                            (BookChapterCommentTable.line eq line) and
                            (BookChapterCommentTable.userId eq userId) and
                            (BookChapterCommentTable.status eq BookStatus.PUBLISHED)
                }
                .orderBy(BookChapterCommentTable.id, SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            val id = if (existing != null) {
                existing[BookChapterCommentTable.id].value
            } else {
                BookChapterCommentTable.insertAndGetId {
                    it[BookChapterCommentTable.chapterId] = chapterId
                    it[BookChapterCommentTable.line] = line
                    it[BookChapterCommentTable.userId] = userId
                    it[BookChapterCommentTable.status] = BookStatus.PUBLISHED
                }.value
            }
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
     * 删除章节行评论（仅能删除自己的评论，并校验评论属于指定章节）
     */
    suspend fun deleteLineComment(userId: Int, chapterId: Int, commentId: Int) {
        databaseManager.suspendedTransaction {
            val updated = BookChapterCommentTable.update({
                (BookChapterCommentTable.id eq commentId) and
                        (BookChapterCommentTable.chapterId eq chapterId) and
                        (BookChapterCommentTable.userId eq userId)
            }) {
                it[BookChapterCommentTable.status] = BookStatus.DELETED
            }
            require(updated > 0) { "评论不存在或无权删除" }
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

    /**
     * 管理端/作者端分页查询书评（含各状态，可组合过滤）。
     * 归属/权限校验由调用方（Controller）完成。
     *
     * @param bookId  书籍过滤（null 表示全部书籍）
     * @param status  状态过滤（null 表示全部状态）
     * @param keyword 用户名关键字模糊匹配（null 表示不过滤）
     */
    suspend fun listBookReviews(
        bookId: Int?,
        page: Int,
        size: Int,
        status: BookStatus?,
        keyword: String?,
    ): PageResult<BookComment> {
        val offset = (page - 1) * size
        return databaseManager.suspendedTransaction(readOnly = true) {
            val base = BookCommentTable
                .innerJoin(UserTable, { BookCommentTable.userId }, { UserTable.id })
            val selectedColumns = listOf(
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
            val whereClause: SqlExpressionBuilder.() -> Op<Boolean> = {
                (bookId?.let { BookCommentTable.bookId eq it } ?: Op.TRUE) and
                        (status?.let { BookCommentTable.status eq it } ?: Op.TRUE) and
                        (keyword?.takeIf { it.isNotBlank() }?.let { UserTable.userName like "%$it%" } ?: Op.TRUE)
            }
            val total = base.selectAll().where(whereClause).count()
            val rows = base
                .select(selectedColumns)
                .where(whereClause)
                .orderBy(BookCommentTable.createdAt, SortOrder.DESC)
                .offset(offset.toLong())
                .limit(size)
                .map { row ->
                    row.toBookComment(
                        row[UserTable.userName],
                        userService.getUserAvatar(row[BookCommentTable.userId]),
                        readCommentContent(
                            "comment/book",
                            row[BookCommentTable.bookId].toString(),
                            row[BookCommentTable.id].value
                        )
                    )
                }
            PageResult(total = total.toInt(), items = rows, page = page, size = size)
        }
    }

    /**
     * 管理端审核书评状态（PUBLISHED/REVIEWING/REJECTED/DELETED）。
     * @return 评论是否存在
     */
    suspend fun auditBookReview(commentId: Int, status: BookStatus): Boolean =
        databaseManager.suspendedTransaction {
            BookCommentTable.update({ BookCommentTable.id eq commentId }) {
                it[BookCommentTable.status] = status
            } > 0
        }

    /**
     * 管理端强制删除书评（软删除 + 同事务登记内容墓碑，清理文件内容）
     */
    suspend fun forceDeleteBookReview(commentId: Int) {
        databaseManager.suspendedTransaction {
            val row = BookCommentTable.selectAll()
                .where { BookCommentTable.id eq commentId }
                .firstOrNull() ?: throw IllegalArgumentException("评论不存在")
            val bookId = row[BookCommentTable.bookId]
            BookCommentTable.update({ BookCommentTable.id eq commentId }) {
                it[BookCommentTable.status] = BookStatus.DELETED
            }
            // 同事务登记内容删除，由 Outbox worker 落墓碑
            outboxService.write(
                storeDir = "comment/book",
                storeName = bookId.toString(),
                contentId = commentId,
                op = OutboxOp.DELETE
            )
        }
    }

    /**
     * 安全读取评论内容：内容尚未写入/已删除时返回空串，
     * 避免列表接口因单个脏数据整体失败。
     */
    private suspend fun readCommentContent(storeDir: String, storeName: String, contentId: Int): String =
        chapterStoreFactory.open(storeDir, storeName).use { store ->
            try {
                store.readChapter(contentId)
            } catch (e: IllegalStateException) {
                ""
            }
        }
}

