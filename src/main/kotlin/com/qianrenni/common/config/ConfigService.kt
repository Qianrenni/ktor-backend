package com.qianrenni.common.config

import java.util.concurrent.ConcurrentHashMap

/**
 * 动态配置服务：本地按领域缓存 + 回源加载 + 按域失效 + 默认值兜底。
 *
 * 模型（Redis 配置中心 + 本地缓存）：
 * - 业务读 [get]/[rateLimit]/[cache]/[compact]：本地缓存命中直接返回；
 *   未命中回源 [ConfigSource.load]，Redis 无值/不可达/解析失败则用默认值兜底；
 *   **仅缓存成功的回源结果**，避免 Redis 恢复后仍一直用旧默认值。
 * - 管理端 [update]：合并到当前生效值 → 严格校验 → [ConfigSource.save]（写 Redis 并发布变更）
 *   → 失效本实例本地缓存；其它实例通过订阅变更通知调用 [invalidate]。
 */
class ConfigService(private val source: ConfigSource) {

    private val cache = ConcurrentHashMap<ConfigDomain, Any>()

    /**
     * 读取类型化配置：默认值兜底，解析失败也回退默认值。
     * 仅「成功回源并解析」的结果才写入本地缓存——Redis 无值/不可达/解析失败时
     * 每次都返回默认值但不缓存，避免 Redis 恢复后仍一直用旧默认值。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(domain: ConfigDomain, defaults: T, parse: (Map<String, String>) -> T?): T {
        cache[domain]?.let { return it as T }
        val raw = source.load(domain) ?: return defaults
        val parsed = parse(raw) ?: return defaults
        cache[domain] = parsed
        return parsed
    }

    fun rateLimit(): RateLimitConfig =
        get(ConfigDomain.RATE_LIMIT, RateLimitConfig.DEFAULT, RateLimitConfig::parse)

    fun cache(): CacheConfig =
        get(ConfigDomain.CACHE, CacheConfig.DEFAULT, CacheConfig::parse)

    fun compact(): CompactConfig =
        get(ConfigDomain.COMPACT, CompactConfig.DEFAULT, CompactConfig::parse)

    /** 当前生效的原始值：Redis 有值取 Redis，否则默认值（管理端展示用） */
    fun currentValues(domain: ConfigDomain): Map<String, String> =
        source.load(domain) ?: domain.defaultValues()

    /**
     * 更新领域配置（支持部分字段：未提供的键沿用当前生效值）。
     * @return true 表示校验通过并已写入；false 表示参数非法（拒绝写入）
     */
    fun update(domain: ConfigDomain, values: Map<String, String>): Boolean {
        val merged = currentValues(domain) + values
        if (!validate(domain, merged)) return false
        source.save(domain, merged)
        invalidate(domain)
        return true
    }

    /** 清除某领域本地缓存（配置变更后本实例/他实例调用） */
    fun invalidate(domain: ConfigDomain) {
        cache.remove(domain)
    }

    fun invalidateAll() {
        ConfigDomain.entries.forEach(::invalidate)
    }

    private fun validate(domain: ConfigDomain, values: Map<String, String>): Boolean = when (domain) {
        ConfigDomain.RATE_LIMIT -> RateLimitConfig.parse(values) != null
        ConfigDomain.CACHE -> CacheConfig.parse(values) != null
        ConfigDomain.COMPACT -> CompactConfig.parse(values) != null
    }
}
