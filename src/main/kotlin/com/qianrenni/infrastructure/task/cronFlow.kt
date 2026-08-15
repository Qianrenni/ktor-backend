package com.qianrenni.infrastructure.task

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * 定时任务
 * @param cronExpression cron 表达式
 *  - 秒、分、时、日、月、周、年
 *  - *表示任意
 *  - ?表示可选
 *  - #表示指定某个值
 *  - L表示最后一个
 *  - W表示工作日
 *  - /表间隔
 *  - \-\表示范围
 * @param zone 时区
 * @param clock 时钟（测试可注入虚拟时钟，与 delay 使用同一时间源）
 * @throws IllegalArgumentException 如果 cron 语法错误
 */
fun cronFlow(
    cronExpression: String,
    zone: ZoneId = ZoneId.systemDefault(),
    clock: Clock = Clock.system(zone)
): Flow<ZonedDateTime> = flow {
    // 定义支持秒的 Quartz cron 类型
    val cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    val parser = CronParser(cronDefinition)
    val cron: Cron = parser.parse(cronExpression)
    val executionTime = ExecutionTime.forCron(cron)

    // 统一经 clock 取当前时刻（测试用虚拟时钟可与 delay 同步），再转换到目标时区参与 cron 计算
    fun nowInZone(): ZonedDateTime = ZonedDateTime.now(clock).withZoneSameInstant(zone)

    var nextTime = executionTime.nextExecution(nowInZone())
        .orElseThrow { IllegalArgumentException("Cron 表达式无法计算出下一次执行时间") }

    while (true) {
        val now = nowInZone()
        val delayMills = nextTime.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
        if (delayMills > 0) {
            delay(delayMills.milliseconds) // 精确等待到触发时刻
        }

        // 到达触发时间（或已错过），发射当前触发时刻
        emit(nextTime)
        // 计算下一次触发时间
        val afterNow = if (delayMills < 0) now else nextTime // 若已错过，以现在为基准避免重复发射旧时间
        nextTime = executionTime.nextExecution(afterNow)
            .orElse(null) ?: break // 无下一次则结束 Flow
    }
}