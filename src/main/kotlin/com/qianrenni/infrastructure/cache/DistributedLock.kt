package com.qianrenni.infrastructure.cache

import com.qianrenni.infrastructure.database.RedisManager
import com.qianrenni.infrastructure.database.redisManager
import io.ktor.server.application.*
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.util.*

/**
 * 基于 Redis 的分布式锁
 * 对应 Python 项目中的 app/utils/distribute_lock.py
 */
class DistributedLock(
    private val lockKey: String,
    private val blocking: Boolean = false,
    private val timeout: Long = 10,
    private val redisManager: RedisManager,
    private val logger: Logger,
) {
    private val lockValue = UUID.randomUUID().toString()
    private var renewLock: RenewLock? = null

    /**
     * 尝试获取锁
     */
    private suspend fun tryAcquire(): Boolean {
        val redis = redisManager.getAsyncCommands()
        // SET NX EX - 仅在键不存在时设置,并设置过期时间
        // 注意：不能用 setex（无条件覆盖），否则并发获取会互相覆盖 value，锁形同虚设
        val result = redis.set(lockKey, lockValue, SetArgs.Builder.nx().ex(timeout)).await()
        val flag = result != null
        if (flag) {
            renewLock = RenewLock(
                lockKey = lockKey,
                lockValue = lockValue,
                redisManager = redisManager,
                logger = logger,
                lockTimeout = timeout.toInt()
            )
            renewLock?.start()
        }
        return flag
    }

    /**
     * 释放锁
     */
    private suspend fun release() {
        val redis = redisManager.getAsyncCommands()
        val luaScript = """
            if redis.call("GET", KEYS[1]) == ARGV[1] then
                return redis.call("DEL", KEYS[1])
            else
                return 0
            end
        """
        redis.eval<Long>(luaScript, ScriptOutputType.INTEGER, arrayOf(lockKey), lockValue).await()
    }

    /**
     * 获取锁(带阻塞等待)
     */
    suspend fun acquire(): Boolean {
        return withContext(Dispatchers.IO) {
            var result = false
            return@withContext if (blocking) {
                while (isActive) {
                    if (tryAcquire()) {
                        result = true
                        break
                    }
                }
                result
            } else {
                tryAcquire()
            }
        }
    }

    /**
     * 释放锁
     */
    suspend fun releaseLock() {
        renewLock?.stop()
        release()
    }
}

/**
 * 便捷函数：创建分布式锁实例
 * 基础设施访问点（redisManager / logger 来自 Application 扩展），保持调用方简洁
 */
fun Application.distributedLock(
    lockKey: String,
    blocking: Boolean = true,
    timeout: Long = 10
): DistributedLock {
    return DistributedLock(lockKey, blocking, timeout, redisManager, environment.log)
}
