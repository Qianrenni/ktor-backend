package com.qianrenni.infrastructure.outbox

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.storage.ChapterStoreFactory
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.FileSyncOutboxTable
import com.qianrenni.models.tables.OutboxOp
import com.qianrenni.models.tables.OutboxRecord
import com.qianrenni.models.tables.OutboxStatus
import com.qianrenni.models.tables.toOutboxRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.Logger
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path

/**
 * 事务提交后的文件同步信号通道。
 * - CONFLATED：信号只保留最新一个，多个登记合并为一次通知——消费方以 DB 的 PENDING 记录为准，
 *   信号仅作为"有新活"的触发器，合并不会丢失任何待处理记录
 * - 通知时机在事务提交之后（见 [enqueueFileSync] 的 afterCommit 钩子），
 *   因此消费者收到信号后查询 PENDING 时记录必然已经可见
 */
private val outboxNotifyChannel = Channel<Unit>(Channel.CONFLATED)

/**
 * 在"当前已开启的 DB 事务"内登记一条文件同步操作，并在**事务提交后**通知消费者。
 *
 * 必须与业务记录在同一事务中调用；事务提交后由 [OutboxService] 消费并执行文件操作。
 * 事务回滚时 afterCommit 不会触发，因此不会产生"记录不存在却收到通知"的幽灵信号。
 */
fun enqueueFileSync(
    storeDir: String,
    storeName: String,
    contentId: Int,
    op: OutboxOp,
    content: String? = null
) {
    FileSyncOutboxTable.insert {
        it[FileSyncOutboxTable.storeDir] = storeDir
        it[FileSyncOutboxTable.storeName] = storeName
        it[FileSyncOutboxTable.contentId] = contentId
        it[FileSyncOutboxTable.op] = op
        it[FileSyncOutboxTable.content] = content
    }
    // 注册提交钩子：事务提交成功后才向 channel 发送信号（Exposed 0.59 无 registerHook，
    // StatementInterceptor.afterCommit 在 Transaction.commit() 中于落库之后被调用）
    TransactionManager.current().registerInterceptor(object : StatementInterceptor {
        override fun afterCommit(transaction: Transaction) {
            outboxNotifyChannel.trySend(Unit)
        }
    })
}

/**
 * Outbox 消费服务：Channel 事件驱动 + 低频轮询兜底，执行对应的 ContentStoreService 文件操作。
 *
 * - **即时消费**：业务事务提交时向 [outboxNotifyChannel] 发信号，服务启动后即监听该 channel，
 *   收到信号立刻批量消费，无需等待轮询周期
 * - **兜底轮询**：启动时先消费一次遗留记录（信号不持久化，进程重启后旧信号已丢失）；
 *   另有低频定时任务兜底（见 Application.kt），覆盖多实例/信号极端丢失场景
 * - 按 id 升序消费，保证同一内容上的文件操作顺序与登记顺序一致（追加式日志语义）
 * - 文件操作幂等（update=追加+索引快照覆盖、delete=墓碑），失败重试；重试耗尽标记 FAILED 待人工处理
 * - 单实例内通过 AtomicBoolean 防止消费循环与定时任务并发执行
 */
