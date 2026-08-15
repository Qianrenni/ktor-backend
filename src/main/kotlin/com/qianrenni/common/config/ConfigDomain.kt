package com.qianrenni.common.config

/**
 * 动态配置领域标识。
 *
 * 每个领域对应一组可运行期调整的配置（存储于 Redis Hash，key 见 [redisKey]）。
 * 静态敏感配置（DB/Redis DSN、JWT 密钥、SMTP 密码等）不属于动态区。
 */
enum class ConfigDomain(val redisKey: String) {
    /** 限流：窗口秒数 / 窗口内最大请求数 */
    RATE_LIMIT("config:ratelimit"),

    /** 缓存：各类缓存过期时间（秒） */
    CACHE("config:cache"),

    /** 内容存储 compact：垃圾占比 / 最小有效字节 / 最大文件大小 */
    COMPACT("config:compact");

    /** 该领域配置缺失时的默认值（兜底） */
    fun defaultValues(): Map<String, String> = when (this) {
        RATE_LIMIT -> RateLimitConfig.DEFAULT.toValues()
        CACHE -> CacheConfig.DEFAULT.toValues()
        COMPACT -> CompactConfig.DEFAULT.toValues()
    }

    companion object {
        /** 按名称解析（忽略大小写），未知返回 null */
        fun fromName(name: String): ConfigDomain? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
