package com.qianrenni.modules.admin

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.storage.ChapterStoreFactory
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.common.RoleEnum
import com.qianrenni.models.tables.*
import org.jetbrains.exposed.sql.*


class AuditService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val chapterStoreFactory: ChapterStoreFactory,
) {
    suspend fun checkAuditor(userId: Int, bookId: Int) {
        return databaseManager.suspendedTransaction(readOnly = true) {
            require(
                AuditBookTable
                    .selectAll()
                    .where { (AuditBookTable.bookId eq bookId) and (AuditBookTable.userId eq userId) }
                    .count() > 0
            )
        }
    }
    suspend fun getAuditorCount(): Int {
        return databaseManager.suspendedTransaction(readOnly = true) {
            UserRoleTable
                .innerJoin(RoleTable, { UserRoleTable.roleId }, { RoleTable.id })
                .selectAll()
                .where { RoleTable.code eq RoleEnum.REVIEWER.name }
                .count()
                .toInt()
        }
    }

    suspend fun checkAuditBook(bookId: Int): Int? {
        return databaseManager.suspendedTransaction(readOnly = true) {
            AuditBookTable
                .selectAll()
                .where { AuditBookTable.bookId eq bookId }
                .firstOrNull()
                ?.toAuditBook()
                ?.userId
        }

    }

    suspend fun checkAuditChapter(chapterId: Int): Int? {
        return databaseManager.suspendedTransaction(readOnly = true) {
            AuditBookChapterTable
                .selectAll()
                .where { AuditBookChapterTable.bookChapterId eq chapterId }
                .firstOrNull()
                ?.toAuditBookChapter()
                ?.userId
        }
    }

    suspend fun getAbsentAuditor(): Int {
        return databaseManager.suspendedTransaction(readOnly = true) {
            UserRoleTable
                .innerJoin(RoleTable, { UserRoleTable.roleId }, { RoleTable.id })
                .selectAll()
                .where { RoleTable.code eq RoleEnum.REVIEWER.name }
                .orderBy(Random())
                .limit(1)
                .firstOrNull()
                ?.toUserRole()
                ?.userId ?: throw IllegalStateException("No auditor found")
        }
    }
    suspend fun getAuditBooks(userId: Int, bookIds: List<Int>): List<Book> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookTable.innerJoin(AuditBookTable, { BookTable.id }, { AuditBookTable.bookId })
                .selectAll()
                .where {
                    if (bookIds.isEmpty()) (AuditBookTable.userId eq userId)
                    else ((AuditBookTable.userId eq userId) and (AuditBookTable.bookId inList bookIds))
                }
                .map { it.toBook(config.serverUrl) }
        }
    }

    suspend fun getAuditChapters(userId: Int): List<BookCatalogItem> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable
                .innerJoin(AuditBookChapterTable, { BookChapterTable.id }, { AuditBookChapterTable.bookChapterId })
                .selectAll()
                .where { AuditBookChapterTable.userId eq userId }
                .map { it.toBookCatalogItem() }
        }
    }

    suspend fun getAuditChaptersByOrder(
        userId: Int,
        bookId: Int,
        orders: List<Float>
    ): List<BookCatalogItem> {
        checkAuditor(userId, bookId)
        return databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable
                .selectAll()
                .where { (BookChapterTable.bookId eq bookId) and (BookChapterTable.order inList orders) }
                .map { it.toBookCatalogItem() }
                .sortedBy { it.order }
        }
    }

    suspend fun getAuditContentChapter(
        userId: Int,
        bookId: Int,
        orders: List<Float>
    ): List<String> {
        checkAuditor(userId, bookId)
        var chapterIds = emptyList<Int>()
        databaseManager.suspendedTransaction(readOnly = true) {
            BookChapterTable
                .selectAll()
                .where { (BookChapterTable.bookId eq bookId) and (BookChapterTable.order inList orders) }
                .map { it.toBookCatalogItem() }
                .sortedBy { it.order }
                .map { it.id }
                .let {
                    chapterIds = it
                }
        }
        return chapterStoreFactory.open("book", bookId.toString()).use { store ->
            chapterIds.map { store.readChapter(it) }
        }
    }

    suspend fun updateBookChapter(
        userId: Int,
        bookId: Int,
        chapterId: Int,
        isPass: Boolean
    ) {
        checkAuditor(userId, bookId)
        databaseManager.suspendedTransaction {
            BookChapterTable
                .update({ BookChapterTable.id eq chapterId }) {
                    it[BookChapterTable.status] = if (isPass) BookStatus.APPROVED else BookStatus.REJECTED
                }
        }
    }

    suspend fun updateBook(
        userId: Int,
        bookId: Int,
        isPass: Boolean
    ) {
        checkAuditor(userId, bookId)
        databaseManager.suspendedTransaction {
            BookTable
                .update({ BookTable.id eq bookId }) {
                    it[BookTable.status] = if (isPass) BookStatus.APPROVED else BookStatus.REJECTED
                }
        }
    }
}