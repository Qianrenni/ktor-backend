package com.qianrenni.common.web

import com.google.protobuf.LazyStringArrayList.emptyList
import io.ktor.server.application.*
import io.ktor.server.auth.*

data class PermissionCheckConfig(var requiredPermissions: List<String> = emptyList())

// 3. 创建路由作用域插件
val PermissionCheck = createRouteScopedPlugin(
    name = "PermissionCheck",
    createConfiguration = ::PermissionCheckConfig
) {
    on(AuthenticationChecked) { call ->
        // 在认证后的阶段执行
        call.requirePermission(pluginConfig.requiredPermissions)
    }
}