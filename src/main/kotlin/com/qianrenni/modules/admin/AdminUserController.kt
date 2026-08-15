package com.qianrenni.modules.admin

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.common.web.requirePermission
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class UpdateUserStatusBody(val isActive: Boolean)

/**
 * 管理端 - 用户 / 用户角色管理路由（/admin/users）。
 * 从原 499 行 AdminController 拆出，依赖 [AdminService] + [RoleAdminService]。
 */
fun Routing.adminUser(
    adminService: AdminService,
    roleAdminService: RoleAdminService,
) {
    authenticate("auth-jwt") {
        route("/admin/users") {
            // GET /admin/users - 分页获取用户列表
            get {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.USER, ActionEnum.READ, ScopeEnum.ALL))
                )
                val page = call.request.queryParameters["page"]?.toInt() ?: 1
                val size = call.request.queryParameters["size"]?.toInt() ?: 20
                val keyword = call.queryParameters["keyword"]
                val result = adminService.getUsers(page, size, keyword)
                call.respond(ResponseModel.Success(data = result))
            }

            // GET /admin/users/{id} - 获取用户详情
            get("/{id}") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.USER, ActionEnum.READ, ScopeEnum.ALL))
                )
                val userId = call.requirePathParameter("id").toInt()
                val user = adminService.getUserDetail(userId)
                call.respond(ResponseModel.Success(data = user))
            }

            // PATCH /admin/users/{id}/status - 更新用户状态
            patch("/{id}/status") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.USER, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val userId = call.requirePathParameter("id").toInt()
                val body = call.receive<UpdateUserStatusBody>()
                adminService.updateUserStatus(userId, body.isActive)
                call.respond(ResponseModel.Empty(message = "用户状态已更新"))
            }

            // ----- 用户角色管理 -----
            get("/{id}/roles") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.READ, ScopeEnum.ALL))
                )
                val userId = call.requirePathParameter("id").toInt()
                val userDetail = adminService.getUserDetail(userId)
                call.respond(ResponseModel.Success(data = userDetail.roles))
            }

            post("/{id}/roles") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.USER, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val userId = call.requirePathParameter("id").toInt()
                val body = call.receive<RoleIdBody>()
                roleAdminService.addUserRoleById(
                    adminId = call.getCurrentUser().id,
                    updateUserId = userId,
                    roleId = body.roleId
                )
                call.respond(ResponseModel.Empty(message = "用户角色添加成功"))
            }

            delete("/{id}/roles") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.USER, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val userId = call.requirePathParameter("id").toInt()
                val roleId = call.requireQueryParameter("roleId").toInt()
                roleAdminService.removeUserRole(
                    adminId = call.getCurrentUser().id,
                    userId = userId,
                    roleId = roleId
                )
                call.respond(ResponseModel.Empty(message = "用户角色移除成功"))
            }
        }
    }
}
