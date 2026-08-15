package com.qianrenni.common.config

/**
 * 动态配置数据类：不可变、含默认值、提供严格解析（解析失败返回 null 用于校验/兜底）。
 * 各领域配置由 [ConfigDomain] 关联。
 */

/** 限流动态配置（Ktor RateLimit 插件在启动时读取） */
data class RateLimitConfig(
    val windowSeconds: Int = 60,
    val maxRequests: Int = 30,
) {
    companion object {
        val DEFAULT = RateLimitConfig()

        /** 严格解析：所有键必须可解析且范围合法，否则返回 null */
        fun parse(raw: Map<String, String>): RateLimitConfig? {
            val window = raw["windowSeconds"]?.toIntOrNull() ?: return null
            val max = raw["maxRequests"]?.toIntOrNull() ?: return null
            if (window <= 0 || max <= 0) return null
            return RateLimitConfig(window, max)
        }
    }

    fun toValues(): Map<String, String> = mapOf(
        "windowSeconds" to windowSeconds.toString(),
        "maxRequests" to maxRequests.toString(),
    )
}

/** 缓存动态配置（各类缓存过期时间，秒） */
data class CacheConfig(
    val bookCacheExpire: Int = 1800,
) {
    companion object {
        val DEFAULT = CacheConfig()

        fun parse(raw: Map<String, String>): CacheConfig? {
            val bookCache = raw["bookCacheExpire"]?.toIntOrNull() ?: return null
            if (bookCache <= 0) return null
            return CacheConfig(bookCache)
        }
    }

    fun toValues(): Map<String, String> = mapOf(
        "bookCacheExpire" to bookCacheExpire.toString(),
    )
}

/** 内容存储 compact 动态配置（ContentStoreCompactor 每次扫描时读取） */
data class CompactConfig(
    val garbageThreshold: Double = 0.4,
    val minLiveBytes: Long = 64 * 1024,
    val maxFileBytes: Long = 512L * 1024 * 1024,
) {
    companion object {
        val DEFAULT = CompactConfig()

        fun parse(raw: Map<String, String>): CompactConfig? {
            val threshold = raw["garbageThreshold"]?.toDoubleOrNull() ?: return null
            val minLive = raw["minLiveBytes"]?.toLongOrNull() ?: return null
            val maxFile = raw["maxFileBytes"]?.toLongOrNull() ?: return null
            if (threshold !in 0.0..1.0) return null
            if (minLive < 0 || maxFile <= 0 || maxFile < minLive) return null
            return CompactConfig(threshold, minLive, maxFile)
        }
    }

    fun toValues(): Map<String, String> = mapOf(
        "garbageThreshold" to garbageThreshold.toString(),
        "minLiveBytes" to minLiveBytes.toString(),
        "maxFileBytes" to maxFileBytes.toString(),
    )
}
