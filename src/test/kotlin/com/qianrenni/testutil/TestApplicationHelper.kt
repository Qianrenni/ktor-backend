package com.qianrenni.testutil

import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication

/**
 * 启动测试应用（重建 H2 schema + 种子数据 + 全部服务注册），并执行测试块。
 *
 * 注意：Ktor 3.5 的 testApplication 中 `application {}` 模块惰性执行，
 * 必须先 `startApplication()` 才能访问 application 属性与服务。
 */
fun withTestApplication(block: suspend Application.() -> Unit) {
    testApplication {
        application { testConfigure() }
        startApplication()
        block(application)
    }
}
