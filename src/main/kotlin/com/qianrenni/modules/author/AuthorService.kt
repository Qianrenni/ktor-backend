package com.qianrenni.modules.author

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.infrastructure.storage.ChapterStoreFactory
import com.qianrenni.modules.admin.AuditService
import com.qianrenni.modules.user.UserService
import com.qianrenni.modules.author.RequestUpdateBookChapter
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.common.util.ImageValidator
import com.qianrenni.models.tables.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.File
import kotlin.io.path.Path

class AuthorService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val outboxService: OutboxService,
    private val auditService: AuditService,
    private val userService: UserService,
    private val emailService: EmailService,
    private val chapterStoreFactory: ChapterStoreFactory,
    private val cache: CacheService,
) {
    suspend fun checkAuthor(userId: Int, bookId: Int) {
        databaseManager.suspendedTransaction(readOnly = true) {
            require(
                AuthorBookTable.selectAll().where {
                    (AuthorBookTable.userId eq userId) and (AuthorBookTable.bookId eq bookId)
                }.count() > 0
            )
        }
    }

    suspend fun getAuthorCount(): Int {
        return databaseManager.suspendedTransaction(readOnly = true) {
            AuthorTable.selectAll().count().toInt()
        }
    }

    suspend fun getBook(userId: Int, bookIds: List<Int>): List<Book> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookTable
                .innerJoin(AuthorBookTable, { BookTable.id }, { AuthorBookTable.bookId })
                .selectAll()
                .where {
                    if (bookIds.isEmpty()) {
                        AuthorBookTable.userId eq userId
                    } else {
                        (AuthorBookTable.userId eq userId) and (AuthorBookTable.bookId inList bookIds)
                    }
                }
                .map { it.toBook(config.serverUrl) }
        }
    }

    suspend fun createBook(
        userId: Int,
        bookName: String,
        author: String,
        tags: String,
        description: String,
        category: String,
        coverFile: File
    ) {
        var bookId: EntityID<Int>? = null
        databaseManager.suspendedTransaction {
            bookId = BookTable.insertAndGetId {
                it[BookTable.name] = bookName
                it[BookTable.author] = author
                it[BookTable.tags] = tags
                it[BookTable.description] = description
                it[BookTable.category] = category
                it[BookTable.status] = BookStatus.PENDING
            }
            AuthorBookTable.insert {
                it[AuthorBookTable.userId] = userId
                it[AuthorBookTable.bookId] = bookId.value
            }
        }
        bookId?.let {
            // 安全加固（M6）：校验封面魔数
            ImageValidator.requireImage(coverFile)
            withContext(Dispatchers.IO) {
                // 目标目录可能不存在（新书 id），与 AdminService 一致先建目录再复制
                val coverDir = Path(config.staticDir + "/book/${it.value}")
                coverDir.toFile().mkdirs()
                coverFile.copyTo(
                    coverDir.resolve("cover.webp").toFile(),
                    overwrite = false
                )
                coverFile.deleteOnExit()
            }
        }

    }

    suspend fun updateBook(
        userId: Int,
        bookId: Int,
        bookName: String,
        author: String,
        tags: String,
        description: String,
        category: String,
        coverFile: File?
    ) {
        checkAuthor(userId, bookId)
        var targetId: Int? = null
        databaseManager.suspendedTransaction {
            val alreadyBook = BookTable.selectAll().where { BookTable.id eq bookId }.firstOrNull()
                ?.toBook(config.serverUrl)
            when (alreadyBook) {
                null -> {}
                else -> {
                    when (alreadyBook.status) {
                        BookStatus.PENDING, BookStatus.REJECTED -> {
                            BookTable.update({ BookTable.id eq alreadyBook.id }) {
                                it[BookTable.name] = bookName
                                it[BookTable.author] = author
                                it[BookTable.tags] = tags
                                it[BookTable.description] = description
                                it[BookTable.category] = category
                                it[BookTable.status] = BookStatus.PENDING
                            }
                            targetId = alreadyBook.id
                        }

                        BookStatus.PUBLISHED -> {
                            targetId = BookTable.insertAndGetId {
                                it[BookTable.id] = (-bookId)
                                it[BookTable.name] = bookName
                                it[BookTable.author] = author
                                it[BookTable.tags] = tags
                                it[BookTable.wordsCount] = alreadyBook.wordsCount
                                it[BookTable.totalChapter] = alreadyBook.totalChapter
                                it[BookTable.description] = description
                                it[BookTable.category] = category
                                it[BookTable.status] = BookStatus.PENDING
                            }.value
                            AuthorBookTable.insert {
                                it[AuthorBookTable.userId] = userId
                                it[AuthorBookTable.bookId] = targetId
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            when (coverFile) {
                null -> {
                    if (targetId != bookId) {
                        val srcCover = File(config.staticDir + "/book/${bookId}/cover.webp")
                        if (srcCover.exists()) {
                            // 目标目录可能不存在，先建目录再复制
                            val targetDir = Path(config.staticDir + "/book/${targetId}")
                            targetDir.toFile().mkdirs()
                            srcCover.copyTo(
                                targetDir.resolve("cover.webp").toFile(),
                                overwrite = true
                            )
                        }
                    }
                }

                else -> {
                    // 安全加固（M6）：校验封面魔数
                    ImageValidator.requireImage(coverFile)
                    val coverDir = Path(config.staticDir + "/book/${targetId}")
                    coverDir.toFile().mkdirs()
                    coverFile.copyTo(
                        coverDir.resolve("cover.webp").toFile(),
                        overwrite = true
                    )
                    coverFile.deleteOnExit()
                }
            }

        }
        cache.invalidate("book_service")
    }

    suspend fun deleteBook(
        userId: Int,
        bookId: Int
    ) {
        checkAuthor(userId, bookId)
        var deleteCount = 0
        databaseManager.suspendedTransaction {
            val book = BookTable
                .innerJoin(AuthorBookTable, { BookTable.id }, { AuthorBookTable.bookId })
                .selectAll()
                .where { (BookTable.id eq bookId) and (AuthorBookTable.userId eq userId) and (BookTable.status eq BookStatus.PENDING) }
                .firstOrNull()
                ?.toBook(config.serverUrl)
            book?.let {
                // 同一事务内登记各章节内容文件删除，并删除章节记录
                val chapterIds = BookChapterTable
                    .select(BookChapterTable.id)
                    .where { BookChapterTable.bookId eq book.id }
                    .map { it[BookChapterTable.id].value }
                chapterIds.forEach { chapterId ->
                    outboxService.write("book", book.id.toString(), chapterId, OutboxOp.DELETE)
                }
                BookChapterTable.deleteWhere { BookChapterTable.bookId eq book.id }
                AuditBookTable.deleteWhere {
                    AuditBookTable.bookId eq book.id
                }
                AuthorBookTable.deleteWhere {
                    (AuthorBookTable.userId eq userId) and (AuthorBookTable.bookId eq book.id)
                }
                deleteCount = BookTable.deleteWhere {
                    BookTable.id eq book.id
                }
            }
        }
        if (deleteCount > 0) {
            val bookDir = Path(config.staticDir + "/book/${bookId}")
            bookDir.toFile().deleteRecursively()
        }
        cache.invalidate("book_service")
    }
    suspend fun getBookChapter(userId: Int, bookId: Int, chapterId: List<Int>?): List<BookCatalogItem> {
        checkAuthor(userId, bookId)
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable.selectAll().where {
                if (chapterId == null) {
                    (BookChapterTable.bookId eq bookId)
                } else {
                    (BookChapterTable.bookId eq bookId) and (BookChapterTable.id inList chapterId)
                }
            }.map {
                it.toBookCatalogItem()
            }
        }
    }

    suspend fun updateBookChapter(requestUpdateBookChapter: RequestUpdateBookChapter, userId: Int) {
        checkAuthor(userId, requestUpdateBookChapter.bookId)
        var targetId: Int? = null
        databaseManager.suspendedTransaction {
            val bookCatalogItem = BookChapterTable.selectAll().where {
                (BookChapterTable.bookId eq requestUpdateBookChapter.bookId) and (BookChapterTable.order eq requestUpdateBookChapter.order)
            }.firstOrNull()?.toBookCatalogItem()
            when (bookCatalogItem) {
                null -> {
                    targetId = BookChapterTable.insertAndGetId {
                        it[BookChapterTable.bookId] = requestUpdateBookChapter.bookId
                        it[BookChapterTable.title] = requestUpdateBookChapter.title
                        it[BookChapterTable.order] = requestUpdateBookChapter.order
                        it[BookChapterTable.wordCount] = requestUpdateBookChapter.content.length
                    }.value
                }

                else -> {
                    BookChapterTable.update({
                        (BookChapterTable.bookId eq requestUpdateBookChapter.bookId) and (BookChapterTable.order eq requestUpdateBookChapter.order)
                    }) {
                        it[BookChapterTable.title] = requestUpdateBookChapter.title
                        it[BookChapterTable.wordCount] = requestUpdateBookChapter.content.length
                    }
                    targetId = bookCatalogItem.id
                }
            }
            // 同一事务内登记内容文件写入，由 Outbox worker 异步执行
            targetId.let {
                outboxService.write(
                    storeDir = "book",
                    storeName = requestUpdateBookChapter.bookId.toString(),
                    contentId = it,
                    op = OutboxOp.UPDATE,
                    content = requestUpdateBookChapter.content
                )
            }
        }
        cache.invalidate("book_service")
    }
    suspend fun deleteBookChapter(
        userId: Int,
        bookId: Int,
        chapterId: Int
    ) {
        checkAuthor(userId, bookId)
        var deleteCount = 0
        databaseManager.suspendedTransaction {
            deleteCount = BookChapterTable.deleteWhere {
                (BookChapterTable.bookId eq bookId) and (BookChapterTable.id eq chapterId) and (BookChapterTable.status eq BookStatus.PENDING)
            }
            // 同一事务内登记内容文件删除，由 Outbox worker 异步执行
            if (deleteCount > 0) {
                outboxService.write("book", bookId.toString(), chapterId, OutboxOp.DELETE)
            }
        }
        cache.invalidate("book_service")
    }
    suspend fun getChapterContent(
        userId: Int,
        bookId: Int,
        chapterIds: List<Int>
    ): List<String> {
        checkAuthor(userId, bookId)
        return chapterStoreFactory.open("book", bookId.toString()).use { store ->
            chapterIds.map { store.readChapter(it) }
        }
    }
    suspend fun getBookReadStatistic(userId: Int, bookId: Int): List<ChapterReadStatistics> {
        checkAuthor(userId, bookId)
        return databaseManager.suspendedTransaction(readOnly = true) {
            ChapterReadStatisticsTable.selectAll().where {
                ChapterReadStatisticsTable.bookId eq bookId
            }.map {
                it.toChapterReadStatistics()
            }
        }
    }
    suspend fun getDraftChapter(
        userId: Int,
    ): List<BookCatalogItem> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable
                .innerJoin(AuthorBookTable, { BookChapterTable.bookId }, { AuthorBookTable.bookId })
                .selectAll()
                .where {
                    (AuthorBookTable.userId eq userId) and (BookChapterTable.status neq BookStatus.PUBLISHED)
                }.map {
                    it.toBookCatalogItem()
                }
        }
    }
    suspend fun updateStatusChapter(
        userId: Int,
        bookId: Int,
        chapterId: Int
    ) {
        checkAuthor(userId, bookId)
        var targetId: Int? = null
        var updateCount = 0
        databaseManager.suspendedTransaction {
            updateCount = BookChapterTable.update({
                (BookChapterTable.bookId eq bookId) and (BookChapterTable.id eq chapterId) and (BookChapterTable.status eq BookStatus.PENDING)
            }) {
                it[BookChapterTable.status] = BookStatus.REVIEWING
            }
            val chapterAuditUserId = auditService.checkAuditChapter(chapterId)
            val bookAuditUserId = auditService.checkAuditBook(bookId)
            when (chapterAuditUserId) {
                null -> {
                    when (bookAuditUserId) {
                        null -> {
                            val auditorId = auditService.getAbsentAuditor()
                            AuditBookChapterTable
                                .insert {
                                    it[AuditBookChapterTable.bookChapterId] = chapterId
                                    it[AuditBookChapterTable.userId] = auditorId
                                }
                            AuditBookTable
                                .insert {
                                    it[AuditBookTable.bookId] = bookId
                                    it[AuditBookTable.userId] = auditorId
                                }
                            targetId = auditorId
                        }

                        else -> {
                            AuditBookChapterTable
                                .insert {
                                    it[AuditBookChapterTable.bookChapterId] = chapterId
                                    it[AuditBookChapterTable.userId] = bookAuditUserId
                                }
                            targetId = bookAuditUserId
                        }
                    }
                }

                else -> {
                    targetId = chapterAuditUserId
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            targetId?.let {
                if (updateCount > 0) {
                    val email = userService.getUserById(it).email
                    emailService.sendEmail(
                        listOf(email),
                        "新增书籍章节审核通知",
                        "审核员,有新的章节需要审核,请及时处理"
                    )
                }
            }
        }
    }

    suspend fun updateStatusBook(
        userId: Int,
        bookId: Int
    ) {
        checkAuthor(userId, bookId)
        var targetId: Int? = null
        var updateCount = 0
        databaseManager.suspendedTransaction {
            updateCount = BookTable.update({
                (BookTable.id eq bookId) and (BookTable.status eq BookStatus.PENDING)
            }) {
                it[BookTable.status] = BookStatus.REVIEWING
            }
            val bookAuditUserId = auditService.checkAuditBook(bookId)
            when (bookAuditUserId) {
                null -> {
                    val auditorId = auditService.getAbsentAuditor()
                    AuditBookTable
                        .insert {
                            it[AuditBookTable.bookId] = bookId
                            it[AuditBookTable.userId] = auditorId
                        }
                    targetId = auditorId
                }

                else -> {
                    targetId = bookAuditUserId
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            targetId?.let {
                if (updateCount > 0) {
                    val email = userService.getUserById(it).email
                    emailService.sendEmail(
                        listOf(email),
                        "新增书籍审核通知",
                        "审核员,有新的书籍需要审核,请及时处理"
                    )
                }
            }
        }
    }
}
