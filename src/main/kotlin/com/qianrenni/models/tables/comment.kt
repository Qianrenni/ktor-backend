package com.qianrenni.models.tables

import com.qianrenni.common.BookStatus
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

object BookCommentTable : IntIdTable(name = "book_comment") {
    val bookId = integer("bookId").references(BookTable.id)
    val userId = integer("userId").references(UserTable.id)
    val status = enumerationByName<BookStatus>("status", 50).default(BookStatus.PUBLISHED)
    val createdAt = datetime("createdAt").default(LocalDateTime.now())
    val updatedAt = datetime("updatedAt").default(LocalDateTime.now())
    val parentId = integer("parentId").references(BookCommentTable.id).nullable()
}

object BookChapterCommentTable : IntIdTable(name = "book_chapter_comment") {
    val chapterId = integer("chapterId").references(BookChapterTable.id)
    val userId = integer("userId").references(UserTable.id)
    val status = enumerationByName<BookStatus>("status", 50).default(BookStatus.PUBLISHED)
    val createdAt = datetime("createdAt").default(LocalDateTime.now())
    val updatedAt = datetime("updatedAt").default(LocalDateTime.now())
    val parentId = integer("parentId").references(BookChapterCommentTable.id).nullable()
    val line = integer("line")
}

@Serializable
data class BookComment(
    val id: Int,
    val bookId: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String,
    val content: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val parentId: Int? = null
)

fun ResultRow.toBookComment(userName: String, userAvatar: String, content: String): BookComment {
    return BookComment(
        id = this[BookCommentTable.id].value,
        bookId = this[BookCommentTable.bookId],
        userId = this[BookCommentTable.userId],
        userName = userName,
        userAvatar = userAvatar,
        content = content,
        status = this[BookCommentTable.status].name,
        createdAt = this[BookCommentTable.createdAt].toString(),
        updatedAt = this[BookCommentTable.updatedAt].toString(),
        parentId = this[BookCommentTable.parentId]
    )
}

@Serializable
data class BookChapterComment(
    val id: Int,
    val chapterId: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String,
    val content: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val parentId: Int? = null,
    val line: Int
)

fun ResultRow.toBookChapterComment(userName: String, userAvatar: String, content: String): BookChapterComment {
    return BookChapterComment(
        id = this[BookChapterCommentTable.id].value,
        chapterId = this[BookChapterCommentTable.chapterId],
        userId = this[BookChapterCommentTable.userId],
        userName = userName,
        userAvatar = userAvatar,
        content = content,
        status = this[BookChapterCommentTable.status].name,
        createdAt = this[BookChapterCommentTable.createdAt].toString(),
        updatedAt = this[BookChapterCommentTable.updatedAt].toString(),
        parentId = this[BookChapterCommentTable.parentId],
        line = this[BookChapterCommentTable.line]
    )
}
