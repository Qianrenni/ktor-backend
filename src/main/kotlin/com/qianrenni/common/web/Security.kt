package com.qianrenni.common.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qianrenni.common.appConfig
import com.qianrenni.common.ResponseModel
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

/**
 * 安全配置(JWT 认证)
 * 对应原项目中的 Security.kt
 */
fun Application.configureSecurity() {
    // JWT 认证配置
    install(Authentication) {
        jwt("auth-jwt") {
            verifier {
                JWT.require(Algorithm.HMAC256(this@configureSecurity.appConfig.secretKey))
                    .withAudience(this@configureSecurity.appConfig.audience)
                    .withIssuer(this@configureSecurity.appConfig.issuer)
                    .build()
            }
            validate { credential ->
                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.respond(
                    status = HttpStatusCode.Unauthorized,
                    message = ResponseModel.Error("your identifier is expired or wrong")
                )
            }
        }
    }
//    install(CSRF) {
//        tests Origin is an expected value
//        allowOrigin("http://localhost:8080")

        // tests Origin matches Host header
//        originMatchesHost()

        // custom header checks
//        checkHeader("X-CSRF-Token")
//    }
}
