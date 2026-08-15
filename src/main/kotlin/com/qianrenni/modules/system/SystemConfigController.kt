package com.qianrenni.modules.system

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.requirePermission
import com.qianrenni.modules.admin.generatePermissionCode
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 动态配置管理路由。
 *
 * - GET  /system/config         列出所有领域的当前生效配置
 * - PUT  /system/config/{domain} 更新某领域配置（部分字段合并；写 Redis + 失效本地缓存）
 */
fun Routing.systemConfig(systemConfigService: SystemConfigService) {
    route("/system/config") {
        authenticate("auth-jwt") {
            get {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.PERMISSION,
                            action = ActionEnum.READ,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                call.respond(ResponseModel.Success(data = systemConfigService.list()))
            }

            put("/{domain}") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.PERMISSION,
                            action = ActionEnum.UPDATE,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val domain = call.parameters["domain"] ?: ""
                val body = call.receive<Map<String, String>>()
                val result = systemConfigService.update(domain, body)
                if (result == null) {
                    call.respond(ResponseModel.Error(message = "配置领域不存在或参数非法"))
                } else {
                    call.respond(ResponseModel.Success(data = result))
                }
            }
        }
    }
}
