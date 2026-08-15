package com.qianrenni.infrastructure.cache

import com.qianrenni.infrastructure.database.RedisManager
import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.slf4j.Logger
import kotlin.time.Duration.Companion.seconds


class RenewLock(
    private val lockKey: String,
    private val lockValue: String,
    private val lockTimeout: Int,
    private val redisManager: RedisManager,
    private val logger: Logger,
    renewInterval: Long? = null,
) {
    private val interval = renewInterval ?: (lockTimeout / 3).toLong().coerceAtLeast(1)
    private var renewJob: Job? = null

    /**
     * 启动自动续期
     */
    fun start(): Job {
        renewJob = CoroutineScope(Dispatchers.IO).launch {
            val redis = redisManager.getAsyncCommands()
            while (isActive) {
                try {
                    val luaScript = """
                        if redis.call("get", KEYS[1]) == ARGV[1] then
                            return redis.call("expire", KEYS[1], ARGV[2])
                        else
                            return 0
                        end
                    """
                    val result = redis.eval<Long>(
                        luaScript,
                        ScriptOutputType.INTEGER,
                        arrayOf(lockKey),
                        lockValue,
                        lockTimeout.toString()
                    ).await()

                    if (result == 0L) {
                        logger.debug("Lock $lockKey is no longer held (deleted or stolen). Stopping renewal.")
                        break
                    }
                    logger.debug("Lock renewed: $lockKey")
                } catch (e: CancellationException) {
                    logger.debug("Lock renewal cancelled for $lockKey")
                    break
                } catch (e: Exception) {
                    logger.warn("Failed to renew lock $lockKey: ${e.message}")
                    break
                }
                delay(interval.seconds)
            }
        }
        return renewJob!!
    }

    /**
     * 停止自动续期
     */
    suspend fun stop() {
        renewJob?.let { job ->
            if (job.isActive) {
                job.cancel()
                logger.debug("Canceling lock renewal task for $lockKey")
                try {
                    job.join()
                } catch (e: CancellationException) {
                    // Expected
                } catch (e: Exception) {
                    logger.warn("Error while cancelling lock renewal: ${e.message}")
                }
            }
        }
    }
}
