package com.qianrenni.common

import io.ktor.server.application.*
import io.ktor.util.*

/**
 * 应用配置管理类
 * 直接通过环境变量加载，忽略大小写，支持默认值
 */
data class AppConfig(
    // 运行环境
    val environment: String ,
    val allowHost: String ,
    // 数据库配置
    val mysqlDsn: String ,
    val dbPoolSize: Int ,
    val dbMaxOverflow: Int ,
    val dbPoolRecycle: Int ,

    // Redis 配置
    val redisUrl: String ,
    val redisPoolSize: Int ,
    val redisWaitTimeout: Int ,

    // JWT 配置
    val secretKey: String ,
    val audience: String ,
    val issuer: String ,
    val accessTokenExpire: Int ,
    val refreshTokenExpire: Int ,
    val emailVerifyExpire: Int ,
    val captchaExpire: Int ,
    val permissionBitLength: Int ,

    // 缓存配置
    val bookCacheExpire: Int ,
    val permissionCacheExpire: Int ,

    // 限流配置
    val ipLimitEnable: Boolean ,
    val ipLimitWindow: Int ,
    val ipLimitCount: Int ,

    // 存储配置
    val bookShardCount: Int ,
    val staticDir: String ,
    val contentDir: String ,
    val chapterEncoding: String ,

    // 内容存储自动 compact 配置
    val contentStoreCompactEnable: Boolean ,
    val contentStoreCompactCron: String ,
    val contentStoreCompactGarbageThreshold: Double ,
    val contentStoreCompactMinLiveBytes: Long ,
    val contentStoreCompactMaxFileBytes: Long ,

    // 服务器配置
    val serverUrl: String ,

    // 邮箱配置
    val smtpServer: String ,
    val smtpPort: Int ,
    val emailAccount: String ,
    val emailCode: String ,

    // 跨域配置
    val allowOrigins: String = "*"
) {
    companion object {
        /** 忽略大小写的环境变量映射 */
        private val envMap: Map<String, String> =
            System.getenv().mapKeys { it.key.uppercase() }

        private fun env(key: String): String? = envMap[key.uppercase()]

        fun load(): AppConfig {
            fun String?.toIntOrDefault(default: Int) = this?.toIntOrNull() ?: default

            return AppConfig(
                environment = env("ENV") ?: "dev",
                allowHost = env("ALLOW_HOST") ?: "localhost",
                mysqlDsn = env("MYSQL_DSN") ?: "",
                dbPoolSize = env("DB_POOL_SIZE").toIntOrDefault(20),
                dbMaxOverflow = env("DB_MAX_OVERFLOW").toIntOrDefault(10),
                dbPoolRecycle = env("DB_POOL_RECYCLE").toIntOrDefault(3600),
                redisUrl = env("REDIS_URL") ?: "",
                redisPoolSize = env("REDIS_POOL_SIZE").toIntOrDefault(50),
                redisWaitTimeout = env("REDIS_WAIT_TIMEOUT").toIntOrDefault(3),
                secretKey = env("SECRET_KEY") ?: "",
                audience = env("AUDIENCE") ?: "",
                issuer = env("ISSUER") ?: "",
                accessTokenExpire = env("ACCESS_TOKEN_EXPIRE").toIntOrDefault(30),
                refreshTokenExpire = env("REFRESH_TOKEN_EXPIRE").toIntOrDefault(60 * 30),
                emailVerifyExpire = env("EMAIL_VERIFY_EXPIRE").toIntOrDefault(300),
                captchaExpire = env("CAPTCHA_EXPIRE").toIntOrDefault(120),
                permissionBitLength = env("PERMISSION_BIT_LENGTH").toIntOrDefault(32),
                bookCacheExpire = env("BOOK_CACHE_EXPIRE").toIntOrDefault(1800),
                permissionCacheExpire = env("PERMISSION_CACHE_EXPIRE").toIntOrDefault(604800),
                ipLimitEnable = env("IP_LIMIT_ENABLE")?.toBooleanStrictOrNull() ?: true,
                ipLimitWindow = env("IP_LIMIT_WINDOW").toIntOrDefault(60),
                ipLimitCount = env("IP_LIMIT_COUNT").toIntOrDefault(30),
                bookShardCount = env("BOOK_SHARD_COUNT").toIntOrDefault(64),
                staticDir = env("STATIC_DIR") ?: "static",
                contentDir = env("CONTENT_DIR") ?: "store",
                chapterEncoding = env("CHAPTER_ENCODING") ?: "utf-8",
                contentStoreCompactEnable = env("CONTENT_STORE_COMPACT_ENABLE")?.toBooleanStrictOrNull() ?: true,
                contentStoreCompactCron = env("CONTENT_STORE_COMPACT_CRON") ?: "0 30 4 * * ?",
                contentStoreCompactGarbageThreshold = env("CONTENT_STORE_COMPACT_GARBAGE_THRESHOLD")?.toDoubleOrNull() ?: 0.4,
                contentStoreCompactMinLiveBytes = env("CONTENT_STORE_COMPACT_MIN_LIVE_BYTES")?.toLongOrNull() ?: (64 * 1024).toLong(),
                contentStoreCompactMaxFileBytes = env("CONTENT_STORE_COMPACT_MAX_FILE_BYTES")?.toLongOrNull() ?: (512L * 1024 * 1024),
                serverUrl = env("SERVER_URL") ?: "http://localhost:8080",
                smtpServer = env("SMTP_SERVER") ?: "smtp.qq.com",
                smtpPort = env("SMTP_PORT").toIntOrDefault(465),
                emailAccount = env("EMAIL_ACCOUNT") ?: "",
                emailCode = env("EMAIL_CODE") ?: "",
                allowOrigins = env("ALLOW_ORIGINS") ?: "*"
            )
        }
    }
}

private val AppConfigKey = AttributeKey<AppConfig>("AppConfig")

val Application.appConfig: AppConfig
    get() = attributes[AppConfigKey]

fun Application.loadConfig() {
    val appConfig = AppConfig.load()
    attributes.put(AppConfigKey, appConfig)
    log.info(appConfig.toString())
}
