package com.qianrenni.models.tables

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * Outbox 文件操作类型
 */
enum class OutboxOp {
    UPDATE, // 写入/更新内容（携带 content）
    DELETE; // 逻辑删除内容（墓碑，幂等）
}

/**
 * Outbox 处理状态
 */
enum class OutboxStatus {
    PENDING, // 待处理（重试失败后仍保持 PENDING 以便下次重试）
    SUCCESS, // 文件操作已成功
    FAILED;  // 重试次数耗尽，需人工介入
}

/**
 * 文件同步 Outbox 表。
 *
 * 业务元数据（book/book_chapter/book_comment 等）与 outbox 记录必须在**同一个 DB 事务**中写入，
 * 事务提交后由 OutboxService 后台消费 outbox 执行对应的文件操作（ContentStoreService），
 * 从而保证"DB 元数据 + 文件内容"的最终一致。文件操作本身幂等（update=追加+索引快照覆盖、
 * delete=墓碑追加），因此消费失败后可安全重试。
 *
 * - storeDir: 存储目录相对 contentDir 的路径（book / comment/book / comment/chapter）
 * - storeName: ContentStoreService 的 name（bookId 或 chapterId）
 * - contentId: ContentStoreService 的 contentId（章节 ID 或评论 ID）
 */
object FileSyncOutboxTable : IntIdTable(name = "file_sync_outbox") {
    val storeDir = varchar("storeDir", 64)
    val storeName = varchar("storeName", 64)
    val contentId = integer("contentId")
    val op = enumerationByName<OutboxOp>("op", 16)
    val content = largeText("content").nullable()
    val status = enumerationByName<OutboxStatus>("status", 16).default(OutboxStatus.PENDING)
    val retryCount = integer("retryCount").default(0)
    val errorMessage = text("errorMessage").nullable()
    val createdAt = datetime("createdAt").default(LocalDateTime.now())
    val updatedAt = datetime("updatedAt").default(LocalDateTime.now())
}

/**
 * Outbox 待处理记录（消费时使用）
 */
data class OutboxRecord(
    val id: Int,
    val storeDir: String,
    val storeName: String,
    val contentId: Int,
    val op: OutboxOp,
    val content: String?,
    val retryCount: Int
)

fun ResultRow.toOutboxRecord() = OutboxRecord(
    id = this[FileSyncOutboxTable.id].value,
    storeDir = this[FileSyncOutboxTable.storeDir],
    storeName = this[FileSyncOutboxTable.storeName],
    contentId = this[FileSyncOutboxTable.contentId],
    op = this[FileSyncOutboxTable.op],
    content = this[FileSyncOutboxTable.content],
    retryCount = this[FileSyncOutboxTable.retryCount]
)
