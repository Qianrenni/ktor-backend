package com.qianrenni.modules.user

import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.getCurrentUser
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RequestQrCreate(
    /** 网页端描述（浏览器 UA 摘要等），App 确认框展示用，可空 */
    val client: String? = null,
)

@Serializable
data class RequestQrScan(
    val qrId: String,
) {
    init {
        require(qrId.isNotBlank(), { "qrId 不能为空" })
    }
}

@Serializable
data class RequestQrConfirm(
    val qrId: String,
) {
    init {
        require(qrId.isNotBlank(), { "qrId 不能为空" })
    }
}

@Serializable
data class RequestQrCancel(
    val qrId: String,
) {
    init {
        require(qrId.isNotBlank(), { "qrId 不能为空" })
    }
}

@Serializable
data class RequestQrExchange(
    val confirmToken: String,
) {
    init {
        require(confirmToken.isNotBlank(), { "confirmToken 不能为空" })
    }
}

@Serializable
data class ResponseQrCreate(
    val qrId: String,
    val expireSeconds: Int,
)

@Serializable
data class ResponseQrStatus(
    val status: QrLoginStatus,
    /** 网页端描述，随状态透传 */
    val client: String? = null,
    /** 仅 CONFIRMED 时返回的一次性兑换凭证 */
    val confirmToken: String? = null,
)

@Serializable
data class ResponseQrScan(
    /** 网页端描述，App 确认框展示用 */
    val client: String? = null,
)

/**
 * 扫码登录路由。
 *
 * 公开端点（create/status/exchange）按来源 IP 限流；
 * App 端点（scan/confirm/cancel）要求 JWT 且按用户维度限流。
 */
fun Routing.qrLogin(qrLoginService: QrLoginService) {
    route("/token/qr") {
        rateLimit(RateLimitName("ip")) {
            // POST /token/qr/create - 网页端创建二维码票据
            post("/create") {
                val request = call.receive<RequestQrCreate>()
                call.respond(ResponseModel.Success(qrLoginService.createQr(request.client)))
            }

            // GET /token/qr/status?qrId= - 网页端轮询二维码状态
            get("/status") {
                val qrId = call.requireQueryParameter("qrId")
                call.respond(ResponseModel.Success(qrLoginService.getStatus(qrId)))
            }

            // POST /token/qr/exchange - 网页端用一次性 confirmToken 兑换正式令牌
            post("/exchange") {
                val request = call.receive<RequestQrExchange>()
                call.respond(ResponseModel.Success(qrLoginService.exchange(request.confirmToken)))
            }
        }

        authenticate("auth-jwt") {
            rateLimit(RateLimitName("protected")) {
                // POST /token/qr/scan - App 扫码
                post("/scan") {
                    val request = call.receive<RequestQrScan>()
                    call.respond(ResponseModel.Success(qrLoginService.scan(request.qrId)))
                }

                // POST /token/qr/confirm - App 确认登录
                post("/confirm") {
                    val request = call.receive<RequestQrConfirm>()
                    qrLoginService.confirm(call.getCurrentUser(), request.qrId)
                    call.respond(ResponseModel.Empty("登录成功，请在网页端查看"))
                }

                // POST /token/qr/cancel - App 取消登录
                post("/cancel") {
                    val request = call.receive<RequestQrCancel>()
                    qrLoginService.cancel(request.qrId)
                    call.respond(ResponseModel.Empty("已取消本次登录"))
                }
            }
        }
    }
}
