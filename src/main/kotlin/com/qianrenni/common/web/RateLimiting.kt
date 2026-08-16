package com.qianrenni.common.web

import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimiting() {
    install(RateLimit) {
        // 已认证接口：按用户维度限流
        register(RateLimitName("protected")) {
            rateLimiter(limit = 30, refillPeriod = 60.seconds)
            requestKey { applicationCall ->
                applicationCall.getCurrentUser().id
            }
        }
        // 安全加固（M2）：公开/未认证接口按来源 IP 限流（登录、注册、验证码、邮箱、忘记密码等）
        // 注意：反向代理后需配合 ForwardedHeaders 才能取到真实客户端 IP
        register(RateLimitName("ip")) {
            rateLimiter(limit = 60, refillPeriod = 60.seconds)
            requestKey { applicationCall ->
                applicationCall.request.origin.remoteHost
            }
        }
    }
}
