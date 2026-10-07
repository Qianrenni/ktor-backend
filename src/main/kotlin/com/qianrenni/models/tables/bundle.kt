package com.qianrenni.models.tables

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * 容器页面包（Hybrid Bundle）：支持 native/h5/rn 三种引擎的「页面资源包」版本管理。
 *
 * - h5：包内容是 HTML（可带同目录静态资源），由 WebView 引擎渲染；
 * - rn：包内容是 Metro 打出的 JS bundle，由 RN 引擎渲染；
 * - native：不落库（随 APK 发布），仅作为容器路由表里的一个实现声明。
 *
 * 同一个 [appKey] 下允许多个版本共存，但只有一条 [isActive] = true（当前生效），
 * 便于「上传新版本 → 扫码在真机预发验证 → 再切为生效 → 出问题切回旧版本」。
 */
object AppBundleTable : IntIdTable(name = "app_bundle") {
    /** 应用/业务标识，客户端按它拉取自己需要的包，如 `guga-user` */
    val appKey = varchar("appKey", 64)

    /** 容器路由 key：客户端用 `HybridPage(route)` 打开，如 `promo-2026` */
    val route = varchar("route", 128)

    /** 引擎类型：h5 / rn */
    val engine = varchar("engine", 16)

    /** 引擎入口：h5 为包内 HTML 相对路径；rn 为注册的 component 名 */
    val entry = varchar("entry", 255)

    /** 版本号，单调递增；客户端据此判断更新与回滚 */
    val versionCode = integer("versionCode")

    /** 展示用版本名 */
    val versionName = varchar("versionName", 32)

    /** 包文件相对 `AppConfig.staticDir` 的路径，经 `/static/...` 对外提供 */
    val filePath = varchar("filePath", 255)

    /** 包内容 MD5（客户端下载后校验，防止传输损坏/被替换） */
    val md5 = varchar("md5", 32)

    /** 包大小（字节） */
    val size = long("size")

    /** 备注，便于运维辨认这次上传的目的 */
    val description = varchar("description", 255).default("")

    /** 当前生效版本：同一 appKey 只应有一条为 true */
    val isActive = bool("isActive").default(false)

    /** 最低客户端版本（versionCode，0 表示不限），用于避免老客户端加载不兼容的包 */
    val minAppVersion = integer("minAppVersion").default(0)

    val createdAt = datetime("createdAt").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updatedAt").defaultExpression(CurrentDateTime)

    init {
        // 同一 appKey 内版本号唯一，避免重复上传覆盖语义不清
        uniqueIndex(appKey, versionCode)
    }
}

/** 对外暴露的包信息（[url] 由服务端按 `serverUrl` 拼好，客户端直接用）。 */
@Serializable
data class AppBundleInfo(
    val id: Int,
    val appKey: String,
    val route: String,
    val engine: String,
    val entry: String,
    val versionCode: Int,
    val versionName: String,
    val md5: String,
    val size: Long,
    val description: String = "",
    val isActive: Boolean = false,
    val minAppVersion: Int = 0,
    /** 下载地址（`/static/bundle/...`） */
    val url: String = "",
    val createdAt: String = ""
)

fun ResultRow.toAppBundleInfo(serverUrl: String): AppBundleInfo {
    val path = this[AppBundleTable.filePath]
    return AppBundleInfo(
        id = this[AppBundleTable.id].value,
        appKey = this[AppBundleTable.appKey],
        route = this[AppBundleTable.route],
        engine = this[AppBundleTable.engine],
        entry = this[AppBundleTable.entry],
        versionCode = this[AppBundleTable.versionCode],
        versionName = this[AppBundleTable.versionName],
        md5 = this[AppBundleTable.md5],
        size = this[AppBundleTable.size],
        description = this[AppBundleTable.description],
        isActive = this[AppBundleTable.isActive],
        minAppVersion = this[AppBundleTable.minAppVersion],
        url = "${serverUrl.trimEnd('/')}/static/$path",
        createdAt = this[AppBundleTable.createdAt].toString()
    )
}
