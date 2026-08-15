package com.qianrenni.common.config

import com.qianrenni.testutil.InMemoryConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 动态配置服务测试：默认值兜底、本地缓存、按域失效、更新校验与部分字段合并。
 */
class TestConfigService {

    @Test
    fun `无配置时返回默认值兜底`() {
        val service = ConfigService(InMemoryConfigSource())
        assertEquals(CompactConfig.DEFAULT, service.compact())
        assertEquals(RateLimitConfig.DEFAULT, service.rateLimit())
        assertEquals(CacheConfig.DEFAULT, service.cache())
    }

    @Test
    fun `读取 Redis 值并本地缓存`() {
        val source = InMemoryConfigSource(
            mapOf(
                ConfigDomain.COMPACT to mapOf(
                    "garbageThreshold" to "0.5",
                    "minLiveBytes" to "1024",
                    "maxFileBytes" to "1048576"
                )
            )
        )
        val service = ConfigService(source)
        assertEquals(CompactConfig(0.5, 1024, 1048576), service.compact())

        // 修改源：缓存命中，仍返回首次读取的旧值（不回源）
        source.save(
            ConfigDomain.COMPACT,
            mapOf("garbageThreshold" to "0.9", "minLiveBytes" to "1", "maxFileBytes" to "2")
        )
        assertEquals(CompactConfig(0.5, 1024, 1048576), service.compact())
    }

    @Test
    fun `invalidate 后重新回源取最新`() {
        val source = InMemoryConfigSource(
            mapOf(
                ConfigDomain.COMPACT to mapOf(
                    "garbageThreshold" to "0.5", "minLiveBytes" to "1", "maxFileBytes" to "2"
                )
            )
        )
        val service = ConfigService(source)
        assertEquals(0.5, service.compact().garbageThreshold)

        service.invalidate(ConfigDomain.COMPACT)
        source.save(
            ConfigDomain.COMPACT,
            mapOf("garbageThreshold" to "0.9", "minLiveBytes" to "10", "maxFileBytes" to "20")
        )
        assertEquals(CompactConfig(0.9, 10, 20), service.compact())
    }

    @Test
    fun `update 校验非法值拒绝写入`() {
        val source = InMemoryConfigSource()
        val service = ConfigService(source)

        // garbageThreshold 超出 [0,1] 范围 → 校验失败
        assertFalse(
            service.update(
                ConfigDomain.COMPACT,
                mapOf("garbageThreshold" to "2.0", "minLiveBytes" to "1", "maxFileBytes" to "2")
            )
        )
        assertNull(source.load(ConfigDomain.COMPACT), "非法配置不应写入")
    }

    @Test
    fun `update 部分字段合并写入`() {
        val source = InMemoryConfigSource(
            mapOf(
                ConfigDomain.COMPACT to mapOf(
                    "garbageThreshold" to "0.4",
                    "minLiveBytes" to "65536",
                    "maxFileBytes" to "1048576"
                )
            )
        )
        val service = ConfigService(source)

        assertTrue(service.update(ConfigDomain.COMPACT, mapOf("garbageThreshold" to "0.6")))

        val saved = source.load(ConfigDomain.COMPACT)!!
        assertEquals("0.6", saved["garbageThreshold"])
        assertEquals("65536", saved["minLiveBytes"], "未提供的字段沿用当前生效值")
    }

    @Test
    fun `解析失败回退默认值且不缓存`() {
        val source = InMemoryConfigSource(
            mapOf(
                ConfigDomain.COMPACT to mapOf(
                    "garbageThreshold" to "bad", "minLiveBytes" to "1", "maxFileBytes" to "2"
                )
            )
        )
        val service = ConfigService(source)
        assertEquals(CompactConfig.DEFAULT, service.compact())

        // 非法值不应被缓存：源恢复合法值后，下次读取应取到最新
        source.save(
            ConfigDomain.COMPACT,
            mapOf("garbageThreshold" to "0.7", "minLiveBytes" to "1", "maxFileBytes" to "2")
        )
        assertEquals(0.7, service.compact().garbageThreshold)
    }

    @Test
    fun `currentValues 无配置时返回默认值`() {
        val service = ConfigService(InMemoryConfigSource())
        assertEquals("0.4", service.currentValues(ConfigDomain.COMPACT)["garbageThreshold"])
    }
}
