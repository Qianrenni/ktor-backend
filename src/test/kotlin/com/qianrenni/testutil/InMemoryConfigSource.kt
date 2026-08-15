package com.qianrenni.testutil

import com.qianrenni.common.config.ConfigDomain
import com.qianrenni.common.config.ConfigSource
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版动态配置源（测试用）。
 * 可预置初始值；[load] 返回当前值或 null，[save] 覆盖写入。
 */
class InMemoryConfigSource(
    initial: Map<ConfigDomain, Map<String, String>> = emptyMap(),
) : ConfigSource {

    private val store = ConcurrentHashMap(initial)

    override fun load(domain: ConfigDomain): Map<String, String>? = store[domain]

    override fun save(domain: ConfigDomain, values: Map<String, String>) {
        store[domain] = values
    }
}
