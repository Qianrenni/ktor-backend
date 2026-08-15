package com.qianrenni.models.tables

import com.qianrenni.common.BookStatus
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime


object BookTable : IntIdTable(name = "book") {
    val name = varchar("name", 255)
    val author = varchar("author", 255)
    val description = text("description")
    val category = varchar("category", 25)
    val tags = varchar("tags", 255)
    val totalChapter = integer(name = "totalChapter")
    val createdAt = datetime("createdAt").default(LocalDateTime.now())
    val updatedAt = datetime("updatedAt").default(LocalDateTime.now())
    val wordsCount = integer("wordsCount")
    val isActive = bool("isActive")
    val isEnded = bool("isEnded")
    val status = enumerationByName<BookStatus>(
        name = "status",
        length = 25
    )
}
object BookChapterTable : IntIdTable(name = "book_chapter") {
    val bookId = integer("bookId").references(BookTable.id)
    val title = varchar("title", 255)
    val wordCount = integer("wordCount")
    val createdAt = datetime("createdAt")
    val updatedAt = datetime("updatedAt")
    val status = enumerationByName<BookStatus>("status", 25)
    val isActive = bool("isActive")
    val order = float("order")
}


@Serializable
data class Book(
    val id: Int,
    val name: String = "",
    val author: String = "",
    val cover: String = "",
    val description: String = "",
    val category: String = "",
    val tags: String = "",
    val totalChapter: Int = 0,
    val wordsCount: Int = 0,
    val isActive: Boolean = true,
    val isEnded: Boolean = false,
    val status: BookStatus,
    val createdAt: String = "",
    val updatedAt: String = ""
)


@Serializable
data class BookCatalogItem(
    val id: Int,
    val bookId: Int,
    val title: String,
    val wordsCount: Int,
    val status: BookStatus,
    val order: Float,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

fun ResultRow.toBook(serverUrl: String) = Book(
    id = this[BookTable.id].value,
    name = this[BookTable.name],
    author = this[BookTable.author],
    cover = "${serverUrl}/static/book/${this[BookTable.id].value}/cover.webp",
    description = this[BookTable.description],
    category = this[BookTable.category],
    tags = this[BookTable.tags],
    totalChapter = this[BookTable.totalChapter],
    wordsCount = this[BookTable.wordsCount],
    isActive = this[BookTable.isActive],
    isEnded = this[BookTable.isEnded],
    status = this[BookTable.status],
    createdAt = this[BookTable.createdAt].toString(),
    updatedAt = this[BookTable.updatedAt].toString()
)

@Serializable
data class AdminBook(
    val id: Int,
    val name: String = "",
    val author: String = "",
    val cover: String = "",
    val description: String = "",
    val category: String = "",
    val tags: String = "",
    val totalChapter: Int = 0,
    val wordsCount: Int = 0,
    val isActive: Boolean = true,
    val isEnded: Boolean = false,
    val status: BookStatus,
    val createdAt: String = "",
    val updatedAt: String = "",
    val authorCount: Int = 0
)

fun ResultRow.toAdminBook(serverUrl: String, authorCount: Int = 0) = AdminBook(
    id = this[BookTable.id].value,
    name = this[BookTable.name],
    author = this[BookTable.author],
    cover = "${serverUrl}/static/book/${this[BookTable.id].value}/cover.webp",
    description = this[BookTable.description],
    category = this[BookTable.category],
    tags = this[BookTable.tags],
    totalChapter = this[BookTable.totalChapter],
    wordsCount = this[BookTable.wordsCount],
    isActive = this[BookTable.isActive],
    isEnded = this[BookTable.isEnded],
    status = this[BookTable.status],
    createdAt = this[BookTable.createdAt].toString(),
    updatedAt = this[BookTable.updatedAt].toString(),
    authorCount = authorCount
)

fun ResultRow.toBookCatalogItem() = BookCatalogItem(
    id = this[BookChapterTable.id].value,
    bookId = this[BookChapterTable.bookId],
    title = this[BookChapterTable.title],
    wordsCount = this[BookChapterTable.wordCount],
    status = this[BookChapterTable.status],
    order = this[BookChapterTable.order],
    isActive = this[BookChapterTable.isActive],
    createdAt = this[BookChapterTable.createdAt].toString(),
    updatedAt = this[BookChapterTable.updatedAt].toString()
)