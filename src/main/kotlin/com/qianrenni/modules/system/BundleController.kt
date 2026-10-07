package com.qianrenni.modules.system

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.requirePermission
import com.qianrenni.models.tables.AppBundleInfo
import com.qianrenni.modules.admin.generatePermissionCode
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.io.path.createTempFile

@Serializable
data class ResponseBundleUpload(
    val bundle: AppBundleInfo,
    /** 印成二维码即可在真机上「扫码预发」这个版本 */
    val qrContent: String,
)

@Serializable
data class ResponseBundleQr(
    val qrContent: String,
)

/**
 * 容器页面包（Hybrid Bundle）版本管理路由。
 *
 * | 方法 | 路径 | 权限 |
 * | ---- | ---- | ---- |
 * | POST   | `/system/bundle/upload`        | SYSTEM:MANAGE:ALL（管理端上传） |
 * | GET    | `/system/bundle`               | SYSTEM:READ:ALL（管理端版本列表） |
 * | POST   | `/system/bundle/{id}/activate` | SYSTEM:MANAGE:ALL（切生效/回滚） |
 * | DELETE | `/system/bundle/{id}`          | SYSTEM:MANAGE:ALL |
 * | GET    | `/system/bundle/{id}/qrcode`   | SYSTEM:READ:ALL |
 * | GET    | `/system/bundle/active`        | 登录即可（客户端检查更新） |
 * | GET    | `/system/bundle/info`          | 登录即可（扫码后按版本取详情） |
 *
 * 后两个只要求登录：包内容本身就是客户端代码，不涉及用户隐私，
 * 但也没有理由对匿名请求开放。
 */
fun Routing.bundle(bundleService: BundleService) {
    route("/system/bundle") {
        authenticate("auth-jwt") {
            // POST /system/bundle/upload - 上传新版本（multipart）
            post("/upload") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.SYSTEM,
                            action = ActionEnum.MANAGE,
                            scope = ScopeEnum.ALL
                        )
                    )
                )

                var appKey: String? = null
                var route: String? = null
                var engine: String? = null
                var entry: String? = null
                var versionName: String? = null
                var description: String? = null
                var minAppVersion: String? = null
                var activate: String? = null
                var bundleFile: File? = null
                var originalFileName: String? = null

                val multipart = call.receiveMultipart(formFieldLimit = 1024 * 1024)
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "appKey" -> appKey = part.value
                            "route" -> route = part.value
                            "engine" -> engine = part.value
                            "entry" -> entry = part.value
                            "versionName" -> versionName = part.value
                            "description" -> description = part.value
                            "minAppVersion" -> minAppVersion = part.value
                            "activate" -> activate = part.value
                        }

                        is PartData.FileItem -> if (part.name == "file") {
                            originalFileName = part.originalFileName
                            val temp = createTempFile("bundle_upload", "tmp").toFile()
                            part.provider().copyAndClose(temp.writeChannel())
                            bundleFile = temp
                        }

                        else -> {}
                    }
                    part.release()
                }

                val content = bundleFile
                try {
                    requireNotNull(appKey) { "appKey 不能为空" }
                    requireNotNull(route) { "route 不能为空" }
                    requireNotNull(engine) { "engine 不能为空" }
                    requireNotNull(entry) { "entry 不能为空" }
                    requireNotNull(content) { "包文件不能为空" }

                    val info = bundleService.upload(
                        appKey = appKey,
                        route = route,
                        engine = engine,
                        entry = entry,
                        versionName = versionName.orEmpty(),
                        description = description.orEmpty(),
                        minAppVersion = minAppVersion?.toIntOrNull() ?: 0,
                        activate = activate?.toBooleanStrictOrNull() ?: false,
                        originalFileName = originalFileName,
                        content = content,
                    )
                    call.respond(
                        ResponseModel.Success(
                            ResponseBundleUpload(
                                bundle = info,
                                qrContent = BundlePayload.build(info.appKey, info.versionCode, info.md5),
                            )
                        )
                    )
                } finally {
                    // 临时文件已复制进静态目录，这里必须清理，避免 /tmp 堆积
                    content?.delete()
                }
            }

            // GET /system/bundle - 版本列表（管理端）
            get {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.SYSTEM,
                            action = ActionEnum.READ,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val appKey = call.request.queryParameters["appKey"]
                call.respond(ResponseModel.Success(data = bundleService.list(appKey)))
            }

            // GET /system/bundle/active?appKey= - 当前生效版本（客户端检查更新）
            get("/active") {
                val appKey = call.requireQueryParameter("appKey")
                val info = bundleService.active(appKey)
                    ?: throw IllegalArgumentException("该应用暂无生效版本")
                call.respond(ResponseModel.Success(data = info))
            }

            // GET /system/bundle/info?appKey=&versionCode= - 指定版本详情（扫码预发）
            get("/info") {
                val appKey = call.requireQueryParameter("appKey")
                val versionCode = call.requireQueryParameter("versionCode").toIntOrNull()
                    ?: throw IllegalArgumentException("versionCode 非法")
                val info = bundleService.findByVersion(appKey, versionCode)
                    ?: throw IllegalArgumentException("包版本不存在")
                call.respond(ResponseModel.Success(data = info))
            }

            // POST /system/bundle/{id}/activate - 切换生效版本（发布 / 回滚）
            post("/{id}/activate") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.SYSTEM,
                            action = ActionEnum.MANAGE,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val id = call.requirePathParameter("id").toIntOrNull()
                    ?: throw IllegalArgumentException("id 非法")
                call.respond(ResponseModel.Success(data = bundleService.activate(id)))
            }

            // DELETE /system/bundle/{id} - 删除某个版本（生效版本需先切换）
            delete("/{id}") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.SYSTEM,
                            action = ActionEnum.MANAGE,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val id = call.requirePathParameter("id").toIntOrNull()
                    ?: throw IllegalArgumentException("id 非法")
                bundleService.delete(id)
                call.respond(ResponseModel.Empty(message = "已删除"))
            }

            // GET /system/bundle/{id}/qrcode - 取某个版本的二维码内容
            get("/{id}/qrcode") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.SYSTEM,
                            action = ActionEnum.READ,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val id = call.requirePathParameter("id").toIntOrNull()
                    ?: throw IllegalArgumentException("id 非法")
                val info = bundleService.findById(id)
                    ?: throw IllegalArgumentException("包记录不存在")
                call.respond(
                    ResponseModel.Success(
                        ResponseBundleQr(
                            BundlePayload.build(info.appKey, info.versionCode, info.md5)
                        )
                    )
                )
            }
        }
    }
}
