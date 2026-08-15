package com.qianrenni.bootstrap

import com.qianrenni.common.appConfig
import com.qianrenni.common.loadConfig
import com.qianrenni.bootstrap.configureRouting
import com.qianrenni.infrastructure.database.configureDatabase
import com.qianrenni.infrastructure.database.configureRedis
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.web.*
import com.qianrenni.infrastructure.task.TaskConfig
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.time.temporal.ChronoUnit

/**
 * 应用主入口
 * 配置依赖注入和应用生命周期
 */
fun Application.main() {
    // 1. 加载配置
    loadConfig()
    // 2. 初始化数据库
    configureDatabase()
    // 3. 初始化 Redis
    configureRedis()
    configureHTTP()
    configureRateLimiting()
    configureResources()
    configureSecurity()
    configureStatusPages()
    configureMetrics()
    // 4. 装配服务组合根（必须在 configureRouting 之前：路由通过参数注入获取服务）
    configService()
    // 5. 启动动态配置变更订阅（Redis Pub/Sub，多实例本地缓存失效）
    services.infra.startConfigSubscription()
    monitor.subscribe(ApplicationStopped) {
        services.infra.stopConfigSubscription()
    }
    // 6. 启动 Outbox channel 消费（先消费遗留记录，再监听事务提交信号）
    services.infra.outboxService.start()
    // 7. 注册路由
    configureRouting()
    // 8. 注册并启动定时任务
    configureScheduledTasks()
}

/**
 * 配置定时任务
 */
private fun Application.configureScheduledTasks() {
    services.infra.taskManager.apply {
        // 每小时整点执行统计聚合（秒 分 时 日 月 周）
        register(
            TaskConfig(
                name = "每小时阅读统计聚合",
                cronExpression = "0 5 * * * ?"
            ) { triggerTime ->
                val hourEnd = triggerTime.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
                services.book.statisticsService.aggregateUserReadStatistics(hourEnd)
            }
        )
        register(
            TaskConfig(
                name = "每小时发布内容",
                cronExpression = "0 30 * * * ?"
            ) {
                // 先消费待处理文件操作，确保发布读取到最新章节内容
                services.infra.outboxService.processPending()
                services.book.bookService.publishBook()
            }
        )
        register(
            TaskConfig(
                name = "每小时自动发布审核超时内容",
                cronExpression = "0 40 * * * ?"
            ) {
                // 先消费待处理文件操作，确保发布读取到最新章节内容
                services.infra.outboxService.processPending()
                services.book.bookService.publishReviewTimeoutBooks()
            }
        )
        register(
            TaskConfig(
                name = "文件同步Outbox兜底消费",
                // channel 事件驱动即时消费为主，此任务仅作兜底：
                // 覆盖进程重启遗留（start 已处理）、信号丢失、多实例间他实例写入等场景
                cronExpression = "0 * * * * ?"
            ) {
                services.infra.outboxService.processPending()
            }
        )
        // 内容存储自动 compact：定时扫描 + 垃圾占比阈值过滤（见 ContentStoreCompactor）
        if (appConfig.contentStoreCompactEnable) {
            register(
                TaskConfig(
                    name = "内容存储自动compact",
                    cronExpression = appConfig.contentStoreCompactCron
                ) {
                    services.infra.contentStoreCompactor.compactAll()
                }
            )
        }
        start()
    }
}
fun main(args: Array<String>) {

    embeddedServer(Netty, port = 8000, host = "0.0.0.0", module = Application::main)
        .start(wait = true)
}
