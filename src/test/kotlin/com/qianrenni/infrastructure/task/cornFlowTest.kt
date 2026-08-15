package com.qianrenni.infrastructure.task

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.task.cronFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 基于 runTest 虚拟调度器的 java.time.Clock，与 delay 使用同一时间源，
 * 使 cronFlow 的时间计算与测试推进完全同步（替代已被 coroutines 1.11 移除的 TestClock）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class VirtualClock(private val scheduler: TestCoroutineScheduler) : Clock() {
    // 实测：TestCoroutineScheduler.currentTime 单位为毫秒（advanceTimeBy(1s) 后 currentTime=1000）
    override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    // 测试场景固定使用 UTC，无需切换时区
    override fun withZone(zone: ZoneId): Clock = this
}

class CronFlowTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cronFlow with every-second cron should emit at least once`() = runTest {
        // 注入与 delay 同源的虚拟时钟，避免真实时钟导致定时漂移
        val flow = cronFlow("* * * * * ?", clock = VirtualClock(testScheduler))
        val emitted = mutableListOf<ZonedDateTime>()
        val collectJob = launch {
            flow.collect { emitted.add(it) }
        }
        advanceTimeBy(2000.milliseconds)
        collectJob.cancel()
        assertTrue(emitted.isNotEmpty(), "Should emit at least one time")
    }

    @Test
    fun `cronFlow with invalid expression should throw IllegalArgumentException`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            cronFlow("invalid-cron").first()
        }
    }

    @Test
    fun `cronFlow should support cancellation`() = runTest {
        val collectJob = launch {
            cronFlow("* * * * * ?").collect()
        }
        yield()
        collectJob.cancelAndJoin()
        assertTrue(collectJob.isCancelled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cronFlow with custom timezone should use correct zone`() = runTest {
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        // 虚拟时钟与 delay 同源：advanceTimeBy 推进时发射行为完全确定
        val flow = cronFlow("* * * * * ?", tokyoZone, VirtualClock(testScheduler))
        val emitted = mutableListOf<ZonedDateTime>()
        val collectJob = launch {
            flow.collect { emitted.add(it) }
        }
        // 推进 2000ms：advanceTimeBy 不运行“恰好到期”的任务（delay 到期时刻等于推进量时不触发），
        // 需保证推进量严格大于首个 delay（1000ms）
        advanceTimeBy(2000.milliseconds)
        collectJob.cancel()
        assertTrue(emitted.isNotEmpty())
        assertEquals(tokyoZone, emitted.first().zone)
    }
}
