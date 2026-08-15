package com.qianrenni.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jpountz.lz4.LZ4Factory
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path

/**
 * 允许并发读、互斥写的协程同步器。
 * - 读锁之间不互斥
 * - 写锁与所有读锁及写锁互斥
 * - 写锁会等待所有已持有读锁释放后再获取
 */
class CoroutineReadWriteLock {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private var readers = 0

    private suspend fun readLock() {
        readMutex.withLock {
            readers++
            if (readers == 1) writeMutex.lock()   // 第一个读者锁住写
        }
    }

    private suspend fun readUnlock() {
        readMutex.withLock {
            readers--
            if (readers == 0) writeMutex.unlock() // 最后一个读者释放写
        }
    }

    suspend fun <T> withReadLock(block: suspend () -> T): T {
        readLock()
        try {
            return block()
        } finally {
            readUnlock()
        }
    }

    suspend fun <T> withWriteLock(block: suspend () -> T): T {
        writeMutex.lock()
        try {
            return block()
        } finally {
            writeMutex.unlock()
        }
    }
}

/**
 * 封装同一本书的所有同步状态和文件操作。
 * 该对象全局唯一（由 ChapterStoreManager 保证），
 * 同一本书的所有操作都必须通过此单元进行。
 */
class ContentStoreSync(
    val dir: Path,
    val dataFile: File,
    val indexFile: File,
    val compressThreshold: Int = 1024
) {
    companion object {
        // 二进制记录头大小
        private const val RECORD_HEADER_SIZE = 22
    }

    private val refCount = AtomicInteger(0)

    fun acquire() = refCount.incrementAndGet()
    fun release(): Int = refCount.decrementAndGet()

    // ---- 同步原语 ----
    val writeMutex = Mutex()                 // 写写互斥
    val rwLock = CoroutineReadWriteLock()    // 读写隔离（主要用于 compact）

    @Volatile
    var indexSnapshot: Map<Int, Record> = emptyMap()
        private set

    // LZ4 工厂（线程安全，可共享）
    val lz4Factory: LZ4Factory = LZ4Factory.fastestInstance()

    // 索引是否已加载
    private val loadLock = Mutex()

    @Volatile
    private var loaded = false

    // 章节记录元数据（与原始定义完全一致）
    data class Record(
        val offset: Long,
        val contentSize: Int,
        val originalSize: Int,
        val isDelete: Boolean,
        val isCompress: Boolean,
        val timestamp: Long
    )

    suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            loadIndexInternal()
            loaded = true
        }
    }

    private suspend fun loadIndexInternal() {
        // 1. 优先从 index.idx 快速加载
        if (indexFile.exists()) {
            withContext(Dispatchers.IO) {
                val data = indexFile.readBytes()
                if (data.isNotEmpty()) {
                    val unpacker = MessagePack.newDefaultUnpacker(data)
                    val mapSize = unpacker.unpackArrayHeader()
                    val map = mutableMapOf<Int, Record>()
                    repeat(mapSize) {
                        val chapterId = unpacker.unpackInt()
                        map[chapterId] = Record(
                            offset = unpacker.unpackLong(),
                            contentSize = unpacker.unpackInt(),
                            originalSize = unpacker.unpackInt(),
                            isDelete = unpacker.unpackBoolean(),
                            isCompress = unpacker.unpackBoolean(),
                            timestamp = unpacker.unpackLong()
                        )
                    }
                    indexSnapshot = map
                }
            }
            return
        }

        // 2. 回退：从 data.log 重建索引
        if (!dataFile.exists()) return

        withContext(Dispatchers.IO) {
            val channel = AsynchronousFileChannel.open(
                dataFile.toPath(), StandardOpenOption.READ
            )
            channel.use { ch ->
                var position = 0L
                val headerBuf = ByteBuffer.allocate(RECORD_HEADER_SIZE)
                val map = mutableMapOf<Int, Record>()

                while (isActive) {
                    headerBuf.clear()
                    val bytesRead = ch.read(headerBuf, position).get()
                    if (bytesRead < RECORD_HEADER_SIZE) break
                    headerBuf.flip()

                    val chapterId = headerBuf.int
                    val contentSize = headerBuf.int
                    val originalSize = headerBuf.int
                    val deleted = headerBuf.get() != 0.toByte()
                    val isCompress = headerBuf.get() != 0.toByte()
                    val timestamp = headerBuf.long
                    val offset = position + RECORD_HEADER_SIZE

                    map[chapterId] = Record(
                        offset = offset,
                        contentSize = contentSize,
                        originalSize = originalSize,
                        isDelete = deleted,
                        isCompress = isCompress,
                        timestamp = timestamp
                    )
                    position += RECORD_HEADER_SIZE + contentSize
                }
                indexSnapshot = map
            }
        }

        // 重建后立即持久化索引，加速下次启动
        saveIndexSnapshot(indexSnapshot)
    }

    // -------------------- 索引持久化 --------------------
    private suspend fun saveIndexSnapshot(snapshot: Map<Int, Record>) {
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(out)
            packer.packArrayHeader(snapshot.size)
            snapshot.forEach { (chapterId, record) ->
                packer.packInt(chapterId)
                packer.packLong(record.offset)
                packer.packInt(record.contentSize)
                packer.packInt(record.originalSize)
                packer.packBoolean(record.isDelete)
                packer.packBoolean(record.isCompress)
                packer.packLong(record.timestamp)
            }
            packer.close()
            indexFile.writeBytes(out.toByteArray())
        }
    }

    // （追加记录）
    suspend fun appendRecord(
        contentId: Int,
        content: String,
        deleted: Boolean = false
    ) {
        // 确保索引已加载（若首次使用）
        ensureLoaded()

        val ts = System.currentTimeMillis()
        var data = content.toByteArray(Charsets.UTF_8)
        val originalSize = data.size
        val isCompressByte = if (data.size > compressThreshold) 1.toByte() else 0.toByte()

        if (isCompressByte == 1.toByte()) {
            val compressor = lz4Factory.fastCompressor()
            val maxLen = compressor.maxCompressedLength(data.size)
            val temp = ByteArray(maxLen)
            val compressedLen = compressor.compress(data, 0, data.size, temp, 0, maxLen)
            data = temp.copyOf(compressedLen)
        }

        val header = ByteBuffer.allocate(RECORD_HEADER_SIZE).apply {
            putInt(contentId)
            putInt(data.size)
            putInt(originalSize)
            put(if (deleted) 1.toByte() else 0.toByte())
            put(isCompressByte)
            putLong(ts)
            flip()
        }

        // 在写锁保护下进行文件追加和索引更新
        writeMutex.withLock {
            var recordOffset: Long
            withContext(Dispatchers.IO) {
                val channel = AsynchronousFileChannel.open(
                    dataFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
                )
                channel.use { ch ->
                    val fileSize = ch.size()
                    recordOffset = fileSize + RECORD_HEADER_SIZE

                    // 写入 header
                    ch.write(header, fileSize).get()
                    // 写入 content
                    val contentBuf = ByteBuffer.wrap(data)
                    ch.write(contentBuf, fileSize + RECORD_HEADER_SIZE).get()
                }
            }

            // 更新不可变索引快照
            val newMap = indexSnapshot.toMutableMap()
            newMap[contentId] = Record(
                offset = recordOffset,
                contentSize = data.size,
                originalSize = originalSize,
                isDelete = deleted,
                isCompress = isCompressByte == 1.toByte(),
                timestamp = ts
            )
            indexSnapshot = newMap

            // 持久化索引（可优化为批量/异步）
            saveIndexSnapshot(newMap)
        }
    }

    // -------------------- 读操作 --------------------
    suspend fun readChapter(contentId: Int): String {
        ensureLoaded()

        return rwLock.withReadLock {
            val snap = indexSnapshot
            val record = snap[contentId] ?: throw IllegalStateException("Chapter $contentId not found")
            if (record.isDelete) return@withReadLock ""

            return@withReadLock withContext(Dispatchers.IO) {
                val channel = AsynchronousFileChannel.open(
                    dataFile.toPath(), StandardOpenOption.READ
                )
                channel.use { ch ->
                    val buf = ByteBuffer.allocate(record.contentSize)
                    val bytesRead = ch.read(buf, record.offset).get()
                    if (bytesRead <= 0)
                        throw IllegalStateException("Unexpected end of file while reading chapter $contentId")
                    buf.flip()

                    var targetArray = buf.array()
                    if (record.isCompress) {
                        val decompressor = lz4Factory.fastDecompressor()
                        val restored = ByteArray(record.originalSize)
                        decompressor.decompress(buf.array(), 0, restored, 0, record.originalSize)
                        targetArray = restored
                    }
                    String(targetArray, Charsets.UTF_8)
                }
            }
        }
    }

    // -------------------- 更新与删除 --------------------
    suspend fun update(contentId: Int, content: String) {
        appendRecord(contentId = contentId, content = content, deleted = false)
    }

    suspend fun delete(contentId: Int) {
        val currentSnap = indexSnapshot
        if (currentSnap[contentId]?.isDelete == true) return // 幂等
        appendRecord(contentId = contentId, content = "", deleted = true)
    }

    // -------------------- 章节列表 --------------------
    fun toList(): List<Int> {
        return indexSnapshot
            .filter { !it.value.isDelete }
            .keys
            .sorted()
    }

    // -------------------- 压缩整理 --------------------
    suspend fun compact() {
        ensureLoaded()

        // 写锁保证没有其他写操作，读写锁的写锁等待所有读完成并阻止新读
        writeMutex.withLock {
            rwLock.withWriteLock {
                // 保留墓碑记录：已删除章节 compact 后仍须可读（返回空串），
                // 否则 compact 前后 readChapter 语义漂移（已删从空串变为抛异常）
                val validRecords = mutableListOf<Triple<Int, String, Boolean>>()
                val snap = indexSnapshot
                for ((chapterId, record) in snap) {
                    if (record.isDelete) {
                        validRecords.add(Triple(chapterId, "", true))
                    } else {
                        val content = readChapterUnsafe(record) // 内部无锁读取
                        validRecords.add(Triple(chapterId, content, false))
                    }
                }

                // 写入临时文件
                val tempPath = dir.resolve("data.log.tmp")
                val newMap = mutableMapOf<Int, Record>()
                var currentOffset = 0L

                withContext(Dispatchers.IO) {
                    val channel = AsynchronousFileChannel.open(
                        tempPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                    )
                    channel.use { ch ->
                        for ((chapterId, content, isDelete) in validRecords) {
                            val ts = System.currentTimeMillis()
                            var data = content.toByteArray(Charsets.UTF_8)
                            val originalSize = data.size
                            val isCompressByte = if (data.size > compressThreshold) 1.toByte() else 0.toByte()

                            if (isCompressByte == 1.toByte()) {
                                val compressor = lz4Factory.fastCompressor()
                                val maxLen = compressor.maxCompressedLength(data.size)
                                val temp = ByteArray(maxLen)
                                val compressedLen = compressor.compress(data, 0, data.size, temp, 0, maxLen)
                                data = temp.copyOf(compressedLen)
                            }

                            val header = ByteBuffer.allocate(RECORD_HEADER_SIZE).apply {
                                putInt(chapterId)
                                putInt(data.size)
                                putInt(originalSize)
                                put(if (isDelete) 1.toByte() else 0.toByte())
                                put(isCompressByte)
                                putLong(ts)
                                flip()
                            }

                            ch.write(header, currentOffset).get()
                            val contentOffset = currentOffset + RECORD_HEADER_SIZE
                            ch.write(ByteBuffer.wrap(data), contentOffset).get()

                            newMap[chapterId] = Record(
                                offset = contentOffset,
                                contentSize = data.size,
                                originalSize = originalSize,
                                isDelete = isDelete,
                                isCompress = isCompressByte == 1.toByte(),
                                timestamp = ts
                            )
                            currentOffset += RECORD_HEADER_SIZE + data.size
                        }
                    }
                }

                // 原子替换原文件
                withContext(Dispatchers.IO) {
                    Files.move(
                        tempPath, dataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                }

                // 更新快照并持久化
                indexSnapshot = newMap
                saveIndexSnapshot(newMap)
            }
        }
    }

    // 内部使用：无需加锁，仅用于 compact 时读取已验证记录
    private suspend fun readChapterUnsafe(record: Record): String {
        return withContext(Dispatchers.IO) {
            val channel = AsynchronousFileChannel.open(
                dataFile.toPath(), StandardOpenOption.READ
            )
            channel.use { ch ->
                val buf = ByteBuffer.allocate(record.contentSize)
                ch.read(buf, record.offset).get()
                buf.flip()
                var target = buf.array()
                if (record.isCompress) {
                    val decompressor = lz4Factory.fastDecompressor()
                    val restored = ByteArray(record.originalSize)
                    decompressor.decompress(buf.array(), 0, restored, 0, record.originalSize)
                    target = restored
                }
                String(target, Charsets.UTF_8)
            }
        }
    }
}

