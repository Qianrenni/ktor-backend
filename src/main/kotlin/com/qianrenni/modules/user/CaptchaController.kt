package com.qianrenni.modules.user

import com.qianrenni.modules.user.CaptchaService
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*


fun Routing.captcha(captchaService: CaptchaService) {
    route("/captcha") {
        // 安全加固（M2）：验证码获取按来源 IP 限流，防止刷验证码
        rateLimit(RateLimitName("ip")) {
            get("/get") {
                val (captchaId, image) = captchaService.getCaptcha()
                call.response.header("x-captcha-id", captchaId)
                call.respond(image)
            }
        }
    }
}