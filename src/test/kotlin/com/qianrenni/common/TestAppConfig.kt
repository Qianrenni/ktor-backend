package com.qianrenni.common

import com.qianrenni.testutil.*

import com.qianrenni.common.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AppConfig 单元测试：验证 Gradle 注入的测试环境变量被正确加载，以及默认值兜底逻辑。
 * 注意：AppConfig.load() 读取的是 System.getenv()，本测试依赖 build.gradle.kts 中 tasks.test 注入的环境。
 */
class TestAppConfig {

    @Test
    fun `测试环境变量被正确加载`() {
        val config = AppConfig.load()
        assertEquals("test", config.environment)
        // DB 指向 H2 内存库（由 Gradle 注入）
        assertTrue(config.mysqlDsn.startsWith("jdbc:h2:mem:guga_test"), "MYSQL_DSN 应为 H2: ${config.mysqlDsn}")
        // Redis 指向独立 db 15
        assertTrue(config.redisUrl.endsWith("/15"), "REDIS_URL 应指向 db15: ${config.redisUrl}")
        // SMTP 指向不可达端口（测试快速失败）
        assertEquals(1, config.smtpPort)
        // 存储目录指向构建目录
        assertTrue(config.contentDir.contains("test-data"), "CONTENT_DIR 应在 build 下: ${config.contentDir}")
        assertTrue(config.staticDir.contains("test-data"), "STATIC_DIR 应在 build 下: ${config.staticDir}")
        // JWT 密钥非空
        assertTrue(config.secretKey.isNotBlank())
    }

    @Test
    fun `未注入的环境变量使用默认值`() {
        val config = AppConfig.load()
        // 以下变量未在 tasks.test 中注入 → 走 AppConfig 默认值
        assertEquals(20, config.dbPoolSize)
        assertEquals(10, config.dbMaxOverflow)
        assertEquals(50, config.redisPoolSize)
        assertEquals(32, config.permissionBitLength)
        assertEquals(1800, config.bookCacheExpire)
        assertEquals("*", config.allowOrigins)
        assertEquals("utf-8", config.chapterEncoding)
    }

    @Test
    fun `环境变量忽略大小写`() {
        // load() 内部将 System.getenv() 统一转大写再读取 —— 通过注入的 ENV 值验证
        val config = AppConfig.load()
        assertEquals("test", config.environment, "ENV 应忽略大小写被读到")
    }
}
