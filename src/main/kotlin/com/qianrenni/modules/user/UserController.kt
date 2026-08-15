package com.qianrenni.modules.user

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.models.tables.FullUser
import com.qianrenni.common.web.requirePermission
import com.qianrenni.common.ResponseModel
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.modules.admin.generatePermissionCode
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RegisterUser(
    val user: FullUser,
    val captcha: String
)

@Serializable
data class UserPasswordUpdate(
    val userName: String,
    val oldPassword: String,
    val newPassword: String
)

@Serializable
data class ForgotPasswordRequest(
     val userAccount: String,
     val verifyCode: String,
     val password: String
)


fun Routing.user(
    userService: UserService,
    captchaService: CaptchaService,
    cacheService: CacheService,
    emailService: EmailService,
) {
    route("/user") {
        // GET /user/count - 获取用户数量
        authenticate("auth-jwt") {
            get("/count") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.PERMISSION,
                            action = ActionEnum.READ,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val count = userService.getUserCount()
                call.respond(
                    ResponseModel.Success(data = count)
                )
            }
            // PATCH /user/update-password - 更新密码
            patch("/update-password") {
                val request = call.receive<UserPasswordUpdate>()

                userService.updatePassword(
                    userEmail = request.userName,
                    oldPassword = request.oldPassword,
                    newPassword = request.newPassword
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }


        // POST /user/register - 用户注册
        post("/register") {
            val request = call.receive<RegisterUser>()
            val xCaptchaId = call.requireHeader("X-Captcha-Id")

            // 验证验证码
            val isCaptchaValid = captchaService.verifyCaptcha(request.captcha, xCaptchaId)
            if (!isCaptchaValid) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ResponseModel.Error("验证码错误")
                )
            }

            // 检查邮箱是否已验证
            cacheService.cacheGetSimple("email_verified:${request.user.email}")
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ResponseModel.Error("邮箱未验证")
                )

            // 创建用户
            userService.createUser(
                username = request.user.userName,
                email = request.user.email,
                password = request.user.password,
                avatar = request.user.avatar
            )

            // 删除邮箱验证状态
            cacheService.cacheDelete("email_verified:${request.user.email}")

            call.respond(HttpStatusCode.Created)
        }



        // GET /user/forgot-password - 获取忘记密码验证码
        get("/forgot-password") {
            val userAccount = call.requireQueryParameter("user_account")

            val code = captchaService.getVerifyCode(
                keyPrefix = "forgot_password:$userAccount"
            )
            // 发送邮件
            emailService.sendEmail(
                toEmails = listOf(userAccount),
                subject = "忘记密码验证码",
                body = "您的验证码为:$code,请勿将验证码告知他人。",
                isHtml = false
            )
            call.respond(HttpStatusCode.NoContent)
        }

        // PATCH /user/forgot-password - 忘记密码重置
        patch("/forgot-password") {
            val request = call.receive<ForgotPasswordRequest>()
            userService.forgotPassword(
                userAccount = request.userAccount,
                newPassword = request.password,
                verifyCode = request.verifyCode
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
