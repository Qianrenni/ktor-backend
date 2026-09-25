package com.qianrenni.modules.user

import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.models.tables.FullUser
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RequestDeviceToken(
    val deviceToken: String,
) {
    init {
        require(deviceToken.isNotBlank()) { "设备凭据不能为空" }
    }
}

/** 新签发的设备凭据（手机端需放进加密存储）。 */
@Serializable
data class ResponseDeviceCredential(
    val deviceToken: String,
    val expiresIn: Long,
)

/** 设备凭据换令牌的响应，字段与密码登录保持一致，另附轮换后的新凭据。 */
@Serializable
data class ResponseDeviceLogin(
    val deviceToken: String,
    val expiresIn: Long,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val user: FullUser,
)

/**
 * 设备凭据登录路由（手机端「指纹解锁登录」的服务端支撑）。
 *
 * 时序：密码登录 → `POST /token/device` 领凭据（存 Keystore 加密存储）→
 * 下次打开 App 指纹校验通过 → `POST /token/device/login` 换新令牌（凭据同时轮换）。
 * 关闭指纹解锁 → `POST /token/device/revoke` 撤销凭据。
 */
fun Routing.deviceLogin(deviceLoginService: DeviceLoginService) {
    route("/token/device") {
        // 公开端点：按来源 IP 限流，防止暴力尝试设备凭据
        rateLimit(RateLimitName("ip")) {
            // POST /token/device/login - 用设备凭据换取令牌（凭据一次性轮换）
            post("/login") {
                val request = call.receive<RequestDeviceToken>()
                val result = deviceLoginService.login(request.deviceToken)
                call.respond(
                    ResponseModel.Success(
                        ResponseDeviceLogin(
                            deviceToken = result.credential.deviceToken,
                            expiresIn = result.credential.expiresIn,
                            accessToken = result.tokens.accessToken,
                            refreshToken = result.tokens.refreshToken,
                            tokenType = result.tokens.tokenType,
                            user = result.tokens.user,
                        )
                    )
                )
            }
        }

        // 凭据的签发与撤销都要求登录态，避免匿名用户凭空领到长期凭据
        authenticate("auth-jwt") {
            // POST /token/device - 为当前用户签发设备凭据（开启指纹解锁）
            post {
                val userId = call.getCurrentUser().id
                val credential = deviceLoginService.issue(userId)
                call.respond(
                    ResponseModel.Success(
                        ResponseDeviceCredential(
                            deviceToken = credential.deviceToken,
                            expiresIn = credential.expiresIn,
                        )
                    )
                )
            }

            // POST /token/device/revoke - 撤销设备凭据（关闭指纹解锁 / 退出登录）
            post("/revoke") {
                val request = call.receive<RequestDeviceToken>()
                deviceLoginService.revoke(request.deviceToken, call.getCurrentUser().id)
                call.respond(ResponseModel.Empty("已关闭指纹解锁"))
            }
        }
    }
}
