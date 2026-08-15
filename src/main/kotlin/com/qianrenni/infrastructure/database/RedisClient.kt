package com.qianrenni.infrastructure.database

import com.qianrenni.common.AppConfig
import com.qianrenni.common.appConfig
import io.ktor.server.application.*
import io.ktor.util.*
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.time.Duration

/**
 * Redis 客户端管理类
 * 对应 Python 项目中的 app/core/database.py (Redis 部分)
 */
class RedisManager(config: AppConfig) {
    private var redisClient: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    /**
     * 初始化 Redis 客户端
     */
    init {
        val uri = RedisURI.create(config.redisUrl)
        redisClient = RedisClient.create(uri)

        // 配置连接池
        redisClient?.options = ClientOptions.builder()
            .autoReconnect(true)
            .pingBeforeActivateConnection(true)
            .build()

        connection = redisClient?.connect(StringCodec.UTF8)
        connection?.timeout = Duration.ofSeconds(config.redisWaitTimeout.toLong())

    }

    /**
     * 获取异步命令接口
     */
    fun getAsyncCommands() = connection?.async()
            ?: throw IllegalStateException("Redis connection not initialized")

    /**
     * 获取同步命令接口
     */
    fun getSyncCommands() = connection?.sync()
        ?: throw IllegalStateException("Redis connection not initialized")

    /**
     * 检查连接是否活跃
     */
    fun isConnected(): Boolean {
        return connection?.isOpen == true
    }

    /**
     * 创建独立的发布订阅连接（动态配置变更通知用）。
     * 与普通命令连接相互独立，订阅期间不阻塞业务命令。
     */
    fun connectPubSub(): StatefulRedisPubSubConnection<String, String> =
        redisClient?.connectPubSub(StringCodec.UTF8)
            ?: throw IllegalStateException("Redis client not initialized")

    fun close() {
        connection?.close()
    }

}

private val RedisManagerKey = AttributeKey<RedisManager>("RedisManager")

// Application 扩展属性用于存储 Redis 管理器
val Application.redisManager: RedisManager
    get() = attributes[RedisManagerKey]


fun Application.configureRedis() {
    val redisManager = RedisManager(appConfig)
    attributes.put(RedisManagerKey, redisManager)
    // 注册关闭钩子
    monitor.subscribe(ApplicationStopped) {
        redisManager.close()
    }
}
