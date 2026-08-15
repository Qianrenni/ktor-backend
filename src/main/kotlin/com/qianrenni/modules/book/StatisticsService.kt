package com.qianrenni.modules.book

import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.common.ReportEnum
import com.qianrenni.models.tables.ChapterReadStatisticsTable
import com.qianrenni.models.tables.UserReadEventTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class StatisticsService(private val databaseManager: DatabaseManager) {
    suspend fun addUserReadEvent(userId: Int, bookId: Int, chapterId: Int, eventType: ReportEnum) {
        databaseManager.suspendedTransaction {
            UserReadEventTable.insert {
                it[UserReadEventTable.userId] = userId
                it[UserReadEventTable.bookId] = bookId
                it[UserReadEventTable.chapterId] = chapterId
                it[UserReadEventTable.eventType] = eventType
            }
        }
    }

    /**
     * 将指定结束时间之前未聚合的 UserReadEvent 按小时窗口聚合到 ChapterReadStatistics。
     * 由定时任务每小时调用。
     */
    suspend fun aggregateUserReadStatistics(endTime: LocalDateTime) {
        var startTime: LocalDateTime? = null
        do {
            startTime?.let {
                aggregateHourlyStatistics(it, it.plusHours(1))
            }
            startTime = null
            databaseManager.suspendedTransaction(readOnly = true) {
                UserReadEventTable
                    .select(UserReadEventTable.eventTime)
                    .where { UserReadEventTable.eventTime less endTime }
                    .orderBy(UserReadEventTable.eventTime to SortOrder.ASC)
                    .limit(1)
                    .firstOrNull()
                    ?.let {
                        startTime = it[UserReadEventTable.eventTime].truncatedTo(ChronoUnit.HOURS)
                    }
            }
        } while (startTime != null)
    }

    /**
     * 将指定时间段内的 UserReadEvent 聚合到 ChapterReadStatistics
     * @param startTime 小时窗口开始 (含)
     * @param endTime   小时窗口结束 (不含)
     */
    private suspend fun aggregateHourlyStatistics(
        startTime: LocalDateTime,
        endTime: LocalDateTime
    ) {
        databaseManager.suspendedTransaction {
            // 1. 统计 PV 和 UV (仅 ENTER 事件)
            val pvCount = UserReadEventTable.userId.count()
            val uvCount = UserReadEventTable.userId.countDistinct()
            val pvUvRows = UserReadEventTable
                .select(
                    UserReadEventTable.bookId,
                    UserReadEventTable.chapterId,
                    pvCount,
                    uvCount,
                )
                .where {
                    (UserReadEventTable.eventType eq ReportEnum.ENTER) and
                            (UserReadEventTable.eventTime greaterEq startTime) and
                            (UserReadEventTable.eventTime less endTime)
                }
                .groupBy(UserReadEventTable.bookId, UserReadEventTable.chapterId)
                .toList()

            // 初始化统计表映射
            val statisticMap = mutableMapOf<Pair<Int, Int>, MutableMap<String, Number>>()
            for (row in pvUvRows) {
                val key = row[UserReadEventTable.bookId] to row[UserReadEventTable.chapterId]
                statisticMap[key] = mutableMapOf(
                    "pv" to row[pvCount],
                    "uv" to row[uvCount],
                    "totalDuration" to 0
                )
            }

            // 如果没有 ENTER 事件，直接清理本窗口事件并退出
            if (statisticMap.isEmpty()) {
                UserReadEventTable.deleteWhere {
                    (UserReadEventTable.eventTime greaterEq startTime) and
                            (UserReadEventTable.eventTime less endTime)
                }
                return@suspendedTransaction
            }

            // 2. 获取本窗口内所有事件（含 ENTER 和其他，按 (user, book, chapter) 分组并排序）
            val events = UserReadEventTable
                .select(
                    UserReadEventTable.userId,
                    UserReadEventTable.bookId,
                    UserReadEventTable.chapterId,
                    UserReadEventTable.eventType,
                    UserReadEventTable.eventTime
                )
                .where {
                    (UserReadEventTable.eventTime greaterEq startTime) and
                            (UserReadEventTable.eventTime less endTime)
                }
                .orderBy(
                    UserReadEventTable.userId to SortOrder.ASC,
                    UserReadEventTable.bookId to SortOrder.ASC,
                    UserReadEventTable.chapterId to SortOrder.ASC,
                    UserReadEventTable.eventTime to SortOrder.ASC
                )
                .toList()

            // 按 (bookId, chapterId) 分组，内部按用户分组计算时长
            val userGroups = events.groupBy {
                Triple(
                    it[UserReadEventTable.userId],
                    it[UserReadEventTable.bookId],
                    it[UserReadEventTable.chapterId]
                )
            }
            for ((userBookChapter, userEvents) in userGroups) {
                val (_, bookId, chapterId) = userBookChapter
                var last = startTime
                var totalDuration = 0L // 秒

                for (event in userEvents) {
                    val eventType = event[UserReadEventTable.eventType]
                    val eventTime = event[UserReadEventTable.eventTime]
                    if (eventType == ReportEnum.ENTER) {
                        last = eventTime
                    } else {
                        totalDuration += ChronoUnit.SECONDS.between(last, eventTime)
                        last = eventTime
                    }
                }
                // 至少1秒
                if (totalDuration == 0L) {
                    totalDuration = 1
                }
                val key = bookId to chapterId
                val stats = statisticMap.getOrPut(key) {
                    mutableMapOf("pv" to 0, "uv" to 0, "totalDuration" to 0)
                }
                stats["totalDuration"] = stats["totalDuration"] as Int + totalDuration.toInt() // 累加
            }

            // 3. 批量插入聚合结果
            statisticMap.map { (key, value) ->
                ChapterReadStatisticsTable.insert {
                    it[bookId] = key.first
                    it[chapterId] = key.second
                    it[hourStart] = startTime
                    it[pageViewCount] = value["pv"]!!.toInt()
                    it[uniqueReaderCount] = value["uv"]!!.toInt()
                    it[totalDuration] = value["totalDuration"]!!.toFloat()
                }
            }
            // 4. 删除已聚合的原始事件
            UserReadEventTable.deleteWhere {
                (UserReadEventTable.eventTime less endTime)
            }
        }
    }
}