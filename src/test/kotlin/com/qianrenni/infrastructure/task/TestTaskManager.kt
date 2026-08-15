package com.qianrenni.infrastructure.task

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.task.TaskConfig
import com.qianrenni.infrastructure.task.TaskManager
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TaskManager 单元测试：任务注册、cron 触发、非法 cron 表达式容错。
 */
class TestTaskManager {

    @Test
    fun `注册的任务按 cron 触发`() = testApplication {
        val counter = AtomicInteger(0)
        val tm = TaskManager(application.monitor, application.environment.log)
        tm.register(TaskConfig(name = "每秒任务", cronExpression = "0/1 * * * * ?") {
            counter.incrementAndGet()
        })
        tm.start()

        // 真实等待最多 3 秒（TaskManager 协程运行在 Dispatchers.Default，不受虚拟时间控制）
        val deadline = System.currentTimeMillis() + 3000
        while (counter.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(counter.get() >= 1, "每秒任务应在 3 秒内至少触发一次")
    }

    @Test
    fun `非法 cron 表达式不崩溃且不影响其他任务`() = testApplication {
        val counter = AtomicInteger(0)
        val tm = TaskManager(application.monitor, application.environment.log)
        tm.register(TaskConfig(name = "非法表达式", cronExpression = "not-a-cron") {
            counter.incrementAndGet()
        })
        tm.register(TaskConfig(name = "合法每秒任务", cronExpression = "0/1 * * * * ?") {
            counter.incrementAndGet()
        })
        tm.start()

        val deadline = System.currentTimeMillis() + 3000
        while (counter.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        // 非法任务被 catch 记录日志，合法任务照常触发（非法任务不会触发，因此计数只来自合法任务）
        assertTrue(counter.get() >= 1, "合法任务应正常触发")
    }
}
