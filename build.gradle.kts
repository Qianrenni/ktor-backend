
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.qianrenni"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "com.qianrenni.bootstrap.ApplicationKt"
}
kotlin {
    jvmToolchain(21)
}
dependencies {
    // Ktor 核心和插件
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.cachingHeaders)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.conditionalHeaders)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.csrf)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.partialContent)
    implementation(ktorLibs.server.resources)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.rateLimit)
    implementation(libs.logback.classic)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.ucasoft.ktorSimpleCache)
    implementation(libs.ucasoft.ktorSimpleMemoryCache)
    implementation(ktorLibs.server.contentNegotiation)
    // 数据库相关 - Exposed ORM
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector)

    // Redis 客户端
    implementation(libs.lettuce.core)

    // JWT 认证
    implementation(libs.jwt.api)

    // 密码哈希 (Bcrypt)
    implementation(libs.bcrypt)
    // 邮件发送
    implementation("com.sun.mail:jakarta.mail:2.0.2")
    implementation("pro.fessional:kaptcha:2.3.3")
    // Ktor Micrometer 插件
    implementation(ktorLibs.server.metrics.micrometer)
    // Micrometer Prometheus 注册表 (用于暴露 /metrics 接口给 Prometheus 抓取)
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.2")
    // OSHI 系统信息库（替代 Python psutil）
    implementation("com.github.oshi:oshi-core:6.6.5")
    // msgpack 序列化
    implementation("org.msgpack:msgpack-core:0.9.8")
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("com.cronutils:cron-utils:9.2.0")
    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    // 内存数据库（测试用，替代 MySQL）
    testImplementation("com.h2database:h2:2.3.232")
}

/**
 * 读取 .env 文件（key=value 行，支持 # 注释与引号）
 */
fun loadDotEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val map = mutableMapOf<String, String>()
    file.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
        val idx = trimmed.indexOf('=')
        if (idx > 0) {
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim().trim('"')
            map[key] = value
        }
    }
    return map
}

tasks.test {
    // 测试环境变量：不读取系统环境，统一由本任务注入，保证可复现
    val dotEnv = loadDotEnv(file(".env"))
    // H2 内存库（MySQL 兼容模式）：DATABASE_TO_LOWER 统一标识符大小写匹配。
    // 不需要 NON_KEYWORDS：Exposed 0.59 对关键字（user/order 等）自动生成带引号标识符（"user"/"order"），
    // 而 H2 引号标识符允许使用关键字；若把 ORDER 加入 NON_KEYWORDS 反而会破坏生产代码的 ORDER BY 语法。
    environment("MYSQL_DSN", "jdbc:h2:mem:guga_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
    // Redis 使用远程开发服务器 db 15 独立逻辑库，避免污染生产数据（RedisManager 懒连接，不可达时仅缓存类测试失败）
    environment("REDIS_URL", dotEnv["REDIS_URL"]?.replace(Regex("/\\d+$"), "/15") ?: "redis://localhost:6379/15")
    // 存储目录使用构建目录，避免触碰真实数据
    environment("CONTENT_DIR", layout.buildDirectory.dir("test-data/store").get().asFile.absolutePath)
    environment("STATIC_DIR", layout.buildDirectory.dir("test-data/static").get().asFile.absolutePath)
    // SMTP 指向不可达端口，邮件发送立即失败（测试只验证失败路径）
    environment("SMTP_SERVER", "127.0.0.1")
    environment("SMTP_PORT", "1")
    environment("ENV", "test")
    environment("SECRET_KEY", dotEnv["SECRET_KEY"] ?: "test-secret-key-0123456789abcdef")
    environment("SERVER_URL", dotEnv["SERVER_URL"] ?: "http://localhost:8000")
    environment("ALLOW_HOST", "localhost")
    environment("AUDIENCE", "guga-audience")
    environment("ISSUER", "guga-issuer")
    // 权限位长度：84 个权限需要 2 段（32 位一段），保持与生产一致的默认 32
    environment("PERMISSION_BIT_LENGTH", "32")
    // 单测与集成测试不并行（共享 H2 内存库，每次 testApplication 重建 schema）
    maxParallelForks = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
