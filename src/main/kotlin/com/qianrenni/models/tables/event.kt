package com.qianrenni.models.tables

import com.qianrenni.common.ReportEnum
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object UserReadEventTable : Table(name = "user_read_event") {
    val userId = integer("userId").references(UserTable.id)
    val bookId = integer("bookId").references(BookTable.id)
    val chapterId = integer("chapterId").references(BookChapterTable.id)
    val eventTime = datetime("eventTime")
    val eventType = enumerationByName<ReportEnum>("eventType", 25)
}

@Serializable
data class UserReadEvent(
    val userId: Int,
    val bookId: Int,
    val chapterId: Int,
    val eventTime: String,
    val eventType: ReportEnum
)

fun ResultRow.toUserReadEvent() = UserReadEvent(
    userId = this[UserReadEventTable.userId],
    bookId = this[UserReadEventTable.bookId],
    chapterId = this[UserReadEventTable.chapterId],
    eventTime = this[UserReadEventTable.eventTime].toString(),
    eventType = this[UserReadEventTable.eventType]
)