/**
 * 管理所有书籍的同步单元，确保每个 (baseDir, bookId) 全局只有一个同步单元。
 */
object ContentStoreManager {
    private val stores = ConcurrentHashMap<Path, ContentStoreSync>()

    /**
     * 获取或创建指定书籍的同步单元。
     * 该方法是线程安全的，但不会自动加载索引，索引加载由各自的 ensureLoaded 按需触发。
     */
    fun getOrCreateSync(name: String, baseDir: String): ContentStoreSync {
        val dir = Path(baseDir, name)
        return stores.compute(dir) { path, existing ->
            if (existing != null) {
                existing.acquire()
                existing
            } else {
                // 创建目录和文件路径
                path.toFile().mkdirs()
                val dataFile = path.resolve("data.log").toFile()
                val indexFile = path.resolve("index.idx").toFile()
                val sync = ContentStoreSync(path, dataFile, indexFile)
                sync.acquire()
                sync
            }
        }!!
    }

    fun releaseSync(name: String, baseDir: String) {
        val dir = Path(baseDir, name)
        stores.computeIfPresent(dir) { _, sync ->
            val count = sync.release()
            if (count <= 0) {
                // 可以在这里做额外清理，如关闭文件（目前无持久打开）
                null
            } else {
                sync
            }
        }
    }

