package com.qianrenni.modules.system

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.AppBundleInfo
import com.qianrenni.models.tables.AppBundleTable
import com.qianrenni.models.tables.toAppBundleInfo
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.Logger
import java.io.File
import java.security.MessageDigest

/**
 * 容器页面包（Hybrid Bundle）版本管理服务。
 *
 * 设计要点：
 * - **版本号由服务端分配**（同 appKey 内 max+1），避免客户端自报版本造成覆盖或倒退；
 * - 文件写盘后立刻校验并算出 MD5，客户端下载后可校验完整性；
 * - 同一 appKey 只允许一个生效版本，`activate` 用于「预发验证通过后切正式」与「出问题回滚」；
 * - 上传路径全部来自服务端拼装（appKey 白名单字符 + 固定文件名），杜绝路径穿越。
 */
class BundleService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val logger: Logger,
) {

    /** 包文件落盘根目录：`{staticDir}/bundle`，经现有 `/static` 静态路由对外提供。 */
    private val bundleRoot: File
        get() = File(config.staticDir, "bundle")

    /** 允许的引擎：native 随包发布不落库，故只接受 h5 / rn。 */
    private val allowedEngines = setOf(ENGINE_H5, ENGINE_RN)

    /** 允许的包扩展名（同时决定落盘后的固定文件名后缀）。 */
    private val allowedExtensions = setOf("html", "htm", "js", "bundle")

    /**
     * 上传一个新版本。
     *
     * @param activate 上传后是否直接切为生效版本；false 时该版本只能被「扫码预发」使用
     * @return 落库后的包信息（含下载地址）
     */
    suspend fun upload(
        appKey: String,
        route: String,
        engine: String,
        entry: String,
        versionName: String,
        description: String,
        minAppVersion: Int,
        activate: Boolean,
        originalFileName: String?,
        content: File,
    ): AppBundleInfo {
        val safeAppKey = requireAppKey(appKey)
        val safeRoute = requireRoute(route)
        val safeEngine = engine.trim().lowercase().also {
            require(it in allowedEngines) { "engine 只支持 h5 / rn" }
        }
        val safeEntry = requireEntry(entry, safeEngine)
        val extension = requireExtension(originalFileName)
        require(content.length() > 0) { "包内容为空" }
        require(content.length() <= config.bundleMaxBytes) {
            "包体积超过上限 ${config.bundleMaxBytes / 1024 / 1024}MB"
        }

        val md5 = md5Of(content)
        val size = content.length()

        // 1. 先落盘（版本号按 max+1 预估）：DB 的唯一索引才是版本号权威，
        //    若插入冲突则删除文件并提示重试，不会留下指向不存在文件的记录
        val versionCode = nextVersionCode(safeAppKey)
        val targetDir = File(bundleRoot, "$safeAppKey/$versionCode")
        check(targetDir.mkdirs() || targetDir.isDirectory) { "无法创建包目录" }
        val targetFile = File(targetDir, "bundle.$extension")
        content.copyTo(targetFile, overwrite = true)

        // 2. 落库
        val id = try {
            databaseManager.suspendedTransaction {
                if (activate) {
                    AppBundleTable.update({ AppBundleTable.appKey eq safeAppKey }) {
                        it[isActive] = false
                    }
                }
                AppBundleTable.insert {
                    it[AppBundleTable.appKey] = safeAppKey
                    it[AppBundleTable.route] = safeRoute
                    it[AppBundleTable.engine] = safeEngine
                    it[AppBundleTable.entry] = safeEntry
                    it[AppBundleTable.versionCode] = versionCode
                    it[AppBundleTable.versionName] = versionName.trim().ifEmpty { versionCode.toString() }
                    it[filePath] = relativePath(targetFile)
                    it[AppBundleTable.md5] = md5
                    it[AppBundleTable.size] = size
                    it[AppBundleTable.description] = description.trim()
                    it[isActive] = activate
                    it[AppBundleTable.minAppVersion] = minAppVersion.coerceAtLeast(0)
                } get AppBundleTable.id
            }.value
        } catch (e: Exception) {
            targetFile.delete()
            logger.warn("Bundle 上传落库失败，已回滚文件 {}: {}", targetFile.path, e.message)
            throw IllegalArgumentException("版本号冲突，请重试")
        }

        logger.info(
            "Bundle 上传成功: id={} appKey={} version={} engine={} size={} activate={}",
            id, safeAppKey, versionCode, safeEngine, size, activate
        )
        return requireNotNull(findById(id)) { "包记录写入后读取失败" }
    }

    /** 版本列表（默认全部 appKey），按 appKey 升序、版本号降序。 */
    suspend fun list(appKey: String? = null): List<AppBundleInfo> {
        val key = appKey?.trim()?.takeIf { it.isNotEmpty() }
        val rows = databaseManager.suspendedTransaction(readOnly = true) {
            val query = if (key == null) {
                AppBundleTable.selectAll()
            } else {
                AppBundleTable.selectAll().where { AppBundleTable.appKey eq key }
            }
            query.orderBy(
                AppBundleTable.appKey to SortOrder.ASC,
                AppBundleTable.versionCode to SortOrder.DESC
            ).map { it }
        }
        return rows.map { it.toAppBundleInfo(serverUrl()) }
    }

    /** 当前生效版本（客户端「检查更新」用）；没有生效版本时返回 null。 */
    suspend fun active(appKey: String): AppBundleInfo? {
        val row = databaseManager.suspendedTransaction(readOnly = true) {
            AppBundleTable.selectAll()
                .where { (AppBundleTable.appKey eq appKey.trim()) and (AppBundleTable.isActive eq true) }
                .orderBy(AppBundleTable.versionCode to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        }
        return row?.toAppBundleInfo(serverUrl())
    }

    /** 指定版本（扫码预发用）。 */
    suspend fun findByVersion(appKey: String, versionCode: Int): AppBundleInfo? {
        val row = databaseManager.suspendedTransaction(readOnly = true) {
            AppBundleTable.selectAll()
                .where {
                    (AppBundleTable.appKey eq appKey.trim()) and (AppBundleTable.versionCode eq versionCode)
                }
                .limit(1)
                .singleOrNull()
        }
        return row?.toAppBundleInfo(serverUrl())
    }

    suspend fun findById(id: Int): AppBundleInfo? {
        val row = databaseManager.suspendedTransaction(readOnly = true) {
            AppBundleTable.selectAll().where { AppBundleTable.id eq id }.limit(1).singleOrNull()
        }
        return row?.toAppBundleInfo(serverUrl())
    }

    /** 把指定版本切为生效版本（同 appKey 其余版本自动失效）。 */
    suspend fun activate(id: Int): AppBundleInfo {
        databaseManager.suspendedTransaction {
            val row = AppBundleTable.selectAll().where { AppBundleTable.id eq id }.limit(1).singleOrNull()
                ?: throw IllegalArgumentException("包记录不存在")
            val appKey = row[AppBundleTable.appKey]
            AppBundleTable.update({ AppBundleTable.appKey eq appKey }) { it[isActive] = false }
            AppBundleTable.update({ AppBundleTable.id eq id }) { it[isActive] = true }
        }
        return requireNotNull(findById(id)) { "切换生效版本后读取失败" }
    }

    /** 删除某个版本（含文件）。生效版本需先切换到其他版本再删除。 */
    suspend fun delete(id: Int) {
        val row = databaseManager.suspendedTransaction {
            val current = AppBundleTable.selectAll().where { AppBundleTable.id eq id }.limit(1).singleOrNull()
                ?: throw IllegalArgumentException("包记录不存在")
            if (current[AppBundleTable.isActive]) {
                throw IllegalArgumentException("该版本为当前生效版本，请先切换到其他版本再删除")
            }
            val path = current[AppBundleTable.filePath]
            AppBundleTable.deleteWhere { AppBundleTable.id eq id }
            path
        }
        // 删文件不影响记录一致性，失败仅记录日志（残留文件可人工清理）
        runCatching {
            File(config.staticDir, row).takeIf { it.exists() }?.delete()
        }.onFailure { logger.warn("删除包文件失败 {}: {}", row, it.message) }
    }

    /**
     * 下一个版本号 = 同 appKey 内最大版本号 + 1。
     *
     * 用「排序取首行」而不是聚合函数：既避开 Exposed 聚合 API 的版本差异，
     * 也用上 (appKey, versionCode) 的唯一索引。
     */
    private suspend fun nextVersionCode(appKey: String): Int =
        databaseManager.suspendedTransaction(readOnly = true) {
            AppBundleTable.selectAll()
                .where { AppBundleTable.appKey eq appKey }
                .orderBy(AppBundleTable.versionCode to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(AppBundleTable.versionCode)
                ?: 0
        } + 1

    /** 落盘路径转成相对 staticDir 的路径（对外 URL 用正斜杠，兼容 Windows 开发机）。 */
    private fun relativePath(file: File): String =
        file.relativeTo(File(config.staticDir)).invariantSeparatorsPath

    private fun serverUrl(): String = config.serverUrl

    private fun requireAppKey(raw: String): String {
        val value = raw.trim().lowercase()
        require(APP_KEY_PATTERN.matches(value)) {
            "appKey 只允许小写字母/数字/下划线/中划线，且长度 1~64"
        }
        return value
    }

    private fun requireRoute(raw: String): String {
        val value = raw.trim()
        require(ROUTE_PATTERN.matches(value) && !value.contains("..")) {
            "route 只允许字母/数字/下划线/中划线/点/斜杠，且不含 .."
        }
        return value
    }

    /** 入口校验：h5 为包内相对路径，rn 为 component 名，两者都不允许路径穿越。 */
    private fun requireEntry(raw: String, engine: String): String {
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= 255) { "entry 不能为空且不超过 255 字符" }
        require(!value.contains("..") && !value.startsWith("/")) { "entry 不允许以 / 开头或包含 .." }
        if (engine == ENGINE_RN) {
            require(!value.contains('/')) { "rn 的 entry 应为 component 名（不含 /）" }
        }
        return value
    }

    /** 扩展名白名单：只用它拼装固定文件名，不接受用户提供的文件名。 */
    private fun requireExtension(originalFileName: String?): String {
        val extension = originalFileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.lowercase()
            .orEmpty()
            .takeIf { it in allowedExtensions }
            ?: throw IllegalArgumentException("包文件扩展名只支持 ${allowedExtensions.joinToString("/")}")
        return extension
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ENGINE_H5 = "h5"
        const val ENGINE_RN = "rn"

        private val APP_KEY_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        private val ROUTE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_./-]{0,127}$")
    }
}

/**
 * 容器页面包二维码内容的编码约定。
 *
 * 只带「包标识 + 版本 + 校验和」，不带下载地址：**下载地址与引擎/入口等元信息一律由服务端下发**，
 * 二维码因此无法被伪造成「让我去下载别处的代码」，扫到陌生二维码最多是被引导安装一个服务端已发布的版本。
 * 手机端解析实现见 android-user 的 `util/BundlePayload.kt`（需与本约定保持一致）。
 */
object BundlePayload {
    const val SCHEME = "gugareading"
    const val HOST = "bundle"
    const val PARAM_APP_KEY = "appKey"
    const val PARAM_VERSION = "version"
    const val PARAM_MD5 = "md5"

    fun build(appKey: String, versionCode: Int, md5: String): String =
        "$SCHEME://$HOST?$PARAM_APP_KEY=$appKey&$PARAM_VERSION=$versionCode&$PARAM_MD5=$md5"
}
