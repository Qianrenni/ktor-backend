package com.qianrenni.modules.system

import com.qianrenni.common.appConfig
import com.qianrenni.models.tables.AppBundleInfo
import com.qianrenni.testutil.bundleService
import com.qianrenni.testutil.withTestApplication
import io.ktor.server.application.Application
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BundleService 集成测试：容器页面包的版本分配、落盘、生效切换与删除。
 * 依赖 H2（元数据）与 STATIC_DIR（包文件），两者都由测试夹具按用例重建。
 */
class TestBundleService {

    private val appKey = "guga-user"

    /** 造一个上传用的临时文件（内容用于校验 MD5/体积）。 */
    private fun contentFile(text: String = "console.log('hi')", ext: String = "js"): java.io.File =
        createTempFile("bundle_test", ".$ext").toFile().apply { writeText(text) }

    /** 把服务端返回的 url 还原成本地文件路径。 */
    private fun Application.localFile(url: String): java.io.File =
        java.io.File(appConfig.staticDir, url.substringAfter("/static/"))

    private suspend fun Application.upload(
        versionName: String = "",
        activate: Boolean = false,
        engine: String = "rn",
        route: String = "promo",
        entry: String = "PromoPage",
        fileName: String = "index.js",
        text: String = "console.log('hi')",
        key: String = appKey,
    ): AppBundleInfo = bundleService.upload(
        appKey = key,
        route = route,
        engine = engine,
        entry = entry,
        versionName = versionName,
        description = "",
        minAppVersion = 0,
        activate = activate,
        originalFileName = fileName,
        content = contentFile(text, fileName.substringAfterLast('.', "js")),
    )

    @Test
    fun `upload 分配递增版本号并落盘可校验`() = withTestApplication {
        val first = upload()
        val second = upload()

        assertEquals(1, first.versionCode)
        assertEquals(2, second.versionCode)
        // 文件落在 staticDir 下，经 /static 可访问
        assertTrue(first.url.contains("/static/bundle/$appKey/1/"))
        val file = localFile(first.url)
        assertTrue(file.exists(), "包文件应已落盘: ${file.path}")
        assertEquals("console.log('hi')", file.readText())
        assertEquals(file.length(), first.size)
        // MD5 与内容一致（客户端据此校验完整性）
        assertEquals(md5("console.log('hi')"), first.md5)
    }

    @Test
    fun `upload 默认不生效 需要显式切换`() = withTestApplication {
        upload()

        assertNull(bundleService.active(appKey))
        assertEquals(false, bundleService.list(appKey).single().isActive)
    }

    @Test
    fun `upload activate 时直接生效并让旧版本失效`() = withTestApplication {
        val first = upload(activate = true)
        assertNotNull(bundleService.active(appKey))
        assertEquals(first.id, bundleService.active(appKey)?.id)

        val second = upload(activate = true)

        assertEquals(second.id, bundleService.active(appKey)?.id)
        assertEquals(false, bundleService.findById(first.id)?.isActive)
    }

    @Test
    fun `activate 可以回滚到旧版本`() = withTestApplication {
        val v1 = upload(activate = true)
        val v2 = upload(activate = true)
        assertEquals(v2.id, bundleService.active(appKey)?.id)

        bundleService.activate(v1.id)

        assertEquals(v1.id, bundleService.active(appKey)?.id)
        assertEquals(false, bundleService.findById(v2.id)?.isActive)
    }

    @Test
    fun `activate 不存在的记录抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { bundleService.activate(999999) }
    }

    @Test
    fun `findByVersion 返回指定版本`() = withTestApplication {
        upload(activate = true)
        val v2 = upload()

        assertEquals(v2.id, bundleService.findByVersion(appKey, 2)?.id)
        assertEquals(1, bundleService.findByVersion(appKey, 1)?.versionCode)
        assertNull(bundleService.findByVersion(appKey, 99))
    }

    @Test
    fun `list 按 appKey 升序 版本降序`() = withTestApplication {
        upload(activate = true)
        upload()
        upload(key = "another-app", route = "other", entry = "Other", fileName = "index.html", engine = "h5")

        val list = bundleService.list()

        assertEquals(3, list.size)
        assertEquals("another-app", list.first().appKey)
        assertEquals(listOf(2, 1), bundleService.list(appKey).map { it.versionCode })
    }

    @Test
    fun `delete 生效版本被拒绝`() = withTestApplication {
        val v1 = upload(activate = true)

        val error = assertFailsWith<IllegalArgumentException> { bundleService.delete(v1.id) }

        assertTrue(error.message.orEmpty().contains("生效版本"), "应提示先切换版本: ${error.message}")
        assertNotNull(bundleService.findById(v1.id))
    }

    @Test
    fun `delete 非生效版本会连带删除文件`() = withTestApplication {
        upload(activate = true)
        val v2 = upload()
        val file = localFile(v2.url)
        assertTrue(file.exists())

        bundleService.delete(v2.id)

        assertNull(bundleService.findById(v2.id))
        assertFalse(file.exists(), "包文件应同时删除")
        assertEquals(1, bundleService.list(appKey).size)
    }

    @Test
    fun `upload 拒绝非法 appKey 与引擎`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { upload(key = "../evil") }
        assertFailsWith<IllegalArgumentException> { upload(key = "Bad Key") }
        assertFailsWith<IllegalArgumentException> { upload(engine = "native") }
        assertFailsWith<IllegalArgumentException> { upload(engine = "wasm") }
    }

    @Test
    fun `upload 拒绝路径穿越的 route 与 entry`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { upload(route = "../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { upload(entry = "../../secret") }
        assertFailsWith<IllegalArgumentException> { upload(entry = "/absolute") }
    }

    @Test
    fun `upload 拒绝非法扩展名与空文件`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> { upload(fileName = "shell.sh") }
        assertFailsWith<IllegalArgumentException> { upload(fileName = "noext") }
        assertFailsWith<IllegalArgumentException> { upload(text = "") }
    }

    @Test
    fun `h5 与 rn 的 entry 语义不同`() = withTestApplication {
        // h5 允许相对路径，rn 必须是 component 名
        val h5 = upload(engine = "h5", entry = "index.html", fileName = "index.html")
        assertEquals("index.html", h5.entry)

        assertFailsWith<IllegalArgumentException> {
            upload(engine = "rn", entry = "pages/Promo")
        }
    }

    @Test
    fun `同一 appKey 可共存多个版本供扫码预发`() = withTestApplication {
        val active = upload(activate = true)
        val staged = upload()

        // 扫码预发取的是「指定版本」，与是否生效无关
        assertEquals(staged.id, bundleService.findByVersion(appKey, staged.versionCode)?.id)
        assertEquals(active.id, bundleService.active(appKey)?.id)
    }

    @Test
    fun `二维码内容只携带包标识与校验和`() {
        val qr = BundlePayload.build("guga-user", 3, "abc123")

        assertEquals("gugareading://bundle?appKey=guga-user&version=3&md5=abc123", qr)
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
