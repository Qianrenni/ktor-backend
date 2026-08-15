package com.qianrenni.modules.admin

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.infrastructure.storage.TxtChapterParser
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.common.BookStatus
import com.qianrenni.models.tables.*
import com.qianrenni.common.PageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import java.io.File
import kotlin.io.path.Path

class AdminService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val rightService: RightService,
    private val outboxService: OutboxService,
    private val cache: CacheService,
) {
    /**
     * 分页获取用户列表
     */
    suspend fun getUsers(page: Int, size: Int, keyword: String? = null): PageResult<AdminUserResponse> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            val offset = (page - 1) * size

            val query = UserTable.selectAll().let { q ->
                if (!keyword.isNullOrBlank()) {
                    q.andWhere {
                        (UserTable.userName like "%$keyword%") or
                                (UserTable.email like "%$keyword%")
                    }
                } else q
            }
            val total = query.count().toInt()
            val users = query
                .orderBy(UserTable.createdAt, SortOrder.DESC)
                .offset(offset.toLong())
                .limit(size)
                .map { it.toFullUser() }
            val p = rightService.getUserRoles(users.map { it.id })
            PageResult(
                items = users.map { AdminUserResponse(user = it, roles = p[it.id] ?: emptyList()) },
                total = total,
                page = page,
                size = size
            )
        }
    }

    /**
     * 获取用户详情（含角色）
     */
    suspend fun getUserDetail(userId: Int): AdminUserResponse {
        return databaseManager.suspendedTransaction(readOnly = true) {
            val user = UserTable.selectAll().where { UserTable.id eq userId }.firstOrNull()?.toFullUser()
                ?: throw IllegalStateException("用户不存在")
            val userRole = rightService.getUserRoles(listOf(userId))[userId] ?: emptyList()
            AdminUserResponse(
                user = user,
                roles = userRole
            )
        }
    }

    /**
     * 更新用户状态（激活/禁用）
     */
    suspend fun updateUserStatus(userId: Int, isActive: Boolean) {
        databaseManager.suspendedTransaction {
            UserTable.update({ UserTable.id eq userId }) {
                it[UserTable.isActive] = isActive
            }
        }
    }

    // ==================== 书籍管理 ====================

    /**
     * 分页获取所有书籍（管理员视图，不过滤 status/isActive）
     */
    suspend fun getAdminBooks(page: Int, size: Int, keyword: String? = null): PageResult<AdminBook> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            val offset = (page - 1) * size

            // 基础查询
            val baseQuery = BookTable
                .leftJoin(AuthorBookTable, { BookTable.id }, { AuthorBookTable.bookId })
                .select(
                    BookTable.id,
                    BookTable.name,
                    BookTable.author,
                    BookTable.description,
                    BookTable.category,
                    BookTable.tags,
                    BookTable.totalChapter,
                    BookTable.wordsCount,
                    BookTable.isActive,
                    BookTable.isEnded,
                    BookTable.status,
                    BookTable.createdAt,
                    BookTable.updatedAt,
                    AuthorBookTable.userId.count()
                )
                .groupBy(BookTable.id)

            val query = if (!keyword.isNullOrBlank()) {
                baseQuery.andWhere {
                    (BookTable.name like "%$keyword%") or
                            (BookTable.author like "%$keyword%") or
                            (BookTable.tags like "%$keyword%")
                }
            } else baseQuery

            val total = query.count().toInt()

            val books = query
                .orderBy(BookTable.updatedAt, SortOrder.DESC)
                .offset(offset.toLong())
                .limit(size)
                .map {
                    it.toAdminBook(config.serverUrl, it[AuthorBookTable.userId.count()].toInt())
                }
            PageResult(
                items = books,
                total = total,
                page = page,
                size = size
            )
        }
    }

    /**
     * 获取单本书籍详情（管理员视图）
     */
    suspend fun getAdminBookDetail(bookId: Int): AdminBook {
        return databaseManager.suspendedTransaction(readOnly = true) {
            val row = BookTable.selectAll()
                .where { BookTable.id eq bookId }
                .firstOrNull() ?: throw IllegalStateException("书籍不存在")

            val authorCount = AuthorBookTable.selectAll()
                .where { AuthorBookTable.bookId eq bookId }
                .count()
                .toInt()

            row.toAdminBook(config.serverUrl, authorCount)
        }
    }

    /**
     * 管理员更新书籍基本信息
     *
     * 业务规则：只有当该书籍未关联任何作者时（author_book 表无记录）才能修改
     */
    suspend fun updateAdminBook(
        bookId: Int,
        name: String? = null,
        author: String? = null,
        description: String? = null,
        category: String? = null,
        tags: String? = null,
        coverFile: File? = null
    ) {
        // 检查书籍是否存在
        databaseManager.suspendedTransaction(readOnly = true) {
            val exists = BookTable.selectAll().where { BookTable.id eq bookId }.count() > 0
            require(exists) { "书籍不存在" }
        }

        // 检查是否有作者关联
        databaseManager.suspendedTransaction(readOnly = true) {
            val authorCount = AuthorBookTable.selectAll()
                .where { AuthorBookTable.bookId eq bookId }
                .count()
            require(authorCount == 0L) { "该书籍有关联的作者，无法由管理员直接修改" }
        }

        // 执行更新
        databaseManager.suspendedTransaction {
            BookTable.update({ BookTable.id eq bookId }) { row ->
                name?.let { row[BookTable.name] = it }
                author?.let { row[BookTable.author] = it }
                description?.let { row[BookTable.description] = it }
                category?.let { row[BookTable.category] = it }
                tags?.let { row[BookTable.tags] = it }
            }
        }

        // 如果上传了新封面，保存封面文件
        coverFile?.let { file ->
            withContext(Dispatchers.IO) {
                val coverDir = Path(config.staticDir + "/book/$bookId")
                coverDir.toFile().mkdirs()
                file.copyTo(
                    coverDir.resolve("cover.webp").toFile(),
                    overwrite = true
                )
                file.deleteOnExit()
            }
        }
        cache.invalidate("book_service")
    }

    /**
     * 管理员上传书籍（metadata + TXT 文件）
     *
     * 解析 TXT 文件内容，自动创建章节
     */
    suspend fun uploadBookWithTxt(
        name: String,
        author: String,
        description: String,
        category: String,
        tags: String,
        coverFile: File?,
        txtFile: File
    ): Int {
        // 1. 读取 TXT 文件内容
        val txtContent = withContext(Dispatchers.IO) {
            txtFile.readText(Charsets.UTF_8)
        }

        // 2. 解析 TXT 内容
        val parseResult = TxtChapterParser.parse(txtContent)

        // 3. 合并描述：表单传入的 description 优先，若为空则使用 TXT 前置内容
        val finalDescription = description.ifBlank { parseResult.description }

        require(parseResult.chapters.isNotEmpty()) { "TXT 文件中未解析到任何章节内容" }

        // 4. 创建书籍记录
        val bookId = databaseManager.suspendedTransaction {
            val totalWords = parseResult.chapters.sumOf { it.content.length }
            BookTable.insertAndGetId {
                it[BookTable.name] = name
                it[BookTable.author] = author
                it[BookTable.description] = finalDescription
                it[BookTable.category] = category
                it[BookTable.tags] = tags
                it[BookTable.totalChapter] = parseResult.chapters.size
                it[BookTable.wordsCount] = totalWords
                it[BookTable.status] = BookStatus.PUBLISHED
                it[BookTable.isActive] = true
                it[BookTable.isEnded] = false
            }.value
        }
        // 5. 创建章节记录，并在同一事务内登记内容文件写入（由 Outbox worker 异步执行）
        parseResult.chapters.forEachIndexed { index, chapter ->
            val order = (index + 1).toFloat()
            databaseManager.suspendedTransaction {
                val chapterId = BookChapterTable.insertAndGetId {
                    it[BookChapterTable.bookId] = bookId
                    it[BookChapterTable.title] = chapter.title
                    it[BookChapterTable.wordCount] = chapter.content.length
                    it[BookChapterTable.status] = BookStatus.PUBLISHED
                    it[BookChapterTable.isActive] = true
                    it[BookChapterTable.order] = order
                }.value
                outboxService.write(
                    storeDir = "book",
                    storeName = bookId.toString(),
                    contentId = chapterId,
                    op = OutboxOp.UPDATE,
                    content = chapter.content
                )
            }
        }

        // 6. 保存封面
        coverFile?.let { file ->
            withContext(Dispatchers.IO) {
                val coverDir = Path(config.staticDir + "/book/$bookId")
                coverDir.toFile().mkdirs()
                file.copyTo(
                    coverDir.resolve("cover.webp").toFile(),
                    overwrite = true
                )
                file.deleteOnExit()
            }
        }

        // 7. 清理临时 TXT 文件
        txtFile.deleteOnExit()

        cache.invalidate("book_service")
        return bookId
    }

    /**
     * 切换书籍激活状态
     */
    suspend fun toggleBookActiveStatus(bookId: Int, isActive: Boolean) {
        databaseManager.suspendedTransaction {
            val count = BookTable.update({ BookTable.id eq bookId }) {
                it[BookTable.isActive] = isActive
            }
            require(count > 0) { "书籍不存在" }
        }
        cache.invalidate("book_service")
    }
}

@Serializable
data class AdminUserResponse(
    val user: FullUser,
    val roles: List<UserRole>,
)
