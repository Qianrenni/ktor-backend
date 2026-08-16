package com.qianrenni.modules.user

import com.auth0.jwt.JWT
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.mail.EmailService
import com.auth0.jwt.algorithms.Algorithm
import com.qianrenni.common.appConfig
import com.qianrenni.models.tables.FullUser
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.user.UserService
import com.qianrenni.common.util.TokenGenerator
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class RequestTokenGet(
    @SerialName("username")val userName: String,
    val password: String,
    val captcha: String
) {
    init {
        require(userName.isNotBlank(), { "用户名不能为空" })
        require(password.isNotBlank(), { "密码不能为空" })
        require(captcha.isNotBlank(), { "验证码不能为空" })
    }
}

@Serializable
data class ResponseTokenData(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val user: FullUser,
)

@Serializable
data class RequestVerifyEmail(
     val email: String
) {
    init {
        require(email.isNotBlank(), { "邮箱不能为空" })
    }
}

fun Routing.auth(
    userService: UserService,
    cacheService: CacheService,
    emailService: EmailService,
) {
    // 安全加固（M2）：/token 全组按来源 IP 限流（登录/刷新/邮箱验证等公开端点）
    rateLimit(RateLimitName("ip")) {
        route("/token") {
        // POST /token/get - 登录获取access_token和refresh_token
        post("/get") {
            val xCaptchaId = call.requireHeader("X-Captcha-Id")
            val request = call.receive<RequestTokenGet>()
            // 验证用户身份
            val user = userService.login(
                xCaptchaId = xCaptchaId,
                requestTokenGet = request
            )

            // 生成 access_token
            val accessToken = JWT.create()
                .withAudience(application.appConfig.audience)
                .withIssuer(application.appConfig.issuer)
                .withSubject(user.id.toString())
                .withExpiresAt(Date(System.currentTimeMillis() + application.appConfig.accessTokenExpire * 1000))
                .sign(Algorithm.HMAC256(application.appConfig.secretKey))

            // 生成 refresh_token 并存储到 Redis
            val refreshToken = TokenGenerator.secureRandom(byteLength = 48)
            cacheService.cacheSetSimple(
                key = refreshToken,
                value = user.id.toString(),
                expire = application.appConfig.refreshTokenExpire.toLong()
            )
            call.respond(
                ResponseModel.Success(
                    ResponseTokenData(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        user = user
                    )
                )
            )
        }

        // POST /token/refresh - 刷新访问令牌
        post("/refresh") {
            val authorization = call.requireHeader("Authorization")
            if (!authorization.startsWith("Bearer ")) {
                throw IllegalArgumentException("Authorization does not start with Bearer")
            }
            val refreshToken = authorization.substringAfter(" ")

            // 从 Redis 获取 user_id
            val userId = cacheService.cacheGetSimple(refreshToken) ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ResponseModel.Error("refresh token expired")
            )
            // 删除旧的 refresh_token 并获取用户信息
            cacheService.cacheDelete(refreshToken)

            val user = userService.getUserById(userId.toInt())

            // 生成新的 access_token
            val newAccessToken = JWT.create()
                .withAudience(application.appConfig.audience)
                .withIssuer(application.appConfig.issuer)
                .withSubject(user.id.toString())
                .withExpiresAt(Date(System.currentTimeMillis() + application.appConfig.accessTokenExpire * 1000))
                .sign(Algorithm.HMAC256(application.appConfig.secretKey))

            // 生成新的 refresh_token
            val newRefreshToken = TokenGenerator.secureRandom(byteLength = 48)
            cacheService.cacheSetSimple(
                key = newRefreshToken,
                value = user.id.toString(),
                expire = application.appConfig.refreshTokenExpire.toLong()
            )

            call.respond(
                ResponseModel.Success(
                    ResponseTokenData(
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    user = user
                    )
                )
            )
        }

        // POST /token/verify_email - 发送邮箱验证邮件
        post("/verify_email") {
            val request = call.receive<RequestVerifyEmail>()

            // 验证邮箱格式
            val emailRegex = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
            if (!emailRegex.matches(request.email)) {
                throw IllegalArgumentException("Invalid email format")
            }

            // 生成验证 token
            val token = TokenGenerator.secureRandom(byteLength = 32)

            // 缓存 token -> email
            cacheService.cacheSetSimple(
                key = "email_verify:$token",
                value = request.email,
                expire = application.appConfig.emailVerifyExpire.toLong()
            )

            val verifyUrl = "${application.appConfig.serverUrl}/token/verify_email?token=$token"

            // 异步发送邮件
            emailService.sendEmail(
                toEmails = listOf(request.email),
                subject = "在线阅读系统注册邮箱验证",
                body = """点击以下链接完成邮箱验证:<br><a href="$verifyUrl">注册邮箱验证</a>""",
                isHtml = true
            )

            call.respond(
                ResponseModel.Empty("邮件发送成功")
            )
        }

        // GET /token/verify_email - 邮箱验证回调
        get("/verify_email") {
            val token = call.requireQueryParameter("token")

            // 从缓存获取邮箱
            val email = cacheService.cacheGetSimple("email_verify:$token") ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ResponseModel.Error("email expired")
            )
            // 删除验证 token(防止重复使用)
            cacheService.cacheDelete("email_verify:$token")

            // 标记邮箱为已验证
            cacheService.cacheSetSimple(
                key = "email_verified:$email",
                value = "true",
                expire = application.appConfig.emailVerifyExpire.toLong()
            )

            // 返回友好页面
            call.respondText(
                """
                <html>
                    <body>
                        <h2>邮箱验证成功</h2>
                        <p>您现在可以关闭此页面并返回应用,请在 5 分钟内完成注册</p>
                    </body>
                </html>
                """.trimIndent(),
                contentType = ContentType.Text.Html
            )
        }

        // GET /token/auth/me - 获取当前用户信息
        authenticate("auth-jwt") {
            get("/auth/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val userJson = principal.payload.subject
                val user = userService.getUserById(userJson.toInt())

                call.respond(
                    ResponseModel.Success(data = user)
                )
            }
        }
        }
    }
}
