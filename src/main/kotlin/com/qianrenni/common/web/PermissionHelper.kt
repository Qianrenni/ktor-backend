package com.qianrenni.common.web

import com.qianrenni.common.PermissionDeniedException
import com.qianrenni.models.tables.FullUser
import com.qianrenni.bootstrap.services
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.util.*

private val UserAttributeKey = AttributeKey<FullUser>("UserAttributeKey")

/**
 * 权限检查辅助函数
 *
 * 在路由中使用示例:
 *```kotlin
 * route("/admin") {
 *     authenticate("auth-jwt") {
 *         route("/users") {
 *             get {
 *                 // 检查用户是否有 "user:read:all" 权限
 *                 call.requirePermission("user:read:all")
 *                 call.respond(mapOf("users" to listOf()))
 *             }
 *             post {
 *                 // 检查用户是否有 "user:create:all" 权限
 *                 call.requirePermission("user:create:all")
 *                 call.respond(mapOf("success" to true))
 *             }
 *         }
 *     }
 * }
 * ```
 */
suspend fun ApplicationCall.getCurrentUser(): FullUser {

    attributes.getOrNull(UserAttributeKey)?.let { return it }
    val principal = this.principal<JWTPrincipal>() ?: throw PermissionDeniedException("未认证用户")

    val subject = principal.payload.subject ?: throw PermissionDeniedException("无效的用户信息")
    return try {
        val userId = subject.toInt()
        val user = application.services.userService.getUserById(userId)
        attributes.put(UserAttributeKey, user)
        user
    } catch (e: Exception) {
        throw PermissionDeniedException("用户信息解析失败")
    }
}


/**
 * 检查用户是否具有指定权限
 *
 * @param permissions 所需权限编码列表
 * @throws PermissionDeniedException 如果用户权限不足
 * @return 当前用户对象
 */
suspend fun ApplicationCall.requirePermission(
    permissions: List<String>
): FullUser {
    val user = this.getCurrentUser()
    val hasPermission = this.application.services.rightService.checkPermission(
        permissions,
        user.right
    )
    if (!hasPermission) {
        throw PermissionDeniedException("权限不足")
    }
    return user
}
