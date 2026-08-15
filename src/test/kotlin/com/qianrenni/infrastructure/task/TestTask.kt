package com.qianrenni.infrastructure.task

import com.qianrenni.testutil.*

import com.qianrenni.bootstrap.services
import com.qianrenni.infrastructure.database.databaseManager
import io.ktor.client.request.*
import io.ktor.server.testing.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.Ignore
import kotlin.test.Test


class TestTask {
    @Test
    @Ignore("API 级启动测试：依赖生产环境变量与 HTTP 路由；aggregateUserReadStatistics 已由 TestOutboxIntegration 等价覆盖")
    fun testAggregateHourlyStatistics() = testApplication {
        configure()
        client.get("/")
        application.services.book.statisticsService.aggregateUserReadStatistics(
            LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        )
    }
}