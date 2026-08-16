package com.qianrenni.common.web


import io.ktor.server.application.*
import io.ktor.server.request.*

// 2. 创建插件
val ResponseTimePlugin = createApplicationPlugin("ResponseTime") {
    // 当响应准备发送出去时,计算差值并打印
    onCallRespond { call, _ ->
        val status = call.response.status()?.value ?: "200"
        val method = call.request.httpMethod.value
        // 安全加固（M5）：仅记录 path，避免查询参数（token/验证码/邮箱等）进入日志
        val path = call.request.path()
        call.application.log.info("[{}] {} {}", status, method, path)
    }
}