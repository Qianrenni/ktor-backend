package com.qianrenni.models.tables

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.datetime

object ChapterReadStatisticsTable : IntIdTable(name = "chapter_read_statistics") {
    val bookId = integer("bookId").references(BookTable.id)
    val chapterId = integer("chapterId").references(BookChapterTable.id)
    val hourStart = datetime("hourStart")
    val uniqueReaderCount = integer("uniqueReaderCount")
    val pageViewCount = integer("pageViewCount")
    val totalDuration = float("totalDuration")
}

@Serializable
data class ChapterReadStatistics(
    val id: Int,
    val bookId: Int,
    val chapterId: Int,
    val hourStart: String = "",
    val uniqueReaderCount: Int = 0,
    val pageViewCount: Int = 0,
    val totalDuration: Double = 0.0
)

fun ResultRow.toChapterReadStatistics() = ChapterReadStatistics(
    id = this[ChapterReadStatisticsTable.id].value,
    bookId = this[ChapterReadStatisticsTable.bookId],
    chapterId = this[ChapterReadStatisticsTable.chapterId],
    hourStart = this[ChapterReadStatisticsTable.hourStart].toString(),
    uniqueReaderCount = this[ChapterReadStatisticsTable.uniqueReaderCount],
    pageViewCount = this[ChapterReadStatisticsTable.pageViewCount],
    totalDuration = this[ChapterReadStatisticsTable.totalDuration].toDouble()
)