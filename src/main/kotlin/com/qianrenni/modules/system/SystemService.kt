package com.qianrenni.modules.system

import com.qianrenni.models.domain.DiskStatus
import com.qianrenni.models.domain.LogEntry
import com.qianrenni.models.domain.LogFileInfo
import com.qianrenni.models.domain.SystemStatus
import com.qianrenni.common.PageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import oshi.SystemInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round
import kotlin.time.Duration.Companion.seconds

/**
 * 系统信息服务
 * 获取服务器 CPU、内存、虚存和所有磁盘状态
 */
class SystemService {

    companion object {
        /**
         * 日志行正则（匹配 logback.xml 中的 pattern）
         * pattern: %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n
         */
        private val logLineRegex = Regex(
            """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[(.*?)]\s+(\w+)\s+(\S+)\s*-\s*(.*)$""",
            setOf(RegexOption.MULTILINE)
        )

        /**
         * 解析单行日志
         */
        private fun parseLogLine(lineNumber: Int, line: String): LogEntry? {
            val match = logLineRegex.find(line) ?: return null
            val (timestamp, thread, level, logger, message) = match.destructured
            return LogEntry(
                lineNumber = lineNumber,
                timestamp = timestamp,
                thread = thread,
                level = level,
                logger = logger,
                message = message
            )
        }
    }
    private val si = SystemInfo()

    @Volatile
    private var previousTicks: LongArray? = null

    private val lock = Any()

    /**
     * 获取 CPU 占用率（非阻塞）
     * 模拟 psutil.cpu_percent(interval=None) 的行为：
     * 通过两次 tick 采样的差值计算 CPU 利用率
     */
    private fun getCpuPercent(): Double {
        val processor = si.hardware.processor
        val currentTicks = processor.systemCpuLoadTicks

        val percent = synchronized(lock) {
            val prev = previousTicks
            previousTicks = currentTicks
            if (prev != null) {
                processor.getSystemCpuLoadBetweenTicks(prev)
            } else {
                0.0 // 首次调用无历史数据，返回 0.0
            }
        }

        return round(percent * 1000.0) / 10.0 // 保留一位小数
    }

    /**
     * 列出所有可用日志文件
     * 扫描 logs/ 目录下所有 .log 文件
     */
    suspend fun listLogFiles(): List<LogFileInfo> = withContext(Dispatchers.IO) {
        val logDir = File("logs")
        if (!logDir.exists() || !logDir.isDirectory) return@withContext emptyList()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        logDir.listFiles { file -> file.extension == "log" }
            ?.map { file ->
                LogFileInfo(
                    name = file.name,
                    size = file.length(),
                    lastModified = sdf.format(Date(file.lastModified())),
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    /**
     * 读取日志文件内容（分页 + 级别过滤）
     *
     * @param fileName  日志文件名,如 "app.log"
     * @param level     过滤级别,可选 null(不过滤)
     * @param page      页码,从1开始
     * @param size      每页条数
     * @return PageResult<LogEntry>
     */
    suspend fun readLogFile(
        fileName: String,
        level: String? = null,
        regex: String? = null,
        page: Int = 1,
        size: Int = 100
    ): PageResult<LogEntry> = withContext(Dispatchers.IO) {
        // 安全加固（H2）：规范化路径，仅允许读取 logs 目录内的 .log 文件，防止路径穿越
        val logDir = File("logs").canonicalFile
        val logFile = File(logDir, fileName).canonicalFile
        if (!logFile.path.startsWith(logDir.path + File.separator) || logFile.extension != "log") {
            throw IllegalArgumentException("非法文件名")
        }
        if (!logFile.exists() || !logFile.isFile) {
            return@withContext PageResult(items = emptyList(), total = 0, page = page, size = size)
        }

        val allLines = logFile.readLines(Charsets.UTF_8)

        // 解析并过滤（级别）
        val matchedEntries = allLines.mapIndexedNotNull { index, line ->
            parseLogLine(index + 1, line)
        }.filter { entry ->
            level == null || entry.level.equals(level, ignoreCase = true)
        }

        // 正则过滤（带超时保护，防止 redos）
        val filteredEntries = if (regex != null) {
            try {
                val pattern = Regex(regex)
                withTimeout(5.seconds) {
                    matchedEntries.filter { entry ->
                        pattern.containsMatchIn(entry.message)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw IllegalArgumentException("正则执行超时，请尝试更简单的表达式")
            }
        } else {
            matchedEntries
        }

        val total = filteredEntries.size
        val offset = (page - 1) * size
        val items = filteredEntries.sortedByDescending { it.timestamp }.drop(offset).take(size)

        PageResult(items = items, total = total, page = page, size = size)
    }

    /**
     * 获取系统状态信息
     */
    suspend fun getSystemInfo(): SystemStatus = withContext(Dispatchers.IO) {
        val hal = si.hardware
        val os = si.operatingSystem

        // 1. CPU 占用率
        val cpuPercent = getCpuPercent()

        // 2. 内存使用情况
        val memory = hal.memory
        val memoryTotal = memory.total
        val memoryUsed = memory.total - memory.available

        // 3. 虚存 (Swap) 使用情况
        val swapTotal = memory.virtualMemory.swapTotal
        val swapUsed = memory.virtualMemory.swapUsed

        // 4. 磁盘使用情况（所有分区）
        val fileStores = os.fileSystem.fileStores
        val diskStatuses = fileStores.mapNotNull { store ->
            try {
                val total = store.totalSpace
                val free = store.freeSpace
                val used = total - free
                val percent = if (total > 0) {
                    used.toDouble() / total * 100.0
                } else {
                    0.0
                }

                DiskStatus(
                    mountPoint = store.mount,
                    device = store.name,
                    fStype = store.type,
                    total = total,
                    used = used,
                    free = free,
                    percent = round(percent * 10.0) / 10.0,
                )
            } catch (_: Exception) {
                null
            }
        }

        SystemStatus(
            cpuPercent = cpuPercent,
            memoryTotal = memoryTotal,
            memoryUsed = memoryUsed,
            swapTotal = swapTotal,
            swapUsed = swapUsed,
            disks = diskStatuses,
        )
    }
}
