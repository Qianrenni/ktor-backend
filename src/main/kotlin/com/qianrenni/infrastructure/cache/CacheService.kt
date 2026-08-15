package com.qianrenni.infrastructure.cache

import com.qianrenni.bootstrap.services
import com.qianrenni.infrastructure.database.RedisManager
import com.qianrenni.infrastructure.cache.RenewLock
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.security.MessageDigest
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class CacheService(
    private val logger: Logger,
    private val redisManager: RedisManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun generateCacheKey(
        args: List<Any> = emptyList(),
        excludeArgs: List<Int> = emptyList(),
        keyPrefix: String
    ): String {
        val filteredArgs = args.filterIndexed { index, _ -> index !in excludeArgs }
        // 将参数转为字符串再序列化,避免 kotlinx.serialization 对 Any? 类型的限制
        val payload = filteredArgs.joinToString("") { it.toString() }

        return "$keyPrefix:${md5(payload)}"
    }

    suspend fun <T> cacheGetInternal(
        cacheKey: String,
        expire: Int,
        ignoreNull: Boolean,
        lockTimeout: Int,
        fallbackFunc: suspend () -> T,
        serializer: KSerializer<T>,
    ): T {
        val redis = redisManager.getAsyncCommands()
        // 1. 尝试读缓存
        redis.get(cacheKey).await()?.let {
            logger.debug("Cache hit: $cacheKey")
            return json.decodeFromString(serializer, it)
        }
        // 2. 尝试加锁回源
        val lockKey = "lock:$cacheKey"
        val lockValue = UUID.randomUUID().toString()
        var lockAcquired = false
        var renewLock: RenewLock? = null

        try {
            // Lettuce 设置 NX 和 EX
            val setResult = redis.setex(lockKey, lockTimeout.toLong(), lockValue).await()
            lockAcquired = setResult != null
            renewLock = RenewLock(
                lockKey = lockKey,
                lockValue = lockValue,
                redisManager = redisManager,
                logger = logger,
                lockTimeout = lockTimeout
            )
            if (lockAcquired) {
                // 启动协程进行锁续期 (Watchdog)
                renewLock.start()

                // 双重检查
                redis.get(cacheKey).await()?.let {
                    return json.decodeFromString(serializer, it)
                }

                // 执行回源逻辑
                val result = fallbackFunc()

                // 决定是否缓存
                val isEmpty = result == null ||
                        (result is Collection<*> && result.isEmpty()) ||
                        (result is Map<*, *> && result.isEmpty())

                if (!(ignoreNull && isEmpty)) {
                    val encoded = json.encodeToString(serializer, result)
                    redis.setex(cacheKey, expire.toLong(), encoded).await()
                    logger.debug("Cache set: $cacheKey (expire=${expire}s)")
                }
                return result
            } else {
                // 未获取到锁：循环等待
                val startTime = System.currentTimeMillis()
                val maxWait = (lockTimeout + 1) * 1000L

                while (System.currentTimeMillis() - startTime < maxWait) {
                    delay(50.milliseconds)
                    redis.get(cacheKey).await()?.let {
                        logger.debug("Cache filled by another worker: $cacheKey")
                        return json.decodeFromString(serializer, it)
                    }
                }

                // 超时直接回源
                logger.warn("Cache still empty after ${maxWait}ms, executing fallback directly: $cacheKey")
                return fallbackFunc()
            }
        } catch (e: Exception) {
            logger.error("Error in cacheGet fallback: $e")
            throw e
        } finally {
            renewLock?.stop() // 停止续期
            if (lockAcquired) {
                try {
                    // Lua 脚本安全释放锁
                    val luaScript = """
                        if redis.call("get", KEYS[1]) == ARGV[1] then
                            return redis.call("del", KEYS[1])
                        else
                            return 0
                        end
                    """
                    redis.eval<Long>(luaScript, ScriptOutputType.INTEGER, arrayOf(lockKey), lockValue).await()
                } catch (e: Exception) {
                    logger.warn("Failed to release lock $lockKey: $e")
                }
            }
        }
    }

    /**
     * 缓存读取便捷方法（实例方法版，供 Service 显式注入使用）。
     * 等价于 [Application.cache]，内部复用注入的 redisManager/logger。
     */
    suspend fun <T> get(
        args: List<Any>,
        expire: Int = 300,
        ignoreNull: Boolean = true,
        excludeArgs: List<Int> = emptyList(),
        keyPrefix: String = "",
        lockTimeout: Int = 30,
        serializer: KSerializer<T>,
        block: suspend () -> T
    ): T {
        return cacheGetInternal(
            expire = expire,
            ignoreNull = ignoreNull,
            lockTimeout = lockTimeout,
            fallbackFunc = block,
            cacheKey = generateCacheKey(args = args, excludeArgs = excludeArgs, keyPrefix = keyPrefix),
            serializer = serializer,
        )
    }

    /**
     * 按 key 前缀失效缓存（Redis KEYS + DEL）。
     *
     * 写接口在数据变更后显式调用，避免读到旧值（缓存失效是显式约定而非依赖 TTL 兜底）。
     * 例如书籍/章节写操作后调用 `cache.invalidate("book_service")`。
     */
    suspend fun invalidate(prefix: String) {
        try {
            val redis = redisManager.getAsyncCommands()
            val keys = redis.keys("$prefix:*").await()
            if (!keys.isNullOrEmpty()) {
                redis.del(*keys.toTypedArray()).await()
                logger.debug("Cache invalidated: {} ({} keys)", prefix, keys.size)
            }
        } catch (e: Exception) {
            logger.warn("Cache invalidate failed for prefix {}: {}", prefix, e.message)
        }
    }

    /**
     * 简单设置缓存(不带锁)
     */
    suspend fun cacheSetSimple(
        key: String,
        value: String,
        expire: Long,
    ) {
        val redis = redisManager.getAsyncCommands()
        try {
            redis.setex(key, expire, value).await()
        } catch (e: Exception) {
            logger.error("Error in cache set: $key: $e")
            throw IllegalStateException("服务器繁忙,请稍后再试")
        }
    }

    /**
     * 简单获取缓存值(字符串)
     */
    suspend fun cacheGetSimple(key: String): String? {
        val redis = redisManager.getAsyncCommands()
        return try {
            redis.get(key).await()
        } catch (e: Exception) {
            logger.error("Cache get failed: $e")
            null
        }
    }

    /**
     * 简单删除缓存
     */
    suspend fun cacheDelete(key: String): Boolean {
        val redis = redisManager.getAsyncCommands()
        return try {
            redis.del(key).await() > 0
        } catch (e: Exception) {
            logger.error("Cache delete failed: $e")
            false
        }
    }

    /**
     * 手动设置缓存
     */
    suspend fun <T> cacheSet(
        value: T,
        args: List<Any> = emptyList(),
        expire: Int = 300,
        ignoreNull: Boolean = true,
        excludeArgs: List<Int> = emptyList(),
        keyPrefix: String,
        lockTimeout: Int = 30,
        acquireLock: Boolean = true,
        serializer: KSerializer<T>,
    ): Boolean {
        val isEmpty = (value is Collection<*> && value.isEmpty()) || (value is Map<*, *> && value.isEmpty())
        if (ignoreNull && isEmpty) return false

        val redis = redisManager.getAsyncCommands()
        val cacheKey = generateCacheKey(args, excludeArgs, keyPrefix)
        val encoded = json.encodeToString(serializer, value)
        if (!acquireLock) {
            return try {
                redis.setex(cacheKey, expire.toLong(), encoded).await()
                true
            } catch (e: Exception) {
                logger.error("Manual cache set failed: $e")
                false
            }
        }

        // 带锁逻辑 (与 cacheGet 类似,此处简化展示核心逻辑)
        val lockKey = "lock:$cacheKey"
        val lockValue = UUID.randomUUID().toString()
        var lockAcquired = false
        try {
            val setResult = redis.setex(lockKey, lockTimeout.toLong(), lockValue).await()
            lockAcquired = setResult != null
            if (!lockAcquired) return false
            // 双重检查
            if (redis.get(cacheKey).await() != null) return false
            redis.setex(cacheKey, expire.toLong(), encoded).await()
            return true
        } catch (e: Exception) {
            logger.error("Manual cache set with lock failed: $e")
            return false
        } finally {
            if (lockAcquired) {
                try {
                    val luaScript =
                        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
                    redis.eval<Long>(luaScript, ScriptOutputType.INTEGER, arrayOf(lockKey), lockValue).await()
                } catch (e: Exception) {
                    logger.warn("Failed to release lock in cacheSet: $e")
                }
            }
        }
    }
}


