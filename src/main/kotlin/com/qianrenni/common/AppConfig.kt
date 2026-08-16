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
                // 安全加固（M1）：默认 fail-closed 为 prod，避免生产漏配 ENV 时暴露 /test 路由与 SQL 日志
                environment = env("ENV") ?: "prod",
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
    // 安全加固（H1）：禁止打印完整 data class（含 mysqlDsn/redisUrl/secretKey/emailCode），改为脱敏输出
    log.info(
        "AppConfig loaded: environment={}, allowHost={}, mysqlDsn={}, redisUrl={}, secretKey={}, " +
            "smtpServer={}, emailAccount={}, emailCode={}",
        appConfig.environment,
        appConfig.allowHost,
        maskDsn(appConfig.mysqlDsn),
        maskUrl(appConfig.redisUrl),
        if (appConfig.secretKey.isBlank()) "<blank>" else "<set:${appConfig.secretKey.length} chars>",
        appConfig.smtpServer,
        appConfig.emailAccount,
        if (appConfig.emailCode.isBlank()) "<blank>" else "<set>"
    )
}

/** 数据库 DSN 脱敏：隐藏 userinfo 与 password 参数 */
private fun maskDsn(dsn: String): String {
    if (dsn.isBlank()) return ""
    var s = dsn
    val schemeEnd = s.indexOf("://")
    if (schemeEnd > 0) {
        val at = s.indexOf('@', schemeEnd + 3)
        if (at > 0) s = s.substring(0, schemeEnd + 3) + "***@" + s.substring(at + 1)
    }
    return s.replace(Regex("(?i)(password=)[^;&]*"), "$1***")
}

/** URL（Redis 等）脱敏：隐藏 userinfo 中的密码 */
private fun maskUrl(url: String): String {
    if (url.isBlank()) return ""
    val schemeEnd = url.indexOf("://")
    if (schemeEnd > 0) {
        val at = url.indexOf('@', schemeEnd + 3)
        if (at > 0) return url.substring(0, schemeEnd + 3) + "***@" + url.substring(at + 1)
    }
    return url
}
