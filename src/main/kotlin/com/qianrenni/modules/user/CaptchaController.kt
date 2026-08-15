package com.qianrenni.modules.user

import com.qianrenni.modules.user.CaptchaService
import io.ktor.server.response.*
import io.ktor.server.routing.*


fun Routing.captcha(captchaService: CaptchaService) {
    route("/captcha") {
        get("/get") {
            val (captchaId, image) = captchaService.getCaptcha()
            call.response.header("x-captcha-id", captchaId)
            call.respond(image)
        }
    }
}