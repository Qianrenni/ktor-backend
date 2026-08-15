package com.qianrenni.infrastructure.database

import com.qianrenni.common.AppConfig
import com.qianrenni.common.appConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 数据库配置类
 * 对应 Python 项目中的 app/core/database.py
 */
class DatabaseManager(private val config: AppConfig) {
    private var dataSource: HikariDataSource? = null
    private var database: Database? = null

    /**
     * 初始化数据库连接池
     */
    init {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.mysqlDsn
            maximumPoolSize = config.dbPoolSize
            minimumIdle = maxOf(1, config.dbPoolSize / 4)
            maxLifetime = config.dbPoolRecycle * 1000L // 转换为毫秒
            connectionTimeout = 30000
            idleTimeout = 600000
            isAutoCommit = false
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            addDataSourceProperty("useLocalSessionState", "true")
            addDataSourceProperty("rewriteBatchedStatements", "true")
            addDataSourceProperty("cacheResultSetMetadata", "true")
            addDataSourceProperty("cacheServerConfiguration", "true")
            addDataSourceProperty("elideSetAutoCommits", "true")
            addDataSourceProperty("maintainTimeStats", "false")
        }

        dataSource = HikariDataSource(hikariConfig)
        database = dataSource?.let { Database.connect(it) }
    }

    /**
     * 获取 Exposed Database 实例
     */
    fun getDatabase(): Database {
        return database ?: throw IllegalStateException("Database not initialized")
    }

    /**
     * 执行事务
     */
    fun <T> transaction(block: () -> T): T {
        return transaction(getDatabase()) {
            if (config.environment == "dev") {
                addLogger(StdOutSqlLogger)
            }
            block()
        }
    }

    suspend fun <T> suspendedTransaction(readOnly: Boolean = false, block: suspend () -> T): T {
        return newSuspendedTransaction(db = getDatabase(), context = Dispatchers.IO, readOnly = readOnly) {
            if (config.environment == "dev") {
                addLogger(StdOutSqlLogger)
            }
            block()
        }
    }

    /**
     * 关闭数据库连接池
     */
    fun close() {
        dataSource?.close()
    }
}

private val DatabaseManagerKey = AttributeKey<DatabaseManager>("DatabaseManager")

// Application 扩展属性用于存储数据库管理器
val Application.databaseManager: DatabaseManager
    get() = attributes[DatabaseManagerKey]

fun Application.configureDatabase() {
    val dbManager = DatabaseManager(appConfig)
    attributes.put(DatabaseManagerKey, dbManager)
    // 注册关闭钩子
    monitor.subscribe(ApplicationStopped) {
        dbManager.close()
    }
}
