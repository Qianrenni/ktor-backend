package com.qianrenni.infrastructure.config

import com.qianrenni.common.config.ConfigDomain
import com.qianrenni.common.config.ConfigSource
import com.qianrenni.infrastructure.database.RedisManager
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.Logger

/**
 * 基于 Redis 的动态配置源。
 *
 * - [load]：`HGETALL config:{domain}`，空或不可达返回 null（默认值兜底）；
 * - [save]：`DEL + HSET config:{domain}` 后 `PUBLISH config:change {domain}`，
 *   通知其它实例失效本地缓存；
 * - [start]：订阅 `config:change` 频道，收到变更回调 [onChange]（多实例一致性）。
 */
class RedisConfigSource(
    private val redis: RedisManager,
    private val logger: Logger,
) : ConfigSource {

    /** 变更回调（装配时绑定到 [com.qianrenni.common.config.ConfigService.invalidate]） */
    @Volatile
    var onChange: ((ConfigDomain) -> Unit)? = null

    private var pubSub: StatefulRedisPubSubConnection<String, String>? = null

    override fun load(domain: ConfigDomain): Map<String, String>? {
        return try {
            if (!redis.isConnected()) return null
            val values = redis.getSyncCommands().hgetall(domain.redisKey)
            if (values.isEmpty()) null else values
        } catch (e: Exception) {
            logger.warn("读取动态配置失败({}): {}", domain.redisKey, e.message)
            null
        }
    }

    override fun save(domain: ConfigDomain, values: Map<String, String>) {
        val cmd = redis.getSyncCommands()
        cmd.del(domain.redisKey)
        if (values.isNotEmpty()) {
            cmd.hset(domain.redisKey, values)
        }
        // 发布变更通知：本实例已由 ConfigService 失效，此处主要通知其它实例
        runCatching { redis.getSyncCommands().publish(CONFIG_CHANNEL, domain.name) }
            .onFailure { logger.warn("发布配置变更通知失败: {}", it.message) }
    }

    /** 启动变更订阅（生产装配调用；Redis 不可达时容错，不阻塞启动） */
    fun start() {
        try {
            val conn = redis.connectPubSub()
            pubSub = conn
            conn.addListener(object : RedisPubSubListener<String, String> {
                override fun message(channel: String, message: String) {
                    val domain = ConfigDomain.fromName(message)
                    if (domain != null) {
                        logger.info("收到配置变更通知，失效本地缓存: {}", domain)
                        onChange?.invoke(domain)
                    }
                }

                override fun message(pattern: String, channel: String, message: String) =
                    message(channel, message)

                override fun subscribed(channel: String, count: Long) = Unit
                override fun psubscribed(pattern: String, count: Long) = Unit
                override fun unsubscribed(channel: String, count: Long) = Unit
                override fun punsubscribed(pattern: String, count: Long) = Unit
            })
            conn.async().subscribe(CONFIG_CHANNEL)
            logger.info("动态配置变更订阅已启动")
        } catch (e: Exception) {
            logger.warn("配置变更订阅启动失败（后续将回退定时拉取/默认值）: {}", e.message)
        }
    }

    fun close() {
        runCatching { pubSub?.close() }
        pubSub = null
    }

    private companion object {
        const val CONFIG_CHANNEL = "config:change"
    }
}
