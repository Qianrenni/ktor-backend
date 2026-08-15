package com.qianrenni.infrastructure.storage

import com.qianrenni.common.AppConfig
import com.qianrenni.common.config.ConfigService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * ContentStore 自动 compact 编排器（方案 C：定时扫描 + 垃圾占比阈值过滤）。
 *
 * 触发方式：由 TaskManager 按 [AppConfig.contentStoreCompactCron] 定时调用 [compactAll]。
 *
 * 行为约定：
 * - 同一时刻仅允许一轮扫描（[sweepMutex]），避免 cron 重叠导致并发整理；
 * - 递归遍历 [AppConfig.contentDir]，任何含 `data.log` 的目录视为一个 store；
 * - 仅对满足 `needsCompact` 阈值（垃圾占比 / 最小有效字节 / 最大文件大小）的 store 执行 compact，
 *   健康 store 不做任何 I/O；
 * - 阈值取自动态配置（[ConfigService.compact]），支持运行期调整；
 * - 复用 [ContentStoreManager] 的共享同步单元（引用计数 acquire/release），
 *   与业务读写天然互斥——[ContentStoreSync.compact] 内部自带写锁 + 读写锁，
 *   不会破坏既有的并发读写语义；
 * - 单个 store 整理失败只记日志，不影响其余 store。
 */
class ContentStoreCompactor(
    private val config: AppConfig,
    private val configService: ConfigService,
    private val logger: Logger,
) {
    private val sweepMutex = Mutex()

    /**
     * 扫描并整理所有需要 compact 的 store。
     *
     * @param force true 时忽略阈值，整理全部 store（用于手动触发/测试）
     * @return 本次实际执行的 compact 次数
     */
    suspend fun compactAll(force: Boolean = false): Int {
        sweepMutex.withLock {
            val root = Path(config.contentDir)
            if (!root.exists()) return 0

            val stores = Files.walk(root).use { stream ->
                stream
                    .filter { it.resolve("data.log").toFile().exists() }
                    .toList()
            }
            if (stores.isEmpty()) return 0

            logger.info("内容存储自动compact扫描：共发现 {} 个 store", stores.size)
            val dynamic = configService.compact() // 动态阈值（可运行期调整）
            var compacted = 0
            for (storeDir in stores) {
                try {
                    val name = storeDir.name
                    val baseDir = storeDir.parent.toString()
                    val sync = ContentStoreManager.getOrCreateSync(name, baseDir)
                    try {
                        val need = force || sync.needsCompact(
                            garbageThreshold = dynamic.garbageThreshold,
                            minLiveBytes = dynamic.minLiveBytes,
                            maxFileBytes = dynamic.maxFileBytes
                        )
                        if (need) {
                            logger.info("自动compact store: {} / {}", baseDir, name)
                            sync.compact()
                            compacted++
                        }
                    } finally {
                        // 归还引用计数；若业务未持有则同步单元会被回收，下次按需重新加载
                        ContentStoreManager.releaseSync(name, baseDir)
                    }
                } catch (e: Exception) {
                    logger.error("自动compact store 失败: $storeDir: ${e.message}", e)
                }
            }
            logger.info("内容存储自动compact完成：共整理 {} 个 store", compacted)
            return compacted
        }
    }
}
