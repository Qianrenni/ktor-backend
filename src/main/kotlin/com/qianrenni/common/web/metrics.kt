package com.qianrenni.common.web

import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

fun Application.configureMetrics() {
    // 1. 创建全局的 Prometheus 注册表实例
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // 2. 只安装这一个插件
    install(MicrometerMetrics) {
        registry = prometheusRegistry
        // 开启耗时分布统计 (可选,开启后才能看到 TP90/TP99 等耗时数据)
        distributionStatisticConfig = DistributionStatisticConfig.Builder()
            .percentiles(0.5, 0.9, 0.95, 0.99)
            .build()
        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics()
        )

    }

    routing {
        // 3. 提供一个接口,让你能直接看到收集到的耗时数据
        get("/metrics") {
            call.respondText(prometheusRegistry.scrape())
        }
    }
}