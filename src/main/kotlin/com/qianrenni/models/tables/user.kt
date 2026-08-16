package com.qianrenni.models.tables

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime


object UserTable: IntIdTable(name = "user") {
    val userName = varchar("userName", 255)
    val password = varchar("password", 255)
    val email = varchar("email", 255)
    val isActive = bool("isActive").default(true)
    val avatar = varchar("avatar", 255).default("")
    val createdAt = datetime(name = "createdAt").clientDefault { LocalDateTime.now() }
    val updatedAt = datetime(name = "updatedAt").clientDefault { LocalDateTime.now() }
}
object UserReadingProgressTable : Table(name = "user_reading_progress") {
    val userId = integer("userId").references(UserTable.id)
    val bookId = integer("bookId").references(BookTable.id)
    val lastChapterId = integer("lastChapterId")
    val lastPosition = integer("lastPosition").default(0)
    val lastReadAt = datetime("lastReadAt").defaultExpression(CurrentDateTime)
}

object ShelfTable : Table(name = "shelf") {
    val userId = integer("userId").references(UserTable.id)
    val bookId = integer("bookId").references(BookTable.id)
    val createdAt = datetime("createdAt").defaultExpression(CurrentDateTime)
}

@Serializable
data class Shelf(
    val userId: Int,
    val bookId: Int,
    val createdAt: String,
)

@Serializable
data class UserReadingProgress(
    val userId: Int,
    val bookId: Int,
    val lastChapterId: Int,
    val lastPosition: Int,
    val lastReadAt: String,
)

@Serializable
data class FullUser(
    val id: Int = -1,
    val userName: String = "",
    val email: String = "",
    val isActive: Boolean = true,
    val avatar: String = "",
    val password: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val right: List<Int> = emptyList(),
) {
    init {

        require(userName.isNotBlank()) { "用户名不能为空" }
        require(userName.length < 50) { "用户名长度不能超过50字" }
    }
}

fun ResultRow.toFullUser() = FullUser(
    id = this[UserTable.id].value,
    userName = this[UserTable.userName],
    email = this[UserTable.email],
    isActive = this[UserTable.isActive],
    avatar = this[UserTable.avatar],
    createdAt = this[UserTable.createdAt].toString(),
    updatedAt = this[UserTable.updatedAt].toString(),
)

fun ResultRow.toShelf() = Shelf(
    userId = this[ShelfTable.userId],
    bookId = this[ShelfTable.bookId],
    createdAt = this[ShelfTable.createdAt].toString(),
)

fun ResultRow.toUserReadingProgress() = UserReadingProgress(
    userId = this[UserReadingProgressTable.userId],
    bookId = this[UserReadingProgressTable.bookId],
    lastChapterId = this[UserReadingProgressTable.lastChapterId],
    lastPosition = this[UserReadingProgressTable.lastPosition],
    lastReadAt = this[UserReadingProgressTable.lastReadAt].toString(),
)