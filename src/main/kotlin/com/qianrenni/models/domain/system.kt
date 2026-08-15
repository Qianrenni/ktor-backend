package com.qianrenni.models.domain

import kotlinx.serialization.Serializable

/**
 * 单个磁盘分区的状态
 * 对应 Python 版 DiskStatus 模型
 */
@Serializable
data class DiskStatus(
    val mountPoint: String,
    val device: String,
    val fStype: String,
    val total: Long,
    val used: Long,
    val free: Long,
    val percent: Double,
)

/**
 * 系统状态
 * 对应 Python 版 SystemStatus 模型
 */
@Serializable
data class SystemStatus(
    val cpuPercent: Double,
    val memoryTotal: Long,
    val memoryUsed: Long,
    val swapTotal: Long,
    val swapUsed: Long,
    val disks: List<DiskStatus>,
)

/**
 * 日志文件信息
 */
@Serializable
data class LogFileInfo(
    val name: String,
    val size: Long,
    val lastModified: String,
)

/**
 * 单条日志条目（解析后结构）
 */
@Serializable
data class LogEntry(
    val lineNumber: Int,
    val timestamp: String,
    val thread: String,
    val level: String,
    val logger: String,
    val message: String,
)