/**
 * 在 Ktor 的 Application 或 Routing 中直接使用,语法类似 Python 的装饰器
 * 通过组合根 [Application.services] 获取 CacheService，不依赖散落的扩展属性。
 */
suspend fun <T> Application.cache(
    args: List<Any>,
    expire: Int = 300,
    ignoreNull: Boolean = true,
    excludeArgs: List<Int> = emptyList(),
    keyPrefix: String = "",
    lockTimeout: Int = 30,
    serializer: KSerializer<T>,
    block: suspend () -> T
): T {
    return this.services.cacheService.cacheGetInternal(
        expire = expire,
        ignoreNull = ignoreNull,
        lockTimeout = lockTimeout,
        fallbackFunc = block,
        cacheKey = this.services.cacheService.generateCacheKey(args = args, excludeArgs = excludeArgs, keyPrefix = keyPrefix),
        serializer = serializer,
    )
}

suspend fun <T> RoutingContext.cache(
    args: List<Any>,
    expire: Int = 300,
    ignoreNull: Boolean = true,
    excludeArgs: List<Int> = emptyList(),
    keyPrefix: String = "",
    lockTimeout: Int = 30,
    serializer: KSerializer<T>,
    block: suspend () -> T
): T {
    return call.application.services.cacheService.cacheGetInternal(
        expire = expire,
        ignoreNull = ignoreNull,
        lockTimeout = lockTimeout,
        fallbackFunc = block,
        cacheKey = call.application.services.cacheService.generateCacheKey(
            args = args,
            excludeArgs = excludeArgs,
            keyPrefix = keyPrefix
        ),
        serializer = serializer,
    )
}