class OutboxService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val logger: Logger,
    private val chapterStoreFactory: ChapterStoreFactory,
) {
    companion object {
        private const val MAX_RETRY = 5
        private const val BATCH_SIZE = 100
        private const val SUCCESS_KEEP_DAYS = 7L
    }

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 启动消费：先处理一次遗留记录，再进入 channel 监听循环。
     * 由生产装配（Application.main 的 `services.outboxService.start()`）在应用启动时调用；
     * 测试模式不调用本方法，由测试手动触发 [processPending]。
     */
    fun start() {
        scope.launch {
            // 处理上次进程退出前未消费的 PENDING（channel 信号不持久化，重启后需从 DB 兜底）
            drainPending()
        }
        scope.launch {
            // 监听提交信号：事务提交即触发消费，PENDING 数据以 DB 为准
            for (signal in outboxNotifyChannel) {
                drainPending()
            }
        }
    }

    /**
     * 登记文件同步操作（等价于 [enqueueFileSync]），供各 Service 在业务事务内调用。
     * 写入完成后由 channel 通知消费者，保证"先落库、后通知、查询必有内容"。
     */
    fun write(
        storeDir: String,
        storeName: String,
        contentId: Int,
        op: OutboxOp,
        content: String? = null
    ) {
        enqueueFileSync(storeDir, storeName, contentId, op, content)
    }

    /** 循环消费，直到没有待处理记录为止 */
    private suspend fun drainPending() {
        while (processPending() > 0) {
            // 上一批恰好取满 BATCH_SIZE 时继续取下一批，直到清空积压
        }
    }

    /**
     * 消费一批待处理记录，返回本批处理条数。
     * 无待处理记录时顺带清理过期 SUCCESS 记录。
     */
    suspend fun processPending(): Int {
        if (!running.compareAndSet(false, true)) {
            logger.debug("Outbox 消费已在执行，跳过本次")
            return 0
        }
        try {
            val pending = databaseManager.suspendedTransaction(readOnly = true) {
                FileSyncOutboxTable.selectAll()
                    .where { FileSyncOutboxTable.status eq OutboxStatus.PENDING }
                    .orderBy(FileSyncOutboxTable.id to SortOrder.ASC)
                    .limit(BATCH_SIZE)
                    .map { it.toOutboxRecord() }
            }
            if (pending.isEmpty()) {
                cleanupSuccessRecords()
                return 0
            }
            logger.info("Outbox 消费：处理 {} 条待同步文件操作", pending.size)
            pending.forEach { record ->
                processOne(record)
            }
            return pending.size
        } catch (e: Exception) {
            logger.error("Outbox 消费异常: {}", e.message, e)
            return 0
        } finally {
            running.set(false)
        }
    }

    private suspend fun processOne(record: OutboxRecord) {
        try {
            chapterStoreFactory.open(record.storeDir, record.storeName).use { store ->
                when (record.op) {
                    OutboxOp.UPDATE -> store.update(
                        contentId = record.contentId,
                        content = record.content ?: throw IllegalStateException("UPDATE 操作缺少内容")
                    )
                    OutboxOp.DELETE -> store.delete(record.contentId)
                }
            }
            markSuccess(record.id)
        } catch (e: Exception) {
            handleFailure(record, e)
        }
    }

    private suspend fun markSuccess(id: Int) {
        databaseManager.suspendedTransaction {
            FileSyncOutboxTable.update({ FileSyncOutboxTable.id eq id }) {
                it[FileSyncOutboxTable.status] = OutboxStatus.SUCCESS
                it[FileSyncOutboxTable.errorMessage] = null
                it[FileSyncOutboxTable.updatedAt] = LocalDateTime.now()
            }
        }
    }

    private suspend fun handleFailure(record: OutboxRecord, e: Exception) {
        val newRetry = record.retryCount + 1
        val giveUp = newRetry >= MAX_RETRY
        databaseManager.suspendedTransaction {
            FileSyncOutboxTable.update({ FileSyncOutboxTable.id eq record.id }) {
                it[FileSyncOutboxTable.retryCount] = newRetry
                it[FileSyncOutboxTable.errorMessage] = e.message?.take(1000)
                it[FileSyncOutboxTable.updatedAt] = LocalDateTime.now()
                if (giveUp) {
                    it[FileSyncOutboxTable.status] = OutboxStatus.FAILED
                }
            }
        }
        logger.warn(
            "Outbox 记录 {}（{}:{} contentId={} op={}）文件操作失败，第 {} 次重试{}：{}",
            record.id, record.storeDir, record.storeName, record.contentId, record.op,
            newRetry, if (giveUp) "，已标记 FAILED" else "", e.message
        )
    }

    /** 清理超过保留期的 SUCCESS 记录，防止表无限膨胀 */
    private suspend fun cleanupSuccessRecords() {
        val deleted = databaseManager.suspendedTransaction {
            FileSyncOutboxTable.deleteWhere {
                (FileSyncOutboxTable.status eq OutboxStatus.SUCCESS) and
                        (FileSyncOutboxTable.updatedAt less LocalDateTime.now().minusDays(SUCCESS_KEEP_DAYS))
            }
        }
        if (deleted > 0) {
            logger.debug("Outbox 清理过期 SUCCESS 记录 {} 条", deleted)
        }
    }
}
