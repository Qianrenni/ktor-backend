package com.qianrenni.common.config

/**
 * 动态配置数据源抽象。
 *
 * - [load]：读取某领域原始 k-v；无值或不可达返回 null（走默认值兜底）。
 * - [save]：写入某领域配置（覆盖整组），并负责触发变更通知（Redis 实现会发布订阅）。
 *
 * 生产实现为 [com.qianrenni.infrastructure.config.RedisConfigSource]，测试可用内存实现。
 */
interface ConfigSource {
    fun load(domain: ConfigDomain): Map<String, String>?
    fun save(domain: ConfigDomain, values: Map<String, String>)
}
