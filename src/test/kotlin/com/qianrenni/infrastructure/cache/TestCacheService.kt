package com.qianrenni.infrastructure.cache

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.cache.cache
import com.qianrenni.testutil.withTestApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * CacheService 集成测试：缓存键生成、简单读写删、cache-aside 回源与命中、空值不缓存。
 * 依赖 Redis（db15，每个测试前 flushdb 复位）。
 */
class TestCacheService {

    @Test
    fun `generateCacheKey 相同参数生成相同 key`() = withTestApplication {
        val k1 = cacheService.generateCacheKey(args = listOf("book", 1), keyPrefix = "test")
        val k2 = cacheService.generateCacheKey(args = listOf("book", 1), keyPrefix = "test")
        assertEquals(k1, k2)
    }

    @Test
    fun `generateCacheKey 排除指定参数`() = withTestApplication {
        // 排除 index 0（如时间戳类参数）后，不同参数生成相同 key
        val k1 = cacheService.generateCacheKey(args = listOf("ts1", "fixed"), excludeArgs = listOf(0), keyPrefix = "test")
        val k2 = cacheService.generateCacheKey(args = listOf("ts2", "fixed"), excludeArgs = listOf(0), keyPrefix = "test")
        assertEquals(k1, k2)
        // 不排除则 key 不同
        assertTrue(
            cacheService.generateCacheKey(args = listOf("a"), keyPrefix = "test") !=
                cacheService.generateCacheKey(args = listOf("b"), keyPrefix = "test")
        )
    }

    @Test
    fun `cacheGetSimple 与 cacheSetSimple 读写删`() = withTestApplication {
        cacheService.cacheSetSimple("test:key1", "value1", expire = 120)
        assertEquals("value1", cacheService.cacheGetSimple("test:key1"))
        assertEquals(null, cacheService.cacheGetSimple("test:missing"))
        assertTrue(cacheService.cacheDelete("test:key1"))
        assertEquals(null, cacheService.cacheGetSimple("test:key1"))
        assertFalse(cacheService.cacheDelete("test:key1"))
    }

    @Test
    fun `cache 首次回源写缓存第二次命中`() = withTestApplication {
        val counter = AtomicInteger(0)
        suspend fun load(): String {
            counter.incrementAndGet()
            return "缓存值"
        }
        val first = cache(
            keyPrefix = "cache_test",
            args = listOf("hit"),
            serializer = String.serializer()
        ) { load() }
        assertEquals("缓存值", first)
        val second = cache(
            keyPrefix = "cache_test",
            args = listOf("hit"),
            serializer = String.serializer()
        ) { load() }
        assertEquals("缓存值", second)
        assertEquals(1, counter.get(), "第二次调用应命中缓存不再回源")
    }

    @Test
    fun `cache ignoreNull 时空列表不写入缓存`() = withTestApplication {
        val counter = AtomicInteger(0)
        val serializer = ListSerializer(String.serializer())
        suspend fun load(): List<String> {
            counter.incrementAndGet()
            return emptyList()
        }
        cache(keyPrefix = "cache_test", args = listOf("empty"), serializer = serializer) { load() }
        cache(keyPrefix = "cache_test", args = listOf("empty"), serializer = serializer) { load() }
        assertEquals(2, counter.get(), "空结果默认不缓存，两次调用都应回源")
    }

    @Test
    fun `cacheSet 手动写入后 cache 命中`() = withTestApplication {
        val counter = AtomicInteger(0)
        cacheService.cacheSet(
            value = "手动值",
            args = listOf("manual"),
            keyPrefix = "cache_test",
            serializer = String.serializer()
        )
        val result = cache(
            keyPrefix = "cache_test",
            args = listOf("manual"),
            serializer = String.serializer()
        ) {
            counter.incrementAndGet()
            "回源值"
        }
        assertEquals("手动值", result)
        assertEquals(0, counter.get(), "手动写入的缓存应被命中")
    }
}
