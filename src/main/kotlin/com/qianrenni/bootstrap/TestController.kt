package com.qianrenni.bootstrap

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.web.PermissionCheck
import com.qianrenni.common.ResponseModel
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.infrastructure.cache.cache
import com.qianrenni.modules.admin.generatePermissionCode
import com.ucasoft.ktor.simpleCache.cacheOutput
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds


fun Route.test(emailService: EmailService) {
    route("/test") {
        route("/cache") {
            cacheOutput(5.seconds) {
                get("/memory") {
                    call.respond(mapOf("result" to Random.nextInt()))
                }
            }
            get("/redis") {
                val result = cache(
                    args = listOf("default"),
                    keyPrefix = "default",
                    serializer = Int.serializer(),
                ) {
                    Random.nextInt()
                }
                call.respond(ResponseModel.Success(result))
            }
        }

        route("/response-model") {
            get("/success") {
                call.respond(ResponseModel.Success(data = "Success"))
            }
            get("/error") {
                call.respond(ResponseModel.Error(message = "Error"))
            }
            get("/empty") {
                call.respond(ResponseModel.Empty(message = "Empty"))
            }
        }
        route("/email"){
            get("/get") {
                emailService.sendEmail(listOf("1093171693@qq.com"), subject = "测试邮件",body="测试内容")
                call.respond(ResponseModel.Success(data = "Success"))
            }
        }
        authenticate("auth-jwt") {

            route("/permissions") {
                install(PermissionCheck) {
                    requiredPermissions = listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.BOOK,
                            action = ActionEnum.READ,
                            scope = ScopeEnum.ALL
                        )
                    )
                }
                get {
                    call.respond(ResponseModel.Success(data = "Success"))
                }
                get("/book1") {
                    call.respond(ResponseModel.Success(data = "Success"))
                }
            }
        }
        get<Articles> { article ->
            // Get all articles ...
            call.respond("List of articles sorted starting from ${article.sort}")
        }
    }

}