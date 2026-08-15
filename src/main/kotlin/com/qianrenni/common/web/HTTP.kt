package com.qianrenni.common.web

import com.qianrenni.common.appConfig
import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP 相关配置
 */
fun Application.configureHTTP() {
    // CORS 配置
    val config = appConfig
    install(ContentNegotiation) {
        json()
    }
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("x-captcha-id")
        exposeHeader("x-captcha-id")
        exposeHeader("X-RateLimit-Reset")
        exposeHeader("X-RateLimit-Remaining")
        exposeHeader("X-RateLimit-Limit")
        config.allowHost.split(",").forEach {
            allowHost(it.trim())
        }
        allowNonSimpleContentTypes = true
    }

    // 压缩配置
    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024)
        }
    }
    install(SimpleCache) {
        memoryCache {
            invalidateAt = 10.seconds
        }
    }
    // 默认响应头
//    install(DefaultHeaders) {
//        header("X-Engine", "Ktor")
//    }

    // 条件请求支持(Etag, Last-Modified)
//    install(ConditionalHeaders)
//    install(PartialContent) {
//        // Maximum number of ranges that will be accepted from a HTTP request.
//        // If the HTTP request specifies more ranges, they will all be merged into a single range.
//        maxRangeCount = 10
//    }
//    // 转发头支持(反向代理场景)
//    install(ForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
//    install(XForwardedHeaders)
//    install(CachingHeaders) {
//        options { call, outgoingContent ->
//            when (outgoingContent.contentType?.withoutParameters()) {
//                ContentType.Text.CSS -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
//                else -> null
//            }
//        }
//    }
    install(AutoHeadResponse)
    install(ResponseTimePlugin)
}
