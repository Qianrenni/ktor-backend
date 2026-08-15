package com.qianrenni.infrastructure.storage

import com.qianrenni.common.AppConfig
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.*

/**
 * ContentStore 自动 compact 编排器测试：
 * 垃圾占比指标、阈值判断、定时扫描只整理需要整理的 store、force 全量整理、引用计数回收。
 */
class TestContentStoreCompactor {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var config: AppConfig

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("content-store-compactor-test")
        ContentStoreManager.resetForTest() // 清空全局同步单元缓存，避免跨测试污染
        config = AppConfig.load().copy(contentDir = tempDir.resolve("content").toString())
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun openStore(storeDir: String, name: String): ContentStoreService =
        ContentStoreService(name = name, baseDir = Path(config.contentDir, storeDir).toString())

    private fun dataFile(storeDir: String, name: String): java.io.File =
        Path(config.contentDir, storeDir, name, "data.log").toFile()

    private fun syncFor(storeDir: String, name: String): ContentStoreSync =
        ContentStoreManager.getOrCreateSync(name, Path(config.contentDir, storeDir).toString())

    /** 测试用低阈值配置：排除 minLiveBytes / maxFileBytes 干扰，聚焦垃圾占比筛选 */
    private fun compactorConfig(): AppConfig = config.copy(
        contentStoreCompactGarbageThreshold = 0.4,
        contentStoreCompactMinLiveBytes = 1,
        contentStoreCompactMaxFileBytes = Long.MAX_VALUE
    )

    // ================= 指标与阈值判断 =================

    @Test
    fun `垃圾占比与阈值判断`() = runTest {
        val store = openStore("book", "1")
        store.update(1, "内容A") // 单次写入：无垃圾

        val sync = syncFor("book", "1")
        try {
            assertFalse(sync.needsCompact(0.4, 0, Long.MAX_VALUE), "单次写入不应需要 compact")
            assertEquals(0.0, sync.garbageRatio(), 0.001, "单次写入垃圾占比应为 0")

            // 同一章节反复更新：旧版本成为垃圾
            repeat(10) { i ->
                store.update(1, "内容A-更新-$i")
            }

            assertTrue(sync.garbageRatio() > 0.4, "垃圾占比应超过 40%")
            assertTrue(sync.needsCompact(0.4, 0, Long.MAX_VALUE), "垃圾占比高时应需要 compact")

            // 有效字节低于下限时不整理
            assertFalse(
                sync.needsCompact(0.4, Long.MAX_VALUE, Long.MAX_VALUE),
                "有效字节不足下限时不应整理"
            )
        } finally {
            ContentStoreManager.releaseSync("1", Path(config.contentDir, "book").toString())
        }
        store.close()
    }

    // ================= 定时扫描行为 =================

    @Test
    fun `compactAll 只整理需要整理的 store`() = runTest {
        // 脏 store：同一章节反复更新，垃圾占比高
        openStore("book", "1").use { s ->
            repeat(10) { i -> s.update(1, "脏数据-$i") }
            s.update(2, "另一个章节")
        }
        // 健康 store：每章节只写一次
        openStore("book", "2").use { s ->
            repeat(5) { i -> s.update(i + 1, "健康章节-$i") }
        }

        val dirtySizeBefore = dataFile("book", "1").length()
        val cleanSizeBefore = dataFile("book", "2").length()

        val compactor = ContentStoreCompactor(compactorConfig(), logger)
        val compacted = compactor.compactAll()

        assertEquals(1, compacted, "只应整理脏 store")
        assertTrue(dataFile("book", "1").length() < dirtySizeBefore, "脏 store 文件应缩小")
        assertEquals(cleanSizeBefore, dataFile("book", "2").length(), "健康 store 不应被改写")

        // 数据仍正确
        openStore("book", "1").use { s ->
            assertEquals("脏数据-9", s.readChapter(1))
            assertEquals(listOf(1, 2), s.toList())
        }
        openStore("book", "2").use { s ->
            assertEquals(listOf(1, 2, 3, 4, 5), s.toList())
        }
    }

    @Test
    fun `force 整理所有 store`() = runTest {
        openStore("comment/chapter", "7").use { s ->
            s.update(1, "c1")
            s.update(2, "c2")
        }

        val compactor = ContentStoreCompactor(compactorConfig(), logger)
        val compacted = compactor.compactAll(force = true)

        assertEquals(1, compacted, "force 应整理健康 store")
        openStore("comment/chapter", "7").use { s ->
            assertEquals(listOf(1, 2), s.toList())
            assertEquals("c1", s.readChapter(1))
            assertEquals("c2", s.readChapter(2))
        }
    }

    @Test
    fun `无 store 时返回 0`() = runTest {
        val compactor = ContentStoreCompactor(compactorConfig(), logger)
        assertEquals(0, compactor.compactAll())
    }

    @Test
    fun `compactAll 后同步单元引用计数归零`() = runTest {
        openStore("book", "9").use { s -> s.update(1, "x") }

        ContentStoreCompactor(compactorConfig(), logger).compactAll(force = true)

        assertFalse(
            ContentStoreManager.containsSync("9", Path(config.contentDir, "book").toString()),
            "compactor 应归还引用，业务关闭后同步单元应被回收"
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger("ContentStoreCompactorTest")
    }
}
