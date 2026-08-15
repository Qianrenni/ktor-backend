package com.qianrenni.models.tables

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

object AuditBookTable : Table(name = "audit_book") {
    val bookId = integer("bookId").references(BookTable.id)
    val userId = integer("userId").references(UserTable.id)
    val createdAt = datetime("createdAt").clientDefault { LocalDateTime.now() }
    val updatedAt = datetime("updatedAt").clientDefault { LocalDateTime.now() }
}

@Serializable
data class AuditBook(
    val bookId: Int,
    val userId: Int,
    val createdAt: String,
    val updatedAt: String,
)

fun ResultRow.toAuditBook() = AuditBook(
    bookId = this[AuditBookTable.bookId],
    userId = this[AuditBookTable.userId],
    createdAt = this[AuditBookTable.createdAt].toString(),
    updatedAt = this[AuditBookTable.updatedAt].toString()
)

object AuditBookChapterTable : Table(name = "audit_book_chapter") {
    val bookChapterId = integer("bookChapterId").references(BookChapterTable.id)
    val userId = integer("userId").references(UserTable.id)
    val createdAt = datetime("createdAt").clientDefault { LocalDateTime.now() }
    val updatedAt = datetime("updatedAt").clientDefault { LocalDateTime.now() }
}

@Serializable
data class AuditBookChapter(
    val bookChapterId: Int,
    val userId: Int,
    val createdAt: String,
    val updatedAt: String
)

fun ResultRow.toAuditBookChapter() = AuditBookChapter(
    bookChapterId = this[AuditBookChapterTable.bookChapterId],
    userId = this[AuditBookChapterTable.userId],
    createdAt = this[AuditBookChapterTable.createdAt].toString(),
    updatedAt = this[AuditBookChapterTable.updatedAt].toString()
)