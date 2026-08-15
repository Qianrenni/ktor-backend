package com.qianrenni.infrastructure.storage

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.storage.ContentStoreManager
import com.qianrenni.infrastructure.storage.ContentStoreService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class TestContentStoreService {

    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("chapter-store-new-test")
        ContentStoreManager.resetForTest() // 每个测试前清空全局缓存，避免交叉影响
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ================= 原有基础测试 =================
    @Test
    fun testCRUD() = runTest {
        val store = ContentStoreService(name = "1", baseDir = tempDir.toString())
        store.update(1, "第一章内容")
        store.update(2, "第二章内容")
        store.update(3, "第三章内容")

        assertEquals("第一章内容", store.readChapter(1))
        assertEquals(listOf(1, 2, 3), store.toList())

        store.update(1, "第一章更新内容")
        assertEquals("第一章更新内容", store.readChapter(1))

        store.delete(2)
        assertEquals(listOf(1, 3), store.toList())
        assertEquals("", store.readChapter(2))
    }

    @Test
    fun testPersistence() = runTest {
        val store1 = ContentStoreService(name = "2", baseDir = tempDir.toString())
        store1.update(1, "持久化测试内容")
        store1.update(2, "第二章内容")

        val store2 = ContentStoreService(name = "2", baseDir = tempDir.toString())
        assertEquals(listOf(1, 2), store2.toList())
        assertEquals("持久化测试内容", store2.readChapter(1))
    }

    @Test
    fun testCompact() = runTest {
        val store = ContentStoreService(name = "3", baseDir = tempDir.toString())
        store.update(1, "初始内容")
        store.update(1, "第一次更新")
        store.update(1, "第二次更新")
        store.update(2, "临时章节")
        store.delete(2)
        store.compact()

        assertEquals(listOf(1), store.toList())
        assertEquals("第二次更新", store.readChapter(1))
    }

    @Test
    fun testLargeContent() = runTest {
        val store = ContentStoreService(name = "4", baseDir = tempDir.toString())
        val contentSize = 10 * 1024
        val randomContent = buildString {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;':\",./<>?~"
            repeat(contentSize) { append(chars[Random.nextInt(chars.length)]) }
        }
        assertTrue(randomContent.length > 1024)

        store.update(1, randomContent)
        val readBack = store.readChapter(1)
        assertEquals(randomContent, readBack)

        val store2 = ContentStoreService(name = "4", baseDir = tempDir.toString())
        assertEquals(randomContent, store2.readChapter(1))
    }

    // ================= 新增并发测试 =================

    /**
     * 并发写入不同章节，验证所有写入最终可见且无数据错乱。
     */
    @Test
    fun testConcurrentWritesToDifferentChapters() = runTest {
        val store = ContentStoreService(name = "100", baseDir = tempDir.toString())
        val chapterCount = 20
        val jobs = (1..chapterCount).map { chapterId ->
            launch(Dispatchers.Default) {
                store.update(chapterId, "Chapter-$chapterId")
            }
        }
        jobs.joinAll()

        val chapters = store.toList()
        assertEquals(chapterCount, chapters.size, "All chapters should be written")
        for (id in 1..chapterCount) {
            assertEquals("Chapter-$id", store.readChapter(id))
        }
    }

    /**
     * 并发写入同一章节，验证最后一次写入的值生效（最终一致）。
     */
    @Test
    fun testConcurrentWritesToSameChapter() = runTest {
        val store = ContentStoreService(name = "101", baseDir = tempDir.toString())
        val coroutineCount = 10
        val results = mutableListOf<Deferred<String>>()
        repeat(coroutineCount) { index ->
            results.add(async(Dispatchers.Default) {
                val content = "Write-$index"
                store.update(1, content)
                content
            })
        }
        val writtenContents = results.awaitAll()
        val finalContent = store.readChapter(1)
        // 最终值必定是某一次写入的内容
        assertTrue(finalContent in writtenContents, "Final content should be one of the written values")
    }

    /**
     * 并发读与写：多个读者在写入前后读取，确保数据始终为完整值（不会读到一半）。
     */
    @Test
    fun testConcurrentReadsAndWrites() = runTest {
        val store = ContentStoreService(name = "102", baseDir = tempDir.toString())
        store.update(1, "InitialValue")
        val readJobs = mutableListOf<Deferred<List<String>>>()
        val writeJob = async(Dispatchers.Default) {
            delay(50.milliseconds) // 稍后写入
            store.update(1, "UpdatedValue")
        }
        // 启动 20 个读者，在写入前后持续读取
        repeat(20) {
            readJobs.add(async(Dispatchers.Default) {
                val reads = mutableListOf<String>()
                repeat(5) {
                    reads.add(store.readChapter(1))
                    delay(10.milliseconds)
                }
                reads
            })
        }
        writeJob.await()
        val allReads = readJobs.awaitAll().flatten()
        // 所有读取的值只能是 InitialValue 或 UpdatedValue，不应出现损坏或空字符串
        assertTrue(
            allReads.all { it == "InitialValue" || it == "UpdatedValue" },
            "Read values should be either initial or updated, but got: $allReads"
        )
    }

    /**
     * 并发删除与列出：删除和列表操作同时进行，列表不应包含已删除章节，且无异常。
     */
    @Test
    fun testConcurrentDeleteAndList() = runTest {
        val store = ContentStoreService(name = "103", baseDir = tempDir.toString())
        store.update(1, "C1")
        store.update(2, "C2")
        store.update(3, "C3")

        val deleteJob = launch(Dispatchers.Default) {
            delay(20.milliseconds)
            store.delete(2)
        }
        deleteJob.join()
        val listJobs = (1..10).map {
            async(Dispatchers.Default) {
                store.toList()
            }
        }

        val lists = listJobs.awaitAll()
        // 所有列表都不应包含已删除的章节 2
        assertTrue(lists.none { it.contains(2) }, "No list should contain deleted chapter 2")
        assertTrue(lists.all { it.all { id -> id in 1..3 } }, "All list entries should be valid chapter IDs")
    }

    /**
     * 并发 compact：compact 执行期间有持续的读写操作，compact 完成后数据必须正确。
     */
    @Test
    fun testCompactUnderConcurrency() = runTest {
        val store = ContentStoreService(name = "104", baseDir = tempDir.toString())
        // 先写入一些数据
        store.update(1, "V1")
        store.update(2, "V2")
        store.update(3, "V3")

        val compactJob = async(Dispatchers.Default) {
            delay(30.milliseconds)
            store.compact()
        }
        // 同时进行更新和读取
        val updater = async(Dispatchers.Default) {
            store.update(1, "V1-Updated")
            store.delete(2)
        }
        val reader = async(Dispatchers.Default) {
            delay(10.milliseconds)
            store.readChapter(3)
        }

        compactJob.await()
        updater.await()
        reader.await()

        // 最终验证：章节 1 应为更新值，章节 2 已删除，章节 3 仍存在
        assertEquals("V1-Updated", store.readChapter(1))
        assertEquals("", store.readChapter(2))
        assertEquals("V3", store.readChapter(3))
        assertEquals(listOf(1, 3), store.toList())
    }

    /**
     * 多实例并发与引用计数回收：
     * 多个 ChapterStoreService 实例同时使用，全部关闭后缓存应被释放。
     */
    @Test
    fun testMultiInstanceConcurrencyAndCleanup() = runTest {
        val bookId = 200
        val baseDir = tempDir.toString()

        // 创建第一个实例并写入初始数据
        val store1 = ContentStoreService(name = bookId.toString(), baseDir)
        store1.update(1, "shared")

        // 并发使用多个实例
        val jobs = mutableListOf<Job>()
        repeat(5) { index ->
            jobs.add(launch(Dispatchers.Default) {
                // 每个协程内创建自己的实例并执行操作
                ContentStoreService(name = bookId.toString(), baseDir).use { store ->
                    store.readChapter(1)
                    store.update(2, "from-$index")
                    // 简单读取
                    store.toList()
                }
            })
        }
        jobs.joinAll()

        // 确认仍有实例存活（store1 未关闭）
        assertTrue(
            ContentStoreManager.containsSync(bookId.toString(), baseDir),
            "Sync should still be cached because store1 is open"
        )

        // 关闭 store1
        store1.close()
        // 此时引用计数归零，缓存应被移除
        assertFalse(
            ContentStoreManager.containsSync(bookId.toString(), baseDir),
            "Sync should be removed after all instances closed"
        )

        // 再次打开新实例，应能正常加载原有数据
        val store2 = ContentStoreService(name = bookId.toString(), baseDir)
        assertEquals("shared", store2.readChapter(1))
        assertTrue(store2.toList().contains(2))
        store2.close()
    }
}