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
data class RequestQrLoginTicket(
    val ticket: String,
) {
    init {
        require(ticket.isNotBlank()) { "二维码票据不能为空" }
    }
}

@Serializable
data class RequestQrLoginAction(
    val ticket: String,
    val action: QrLoginAction,
) {
    init {
        require(ticket.isNotBlank()) { "二维码票据不能为空" }
    }
}

/** 新建票据的响应：[qrContent] 直接印进二维码，[ticket] 用于网页端轮询。 */
@Serializable
data class ResponseQrLoginTicket(
    val ticket: String,
    val qrContent: String,
    val expiresIn: Long,
)

@Serializable
data class ResponseQrLoginStatus(
    val status: QrLoginStatus,
)

/**
 * 扫码登录路由（手机端扫码 → 网页端自动登录）。
 *
 * 典型时序：
 * 1. 网页端 `POST /token/qr/create` 取票据并渲染二维码，随后轮询 `GET /token/qr/status`；
 * 2. 手机端扫码后调 `POST /token/qr/action`（action=scan）把自己登记为待确认；
 * 3. 用户在手机上确认，手机端再调 `POST /token/qr/action`（action=confirm）；
 * 4. 网页端轮询到 confirmed 后调 `POST /token/qr/exchange` 换取令牌（一次性）。
 */
fun Routing.qrLogin(qrLoginService: QrLoginService) {
    route("/token/qr") {
        // 安全加固：公开端点按来源 IP 限流，避免票据被刷或暴力枚举。
        // 轮询频率远高于其他公开接口，因此复用宽松的 "qr-login" 配额
        rateLimit(RateLimitName("qr-login")) {
            // POST /token/qr/create - 生成登录二维码票据
            post("/create") {
                val ticket = qrLoginService.createSession()
                call.respond(
                    ResponseModel.Success(
                        ResponseQrLoginTicket(
                            ticket = ticket.ticket,
                            qrContent = ticket.qrContent,
                            expiresIn = qrLoginService.expireSeconds,
                        )
                    )
                )
            }

            // GET /token/qr/status - 网页端轮询票据状态
            get("/status") {
                val ticket = call.requireQueryParameter("ticket")
                val status = qrLoginService.getStatus(ticket)
                    ?: throw IllegalArgumentException("二维码已过期，请刷新后重试")
                call.respond(ResponseModel.Success(ResponseQrLoginStatus(status)))
            }

            // POST /token/qr/exchange - 用已确认的票据兑换令牌（一次性，兑换后票据失效）
            post("/exchange") {
                val request = call.receive<RequestQrLoginTicket>()
                call.respond(ResponseModel.Success(qrLoginService.exchange(request.ticket)))
            }
        }

        // 手机端动作需要登录态，确认时才知道「是谁在授权这次登录」
        authenticate("auth-jwt") {
            // POST /token/qr/action - 手机端扫描 / 确认 / 取消
            post("/action") {
                val request = call.receive<RequestQrLoginAction>()
                val userId = call.getCurrentUser().id
                val status = when (request.action) {
                    QrLoginAction.SCAN -> qrLoginService.scan(request.ticket, userId)
                    QrLoginAction.CONFIRM -> qrLoginService.confirm(request.ticket, userId)
                    QrLoginAction.CANCEL -> qrLoginService.cancel(request.ticket, userId)
                }
                call.respond(ResponseModel.Success(ResponseQrLoginStatus(status)))
            }
        }
    }
}
