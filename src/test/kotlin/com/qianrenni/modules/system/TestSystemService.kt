package com.qianrenni.modules.system

import com.qianrenni.testutil.*

import com.qianrenni.modules.system.SystemService
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SystemService 集成单元测试：日志文件列表、日志解析（级别/正则过滤、分页）、系统状态。
 * 注意：SystemService 硬编码读取工作目录下 logs/ 目录（parseLogLine 为 private，通过 readLogFile 间接覆盖）。
 */
class TestSystemService {

    private val logFile = File("logs", "test_diag_${System.currentTimeMillis()}.log")

    private fun writeLog() {
        logFile.parentFile.mkdirs()
        logFile.writeText(
            """
            |2026-08-01 10:00:00.001 [main] INFO  com.qianrenni.Test - 第一行信息
            |2026-08-01 10:00:01.002 [worker-1] WARN  com.qianrenni.Test - 第二行警告
            |2026-08-01 10:00:02.003 [worker-2] ERROR com.qianrenni.Test - 第三行错误
            |不是日志格式的行
            """.trimMargin()
        )
    }

    private fun cleanup() {
        if (logFile.exists()) logFile.delete()
    }

    @Test
    fun `读取不存在的日志文件返回空结果`() = runTest {
        val service = SystemService()
        val result = service.readLogFile("no_such_file.log")
        assertEquals(0, result.total)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `解析日志行并支持级别过滤与正则过滤`() = runTest {
        try {
            writeLog()
            val service = SystemService()

            // 全部（非日志格式行被忽略）；readLogFile 按时间戳倒序，first 是最新日志
            val all = service.readLogFile(logFile.name)
            assertEquals(3, all.total, "应解析出 3 条日志，非格式行被忽略")
            assertEquals("第三行错误", all.items.first().message)
            assertEquals(3, all.items.first().lineNumber)
            assertEquals("第一行信息", all.items.last().message)
            assertEquals(1, all.items.last().lineNumber)

            // 级别过滤（忽略大小写）
            val warnOnly = service.readLogFile(logFile.name, level = "warn")
            assertEquals(1, warnOnly.total)
            assertEquals("WARN", warnOnly.items.first().level)

            // 正则过滤
            val regexMatch = service.readLogFile(logFile.name, regex = "警告")
            assertEquals(1, regexMatch.total)
            assertEquals("第二行警告", regexMatch.items.first().message)

            // 正则不匹配
            val noMatch = service.readLogFile(logFile.name, regex = "不存在的词")
            assertEquals(0, noMatch.total)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `日志分页生效`() = runTest {
        try {
            writeLog()
            val service = SystemService()
            val page1 = service.readLogFile(logFile.name, page = 1, size = 2)
            assertEquals(3, page1.total)
            assertEquals(2, page1.items.size)
            val page2 = service.readLogFile(logFile.name, page = 2, size = 2)
            assertEquals(1, page2.items.size)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `列出日志文件包含新建的测试日志`() = runTest {
        try {
            writeLog()
            val service = SystemService()
            val files = service.listLogFiles()
            val found = files.any { it.name == logFile.name }
            assertTrue(found, "列表应包含测试创建的日志文件")
            assertTrue(files.firstOrNull { it.name == logFile.name }!!.size > 0)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `获取系统状态返回非负指标`() = runTest {
        val service = SystemService()
        val status = service.getSystemInfo()
        assertTrue(status.cpuPercent >= 0.0 && status.cpuPercent <= 100.0)
        assertTrue(status.memoryTotal > 0)
        assertTrue(status.memoryUsed >= 0)
        assertTrue(status.swapTotal >= 0)
        // 磁盘列表非空（本机必有磁盘）
        assertTrue(status.disks.isNotEmpty())
    }
}
