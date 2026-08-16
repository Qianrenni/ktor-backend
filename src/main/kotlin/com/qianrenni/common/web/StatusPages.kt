package com.qianrenni.common.web

import com.qianrenni.common.PermissionDeniedException
import com.qianrenni.common.ResponseModel
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

/**
 * 全局异常处理和状态页配置
 * 对应原项目中的 StatusPages.kt 和 Python 项目的 error_handler.py
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retryAfter = call.response.headers["Retry-After"]
            call.respondText(text = "429: Too many requests. Wait for $retryAfter seconds.", status = status)
        }
        exception<PermissionDeniedException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, message = ResponseModel.Error(message = cause.message ?: ""))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ResponseModel.Error(message = cause.message ?: "参数错误"))
        }
        exception<MissingRequestParameterException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                message = ResponseModel.Error(message = cause.message ?: "缺少参数[${cause.parameterName}]")
            )
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, message = ResponseModel.Error(message = cause.message?:"数据格式错误"))
        }
        exception<Exception> { call, cause ->
            // 安全加固（M3）：不向客户端回传内部异常信息，仅记录日志
            call.application.log.error("Unhandled exception: ", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                message = ResponseModel.Error(message = "服务器内部错误")
            )
        }
    }
}
