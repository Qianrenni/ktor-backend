package com.qianrenni.modules.book

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.storage.ChapterStoreFactory
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.infrastructure.outbox.enqueueFileSync
import com.qianrenni.common.BookStatus
import com.qianrenni.models.tables.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.Path

class BookService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val cache: CacheService,
    private val chapterStoreFactory: ChapterStoreFactory,
) {
    suspend fun getBookCount(): Long {
        return cache.get(
            keyPrefix = "book_service",
            args = listOf("book_count"),
            serializer = Long.serializer()
        ) {
            databaseManager.suspendedTransaction(readOnly = true) {
                BookTable.selectAll().count()
            }
        }
    }

    suspend fun getCategory(): List<String> {
        return cache.get(
            keyPrefix = "book_service",
            args = listOf("category"),
            serializer = ListSerializer(String.serializer())
        ) {
            databaseManager.suspendedTransaction(readOnly = true) {
                BookTable.select(BookTable.category).withDistinct(true).map { it[BookTable.category] }
            }
        }
    }

    suspend fun getRecommendBook(query: String): List<Book> {
        return cache.get(
            keyPrefix = "book_service",
            args = listOf("recommend_book", query),
            serializer = ListSerializer(Book.serializer())
        ) {
            databaseManager.suspendedTransaction(readOnly = true) {
                BookTable.selectAll()
                    .where { (BookTable.status eq BookStatus.PUBLISHED) and (BookTable.isActive eq true) }
                    .orderBy(Random()).limit(5)
                    .map { it.toBook(config.serverUrl) }
            }
        }
    }

    suspend fun getSearchBook(query: String): List<Book> {
        return cache.get(
            keyPrefix = "book_service",
            args = listOf("search_book", query),
            serializer = ListSerializer(Book.serializer())
        ) {
            databaseManager.suspendedTransaction(readOnly = true) {
                BookTable.selectAll()
                    .where { (BookTable.status eq BookStatus.PUBLISHED) and (BookTable.isActive eq true) and ((BookTable.name like "%$query%") or (BookTable.author like "%$query%")) }
                    .map { it.toBook(config.serverUrl) }
            }
        }
    }

    suspend fun getBookList(bookIds: List<Int>): List<Book> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll()
                .where { (BookTable.status eq BookStatus.PUBLISHED) and (BookTable.isActive eq true) and (BookTable.id inList bookIds) }
                .map { it.toBook(config.serverUrl) }
        }
    }

    suspend fun getBookCatalog(bookId: Int): List<BookCatalogItem> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable
                .selectAll()
                .where {
                    (BookChapterTable.status eq BookStatus.PUBLISHED) and (BookChapterTable.bookId eq bookId) and (BookChapterTable.isActive eq true)
                }
                .orderBy(BookChapterTable.order)
                .map { it.toBookCatalogItem() }
        }
    }

    suspend fun getBookChapter(chapterId: Int, bookId: Int): String {
        return cache.get(
            keyPrefix = "book_service",
            args = listOf("book_chapter", bookId.toString(), chapterId.toString()),
            serializer = String.serializer()
        ) {
            val result = databaseManager.suspendedTransaction(readOnly = true) {
                BookChapterTable
                    .selectAll()
                    .where {
                        (BookChapterTable.id eq chapterId) and (BookChapterTable.bookId eq bookId) and (BookChapterTable.status eq BookStatus.PUBLISHED) and (BookChapterTable.isActive eq true)
                    }.firstOrNull()
            }
            if (result == null) {
                throw IllegalArgumentException("书籍内容遍历攻击")
            }
            chapterStoreFactory.open("book", bookId.toString()).use {
                it.readChapter(chapterId)
            }
        }
    }

    suspend fun getBookSelect(category: String, offSet: Int, limit: Int): List<Book> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.selectAll()
                .where { (BookTable.category eq category) and (BookTable.status eq BookStatus.PUBLISHED) and (BookTable.isActive eq true) }
                .offset(start = offSet.toLong())
                .limit(count = limit).map { it.toBook(config.serverUrl) }
        }
    }

    /**
     * 获取书籍累计阅读量（chapter_read_statistics 中 pageViewCount 总和）
     */
    suspend fun getReadCount(bookId: Int): Int {
        return databaseManager.suspendedTransaction(readOnly = true) {
            val sumExpr = ChapterReadStatisticsTable.pageViewCount.sum()
            ChapterReadStatisticsTable
                .select(sumExpr)
                .where { ChapterReadStatisticsTable.bookId eq bookId }
                .firstOrNull()
                ?.let { it[sumExpr] }
                ?: 0
        }
    }

    /**
     * 获取书籍收藏量（shelf 表中记录数）
     */
    suspend fun getFavoriteCount(bookId: Int): Int {
        return databaseManager.suspendedTransaction(readOnly = true) {
            ShelfTable.selectAll()
                .where { ShelfTable.bookId eq bookId }
                .count()
                .toInt()
        }
    }

    /**
     * 定时发布：将 APPROVED 状态的书籍/章节发布为 PUBLISHED。
     * - 负 id 书籍为待合并的临时书籍（-id 为目标书籍）：合并元数据 + 封面 + 章节内容；
     * - 正 id 书籍直接置为 PUBLISHED。
     * 章节内容合并通过 Outbox（enqueueFileSync）在事务提交后异步执行文件操作。
     */
    suspend fun publishBook() {
        var books = emptyList<Book>()
        databaseManager.suspendedTransaction {
            books = BookTable
                .selectAll()
                .where { BookTable.status eq BookStatus.APPROVED }
                .map { it.toBook(config.serverUrl) }

            val (negativeBooks, positiveBooks) = books.partition { it.id < 0 }

            // 处理需要合并的书籍（id < 0 为临时书籍，-id 为目标书籍）
            negativeBooks.forEach { book ->
                BookTable.update({ BookTable.id eq (-book.id) }) {
                    it[BookTable.status] = BookStatus.PUBLISHED
                    it[BookTable.name] = book.name
                    it[BookTable.author] = book.author
                    it[BookTable.description] = book.description
                    it[BookTable.category] = book.category
                    it[BookTable.tags] = book.tags
                }
                AuthorBookTable.deleteWhere { AuthorBookTable.bookId eq book.id }
                AuditBookTable.deleteWhere { AuditBookTable.bookId eq book.id }
                BookTable.deleteWhere { BookTable.id eq book.id }
            }

            // 直接发布的书籍（id > 0）
            if (positiveBooks.isNotEmpty()) {
                BookTable.update({
                    BookTable.id inList positiveBooks.map { it.id }
                }) {
                    it[BookTable.status] = BookStatus.PUBLISHED
                }
            }

            // ---------- 章节处理 ----------
            val chapters = BookChapterTable
                .selectAll()
                .where { BookChapterTable.status eq BookStatus.APPROVED }
                .map { it.toBookCatalogItem() }

            val negativeChapters = chapters.filter { it.order < 0f }

            if (negativeChapters.isNotEmpty()) {
                // 查询对应的目标章节
                val chapterMap = BookChapterTable
                    .selectAll()
                    .where {
                        negativeChapters
                            .map { (BookChapterTable.bookId eq it.bookId) and (BookChapterTable.order eq (-it.order)) }
                            .reduce { acc, op -> acc or op }
                    }
                    .map { it.toBookCatalogItem() }
                    .associateBy { it.bookId to it.order }

                negativeChapters.forEach { chapter ->
                    val targetId = chapterMap[chapter.bookId to (-chapter.order)]?.id
                        ?: return@forEach // 找不到目标章节则跳过

                    // 读取源章节内容，并在同一事务内登记"更新目标 + 删除源"的文件操作，
                    // 由 Outbox worker 在事务提交后执行（避免文件操作在事务内导致回滚不一致）
                    val content = chapterStoreFactory.open("book", chapter.bookId.toString()).use { store ->
                        store.readChapter(chapter.id)
                    }
                    enqueueFileSync(
                        storeDir = "book",
                        storeName = chapter.bookId.toString(),
                        contentId = targetId,
                        op = OutboxOp.UPDATE,
                        content = content
                    )
                    enqueueFileSync(
                        storeDir = "book",
                        storeName = chapter.bookId.toString(),
                        contentId = chapter.id,
                        op = OutboxOp.DELETE
                    )
                }
                AuditBookChapterTable.deleteWhere {
                    AuditBookChapterTable.bookChapterId inList negativeChapters.map { it.id }
                }
                BookChapterTable.deleteWhere {
                    BookChapterTable.id inList negativeChapters.map { it.id }
                }
            }

            val positiveChapters = chapters.filter { it.order > 0f }
            if (positiveChapters.isNotEmpty()) {
                BookChapterTable.update({
                    BookChapterTable.id inList positiveChapters.map { it.id }
                }) {
                    it[BookChapterTable.status] = BookStatus.PUBLISHED
                }
            }
            val countChapter = BookChapterTable.id.count()
            val bookChapterCount = BookChapterTable
                .select(BookChapterTable.bookId, countChapter)
                .where { BookChapterTable.bookId inList chapters.map { it.bookId } }
                .groupBy(BookChapterTable.bookId)
                .associate { it[BookChapterTable.bookId] to it[countChapter].toInt() }
            bookChapterCount.forEach { (bookId, count) ->
                BookTable.update({ BookTable.id eq bookId }) {
                    it[BookTable.totalChapter] = count
                }
            }
        }

        // 文件操作（与事务无关，放在事务外执行）
        withContext(Dispatchers.IO) {
            books.filter { it.id < 0 }.forEach { book ->
                val srcDir = File(config.staticDir, "/book/${book.id}")
                val srcCover = File(srcDir, "cover.webp")
                if (srcCover.exists()) {
                    val targetDir = File(config.staticDir, "/book/${-book.id}")
                    targetDir.mkdirs()
                    srcCover.copyTo(
                        File(targetDir, "cover.webp"),
                        overwrite = true
                    )
                    srcDir.deleteRecursively()
                }
            }
        }
    }

    /**
     * 自动发布审核超时的书籍内容。
     * 当书籍处于审核中(REVIEWING)状态且 updatedAt 距今已超过 3 天未处理时，自动将其发布。
     */
    suspend fun publishReviewTimeoutBooks() {
        var books = emptyList<Book>()
        databaseManager.suspendedTransaction {
            // 审核中且超过 3 天未处理的书籍
            val deadline = LocalDateTime.now().minusDays(3)
            books = BookTable
                .selectAll()
                .where {
                    (BookTable.status eq BookStatus.REVIEWING) and
                            (BookTable.updatedAt less deadline)
                }
                .map { it.toBook(config.serverUrl) }

            if (books.isEmpty()) {
                return@suspendedTransaction
            }

            val (negativeBooks, positiveBooks) = books.partition { it.id < 0 }

            // 处理需要合并的书籍（id < 0 为临时书籍，-id 为目标书籍）
            negativeBooks.forEach { book ->
                BookTable.update({ BookTable.id eq (-book.id) }) {
                    it[BookTable.status] = BookStatus.PUBLISHED
                    it[BookTable.name] = book.name
                    it[BookTable.author] = book.author
                    it[BookTable.description] = book.description
                    it[BookTable.category] = book.category
                    it[BookTable.tags] = book.tags
                }
                AuthorBookTable.deleteWhere { AuthorBookTable.bookId eq book.id }
                AuditBookTable.deleteWhere { AuditBookTable.bookId eq book.id }
                BookTable.deleteWhere { BookTable.id eq book.id }
            }

            // 直接发布的书籍（id > 0）
            if (positiveBooks.isNotEmpty()) {
                BookTable.update({
                    BookTable.id inList positiveBooks.map { it.id }
                }) {
                    it[BookTable.status] = BookStatus.PUBLISHED
                }
            }

            // ---------- 章节处理 ----------
            val chapters = BookChapterTable
                .selectAll()
                .where {
                    (BookChapterTable.status eq BookStatus.REVIEWING) and
                            (BookChapterTable.bookId inList books.map { it.id })
                }
                .map { it.toBookCatalogItem() }

            val negativeChapters = chapters.filter { it.order < 0f }

            if (negativeChapters.isNotEmpty()) {
                // 查询对应的目标章节
                val chapterMap = BookChapterTable
                    .selectAll()
                    .where {
                        negativeChapters
                            .map { (BookChapterTable.bookId eq it.bookId) and (BookChapterTable.order eq (-it.order)) }
                            .reduce { acc, op -> acc or op }
                    }
                    .map { it.toBookCatalogItem() }
                    .associateBy { it.bookId to it.order }

                negativeChapters.forEach { chapter ->
                    val targetId = chapterMap[chapter.bookId to (-chapter.order)]?.id
                        ?: return@forEach // 找不到目标章节则跳过

                    // 读取源章节内容，并在同一事务内登记"更新目标 + 删除源"的文件操作，
                    // 由 Outbox worker 在事务提交后执行（避免文件操作在事务内导致回滚不一致）
                    val content = chapterStoreFactory.open("book", chapter.bookId.toString()).use { store ->
                        store.readChapter(chapter.id)
                    }
                    enqueueFileSync(
                        storeDir = "book",
                        storeName = chapter.bookId.toString(),
                        contentId = targetId,
                        op = OutboxOp.UPDATE,
                        content = content
                    )
                    enqueueFileSync(
                        storeDir = "book",
                        storeName = chapter.bookId.toString(),
                        contentId = chapter.id,
                        op = OutboxOp.DELETE
                    )
                }
                AuditBookChapterTable.deleteWhere {
                    AuditBookChapterTable.bookChapterId inList negativeChapters.map { it.id }
                }
                BookChapterTable.deleteWhere {
                    BookChapterTable.id inList negativeChapters.map { it.id }
                }
            }

            val positiveChapters = chapters.filter { it.order > 0f }
            if (positiveChapters.isNotEmpty()) {
                BookChapterTable.update({
                    BookChapterTable.id inList positiveChapters.map { it.id }
                }) {
                    it[BookChapterTable.status] = BookStatus.PUBLISHED
                }
            }
            val countChapter = BookChapterTable.id.count()
            val bookChapterCount = BookChapterTable
                .select(BookChapterTable.bookId, countChapter)
                .where { BookChapterTable.bookId inList chapters.map { it.bookId } }
                .groupBy(BookChapterTable.bookId)
                .associate { it[BookChapterTable.bookId] to it[countChapter].toInt() }
            bookChapterCount.forEach { (bookId, count) ->
                BookTable.update({ BookTable.id eq bookId }) {
                    it[BookTable.totalChapter] = count
                }
            }
        }

        // 文件操作（与事务无关，放在事务外执行）
        withContext(Dispatchers.IO) {
            books.filter { it.id < 0 }.forEach { book ->
                val srcDir = File(config.staticDir, "/book/${book.id}")
                val srcCover = File(srcDir, "cover.webp")
                if (srcCover.exists()) {
                    val targetDir = File(config.staticDir, "/book/${-book.id}")
                    targetDir.mkdirs()
                    srcCover.copyTo(
                        File(targetDir, "cover.webp"),
                        overwrite = true
                    )
                    srcDir.deleteRecursively()
                }
            }
        }
    }
}