    /** 测试用：判断指定书籍的同步单元是否已缓存 */
    internal fun containsSync(name: String, baseDir: String): Boolean {
        val dir = Path(baseDir, name)
        return stores.containsKey(dir)
    }

    /** 测试用：清空所有缓存（注意：仅应在测试中调用） */
    internal fun resetForTest() {
        stores.clear()
    }
}

/**
 * 章节存储服务。
 * 可以创建多个实例，但同一本书的操作最终共享同一个底层同步单元。
 * 使用方式与原类完全一致，但内部已是多协程安全。
 */
class ContentStoreService(
    private val name: String,
    private val baseDir: String
) : AutoCloseable, ChapterStore {
    private val sync: ContentStoreSync = ContentStoreManager.getOrCreateSync(name, baseDir)

    // 显式初始化索引（可在应用启动时调用，非必须，因为后续操作会延迟加载）

    /** 读取章节内容，若已删除返回空字符串 */
    override suspend fun readChapter(contentId: Int): String = sync.readChapter(contentId)

    /** 新增或更新章节 */
    override suspend fun update(contentId: Int, content: String) = sync.update(contentId, content)

    /** 逻辑删除章节（幂等） */
    override suspend fun delete(contentId: Int) = sync.delete(contentId)

    /** 获取所有有效章节 ID 列表（升序） */
    fun toList(): List<Int> = sync.toList()

    /** 执行 compact 整理 */
    suspend fun compact() = sync.compact()
    override fun close() {
        ContentStoreManager.releaseSync(name, baseDir)
    }
}