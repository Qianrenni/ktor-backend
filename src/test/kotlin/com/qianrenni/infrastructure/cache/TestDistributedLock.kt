package com.qianrenni.infrastructure.cache

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.cache.distributedLock
import com.qianrenni.testutil.withTestApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DistributedLock / RenewLock 集成测试（依赖 Redis db15，每个测试前已 flushdb 复位）：
 * 互斥性、超时自动过期、看门狗续期保活、释放后可重新获取。
 *
 * 注意：曾修复生产缺陷——tryAcquire 原先用 setex（无条件覆盖，锁形同虚设），
 * 现改为 SET NX EX；`互斥` 用例即回归保护。
 */
class TestDistributedLock {

    @Test
    fun `同一 key 互斥且释放后可重新获取`() = withTestApplication {
        val lock1 = distributedLock(lockKey = "test:lock:mutex", blocking = false, timeout = 10)
        assertTrue(lock1.acquire(), "第一个锁应获取成功")

        val lock2 = distributedLock(lockKey = "test:lock:mutex", blocking = false, timeout = 10)
        assertFalse(lock2.acquire(), "锁被持有期间第二个锁应获取失败（NX 语义）")

        lock1.releaseLock()
        val lock3 = distributedLock(lockKey = "test:lock:mutex", blocking = false, timeout = 10)
        assertTrue(lock3.acquire(), "释放后应可重新获取")
        lock3.releaseLock()
    }

    @Test
    fun `锁超时自动过期`() = withTestApplication {
        val lock1 = distributedLock(lockKey = "test:lock:expire", blocking = false, timeout = 1)
        assertTrue(lock1.acquire())
        // 不释放，等待超时
        delay(1600)
        val lock2 = distributedLock(lockKey = "test:lock:expire", blocking = false, timeout = 10)
        assertTrue(lock2.acquire(), "锁超时后应可获取")
        lock2.releaseLock()
    }

    @Test
    fun `RenewLock 看门狗续期使锁在超时后仍被持有`() = withTestApplication {
        val lock1 = distributedLock(lockKey = "test:lock:renew", blocking = false, timeout = 3)
        assertTrue(lock1.acquire())
        // 超过 timeout(3s) 前：续期间隔 = timeout/3 = 1s，锁应被持续续期
        delay(2500)
        val lock2 = distributedLock(lockKey = "test:lock:renew", blocking = false, timeout = 3)
        assertFalse(lock2.acquire(), "看门狗续期后锁应仍被持有")
        lock1.releaseLock()
    }

    @Test
    fun `releaseLock 停止续期并释放锁`() = withTestApplication {
        val lock1 = distributedLock(lockKey = "test:lock:release", blocking = false, timeout = 3)
        assertTrue(lock1.acquire())
        lock1.releaseLock()
        val lock2 = distributedLock(lockKey = "test:lock:release", blocking = false, timeout = 3)
        assertTrue(lock2.acquire(), "释放后应可获取")
        lock2.releaseLock()
    }
}
