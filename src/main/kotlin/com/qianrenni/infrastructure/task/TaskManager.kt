package com.qianrenni.infrastructure.task

import com.qianrenni.infrastructure.task.cronFlow
import io.ktor.events.Events
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import org.slf4j.Logger
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 定时任务配置
 * @param name 任务名称，用于日志标识
 * @param cronExpression cron 表达式（Quartz 格式，支持秒）
 * @param zone 时区，默认系统时区
 * @param handler 任务触发时的处理函数，接收触发时间 [ZonedDateTime]
 */
data class TaskConfig(
    val name: String,
    val cronExpression: String,
    val zone: ZoneId = ZoneId.systemDefault(),
    val handler: suspend (ZonedDateTime) -> Unit
)

/**
 * 定时任务管理器
 * 基于 cronFlow 构建，通过 cron 表达式注册定时任务。
 *
 * 用法：
 * ```kotlin
 * application.taskManager.register(
 *     TaskConfig(name = "每小时统计", cronExpression = "0 0 * * * ?") {
 *         aggregateHourlyStatistics(...)
 *     }
 * )
 * application.taskManager.start()
 * ```
 */
class TaskManager(
    private val events: Events,
    private val logger: Logger,
) {
    private val tasks = mutableListOf<TaskConfig>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 注册一个定时任务
     */
    fun register(task: TaskConfig) {
        tasks.add(task)
    }

    /**
     * 启动所有已注册的定时任务
     */
    fun start() {
        // 监听应用停止事件，自动清理协程
        events.subscribe(ApplicationStopping) {
            scope.cancel()
        }

        tasks.forEach { task ->
            scope.launch {
                runTask(task)
            }
        }
        logger.info("已启动 ${tasks.size} 个定时任务")
    }

    private suspend fun runTask(task: TaskConfig) {
        cronFlow(task.cronExpression, task.zone)
            .catch { e ->
                logger.error("定时任务 [${task.name}] cron 表达式解析失败: ${e.message}", e)
            }
            .collect { triggerTime ->
                try {
                    logger.info("定时任务 [${task.name}] 触发于 {}", triggerTime)
                    task.handler(triggerTime)
                } catch (e: Exception) {
                    logger.error("定时任务 [${task.name}] 执行异常: ${e.message}", e)
                }
            }
    }
}
