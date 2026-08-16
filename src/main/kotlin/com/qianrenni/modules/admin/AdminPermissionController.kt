package com.qianrenni.modules.admin

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.requirePermission
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class PermissionIdsBody(val permissionIds: List<Int>) {
    init {
        require(permissionIds.isNotEmpty(), { "权限列表不能为空" })
    }
}

@Serializable
data class ParentIdBody(val parentId: Int) {
    init {
        require(parentId > 0, { "父角色 ID 必须为正数" })
    }
}

@Serializable
data class RoleIdBody(val roleId: Int) {
    init {
        require(roleId > 0, { "角色 ID 必须为正数" })
    }
}

@Serializable
data class CreateRoleBody(val name: String, val code: String, val description: String? = null) {
    init {
        require(name.isNotBlank(), { "角色名不能为空" })
        require(name.length <= 50, { "角色名不能超过50字" })
        require(code.isNotBlank(), { "角色编码不能为空" })
        require(code.length <= 50, { "角色编码不能超过50字" })
    }
}

@Serializable
data class UpdateRoleBody(val name: String? = null, val description: String? = null) {
    init {
        require(name == null || name.isNotBlank(), { "角色名不能为空" })
        require(name == null || name.length <= 50, { "角色名不能超过50字" })
    }
}

/**
 * 管理端 - 权限 / 角色管理路由（/admin/permissions、/admin/roles）。
 * 从原 499 行 AdminController 拆出，依赖 [RightService]（只读）+ [RoleAdminService]（写）。
 */
fun Routing.adminPermission(
    rightService: RightService,
    roleAdminService: RoleAdminService,
) {
    authenticate("auth-jwt") {
        route("/admin") {
            get("/permissions") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.READ, ScopeEnum.ALL))
                )
                val permissions = rightService.permissionDict.values.toList()
                call.respond(ResponseModel.Success(data = permissions))
            }
            post("/permissions/reload") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                rightService.restart()
                call.respond(ResponseModel.Empty(message = "权限缓存已刷新"))
            }

            // ----- 角色管理 -----
            get("/roles") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.READ, ScopeEnum.ALL))
                )
                val roles = rightService.roleDict.values.toList()
                call.respond(ResponseModel.Success(data = roles))
            }

            post("/roles") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val body = call.receive<CreateRoleBody>()
                val role = roleAdminService.createRole(
                    name = body.name,
                    code = body.code,
                    description = body.description
                )
                call.respond(ResponseModel.Success(data = role))
            }

            put("/roles/{id}") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val body = call.receive<UpdateRoleBody>()
                val role = roleAdminService.updateRole(
                    roleId = roleId,
                    name = body.name,
                    description = body.description
                )
                call.respond(ResponseModel.Success(data = role))
            }

            delete("/roles/{id}") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                roleAdminService.deleteRole(roleId)
                call.respond(ResponseModel.Empty(message = "角色已删除"))
            }

            // ----- 角色权限管理 -----
            get("/roles/{id}/permissions") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.READ, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val permissions = rightService.getRolePermissions(roleId)
                call.respond(ResponseModel.Success(data = permissions))
            }

            post("/roles/{id}/permissions") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val body = call.receive<PermissionIdsBody>()
                roleAdminService.assignPermissionsToRole(roleId, body.permissionIds)
                call.respond(ResponseModel.Empty(message = "权限分配成功"))
            }

            delete("/roles/{id}/permissions") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val body = call.receive<PermissionIdsBody>()
                roleAdminService.revokePermissionsFromRole(roleId, body.permissionIds)
                call.respond(ResponseModel.Empty(message = "权限回收成功"))
            }

            // ----- 角色继承管理 -----
            get("/roles/{id}/parents") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.READ, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val parentIds = rightService.roleInheritanceDict[roleId] ?: emptyList()
                val parents = parentIds.mapNotNull { rightService.roleDict[it] }
                call.respond(ResponseModel.Success(data = parents))
            }

            post("/roles/{id}/parents") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val body = call.receive<ParentIdBody>()
                roleAdminService.addRoleInheritance(childId = roleId, parentId = body.parentId)
                call.respond(ResponseModel.Empty(message = "角色继承添加成功"))
            }

            delete("/roles/{id}/parents") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.PERMISSION, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val roleId = call.requirePathParameter("id").toInt()
                val parentId = call.requireQueryParameter("parentId").toInt()
                roleAdminService.removeRoleInheritance(childId = roleId, parentId = parentId)
                call.respond(ResponseModel.Empty(message = "角色继承移除成功"))
            }
        }
    }
}
