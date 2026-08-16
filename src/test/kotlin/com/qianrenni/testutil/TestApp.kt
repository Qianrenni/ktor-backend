package com.qianrenni.testutil

import com.qianrenni.bootstrap.configService
import com.qianrenni.common.*
import com.qianrenni.common.util.PasswordUtils
import com.qianrenni.infrastructure.database.configureDatabase
import com.qianrenni.infrastructure.database.configureRedis
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.infrastructure.database.redisManager
import com.qianrenni.infrastructure.storage.ContentStoreManager
import com.qianrenni.infrastructure.storage.ContentStoreService
import com.qianrenni.models.tables.*
import com.qianrenni.modules.admin.generatePermissionCode
import io.ktor.server.application.*
import kotlinx.coroutines.future.await
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path

/**
 * 测试环境基础设施。
 *
 * 与生产 [com.qianrenni.Application.main] 的区别：
 * 1. 不注册 TaskManager / 定时任务 —— 避免 cron 干扰测试断言；
 * 2. OutboxService 只注册【不启动】channel 消费循环 —— 测试手动调用
 *    `application.outboxService.processPending()` 以确定性方式断言"DB 元数据 + 文件内容"双写；
 * 3. 每次 [testConfigure] 重建 H2 schema 并写入种子数据（权限/角色/用户），
 *    测试之间互不污染（共享内存库，靠重建保证隔离）。
 */
fun Application.testConfigure() {
    loadConfig()
    // 清空文件存储目录 + 全局 ContentStore 同步单元缓存，避免文件残留与内存索引跨测试污染
    cleanTestStoreDirs()
    ContentStoreManager.resetForTest()
    configureDatabase()
    initTestSchema()
    configureRedis()
    // 清空测试专用 Redis db（Gradle 注入 /15），避免缓存 key/验证码跨测试污染
    // （H2 schema 每次重建，Redis 必须同步复位；Redis 不可达时忽略）
    runCatching {
        kotlinx.coroutines.runBlocking {
            redisManager.getAsyncCommands().flushdb().await()
        }
    }
    // 装配服务组合根（SystemService 等已由组合根统一创建）
    configService()
    // OutboxService 只注册【不启动】channel 消费循环 —— 测试手动调用
    // `application.services.infra.outboxService.processPending()` 以确定性方式断言双写
}

/** 测试用全部表，按 FK 依赖顺序排列（父表在前） */
val testTables: List<org.jetbrains.exposed.sql.Table> = listOf(
    UserTable,
    BookTable,
    BookChapterTable,
    PermissionTable,
    RoleTable,
    RoleInheritanceTable,
    RolePermissionTable,
    UserRoleTable,
    BookCommentTable,
    BookChapterCommentTable,
    FileSyncOutboxTable,
    AuditBookTable,
    AuditBookChapterTable,
    AuthorTable,
    AuthorApplicationTable,
    AuthorBookTable,
    ChapterReadStatisticsTable,
    UserReadEventTable,
    ShelfTable,
    UserReadingProgressTable,
)

/** 重建 schema：drop 全部表 → create 全部表。
 *  生产默认值已声明在 Exposed 表定义（.default(...)）中，SchemaUtils.create() 生成的 DDL
 *  自带默认值，与 database.sql 建表脚本语义一致，无需再手工 ALTER 补齐。 */
private fun Application.initTestSchema() {
    val db = databaseManager.getDatabase()
    transaction(db) {
        SchemaUtils.drop(*testTables.toTypedArray())
        SchemaUtils.create(*testTables.toTypedArray())
    }
    seedDatabase(databaseManager.getDatabase())
}

/** 种子用户（测试内可直接引用） */
object TestUsers {
    const val ADMIN_NAME = "admin"
    const val ADMIN_PASSWORD = "admin123"
    const val USER_NAME = "user1"
    const val USER_PASSWORD = "user123"
    const val AUTHOR_NAME = "author1"
    const val AUTHOR_PASSWORD = "author123"
    const val REVIEWER_NAME = "reviewer1"
    const val REVIEWER_PASSWORD = "reviewer123"

    /** 角色 ID（seed 后填充） */
    val roleIds = mutableMapOf<RoleEnum, Int>()

    /** 用户 ID（seed 后填充） */
    val userIds = mutableMapOf<String, Int>()
}

/**
 * 种子数据：84 个权限（7 资源 × 6 动作 × 2 范围）、5 个角色、角色权限、继承关系、4 个初始用户。
 * 与 production 权限体系一致：bitPosition 0..83，权限码 "resource:action:scope"。
 */
fun seedDatabase(db: Database) {
    transaction(db) {
        // 1. 权限：全组合，bitPosition 递增
        val codeToPermissionId = mutableMapOf<String, Int>()
        var bitPosition = 0
        for (resource in ResourceTypeEnum.entries) {
            for (action in ActionEnum.entries) {
                for (scope in ScopeEnum.entries) {
                    val code = generatePermissionCode(resource, action, scope)
                    val id = PermissionTable.insert {
                        it[PermissionTable.name] = code
                        it[PermissionTable.resourceType] = resource
                        it[PermissionTable.action] = action
                        it[PermissionTable.scope] = scope
                        it[PermissionTable.bitPosition] = bitPosition
                    } get PermissionTable.id
                    codeToPermissionId[code] = id.value
                    bitPosition++
                }
            }
        }

        // 2. 角色
        TestUsers.roleIds.clear()
        RoleEnum.entries.forEach { role ->
            val id = RoleTable.insert {
                it[name] = role.code
                // code 存大写：与生产 createRole（code.uppercase()）及 addUserRole/getAuditorCount 的
                // RoleEnum.X.name（大写）比较保持一致，种子数据才能被生产代码路径匹配
                it[code] = role.code.uppercase()
                it[description] = role.code
            } get RoleTable.id
            TestUsers.roleIds[role] = id.value
        }

        // 3. 角色权限（不含继承：USER 基础权限 + 各角色自有权限）
        // 注意：权限码为枚举 toString 形式（如 "BOOK:READ:ALL"），与 generatePermissionCode 一致
        val rolePermissions = mapOf(
            RoleEnum.USER to listOf(
                "BOOK:READ:ALL", "CHAPTER:READ:ALL",
                "COMMENT:CREATE:OWN", "COMMENT:READ:ALL",
                "SHELF:CREATE:OWN", "SHELF:READ:OWN", "SHELF:UPDATE:OWN", "SHELF:DELETE:OWN",
                "USER:READ:OWN", "USER:UPDATE:OWN",
            ),
            RoleEnum.AUTHOR to listOf(
                "BOOK:CREATE:OWN", "BOOK:UPDATE:OWN", "BOOK:DELETE:OWN",
                "CHAPTER:CREATE:OWN", "CHAPTER:UPDATE:OWN", "CHAPTER:DELETE:OWN",
            ),
            RoleEnum.REVIEWER to listOf(
                "BOOK:AUDIT:ALL", "CHAPTER:AUDIT:ALL",
            ),
            RoleEnum.ADMIN to listOf(
                "BOOK:MANAGE:ALL", "CHAPTER:MANAGE:ALL", "USER:MANAGE:ALL",
                "COMMENT:MANAGE:ALL", "PERMISSION:MANAGE:ALL", "SHELF:MANAGE:ALL",
                "SYSTEM:READ:ALL", "SYSTEM:MANAGE:ALL", "BOOK:AUDIT:ALL", "CHAPTER:AUDIT:ALL",
            ),
            RoleEnum.SUPER_ADMIN to ResourceTypeEnum.entries.flatMap { r ->
                ActionEnum.entries.flatMap { a -> ScopeEnum.entries.map { s -> "${r}:${a}:${s}" } }
            },
        )
        for ((role, codes) in rolePermissions) {
            val roleId = TestUsers.roleIds.getValue(role)
            for (code in codes) {
                val permId = codeToPermissionId[code] ?: error("种子权限码不存在: $code")
                RolePermissionTable.insert {
                    it[RolePermissionTable.roleId] = roleId
                    it[RolePermissionTable.permissionId] = permId
                }
            }
        }

        // 4. 角色继承：AUTHOR→USER、REVIEWER→USER、ADMIN→USER、SUPER_ADMIN→ADMIN
        fun inherit(child: RoleEnum, parent: RoleEnum) {
            RoleInheritanceTable.insert {
                it[childId] = TestUsers.roleIds.getValue(child)
                it[parentId] = TestUsers.roleIds.getValue(parent)
            }
        }
        inherit(RoleEnum.AUTHOR, RoleEnum.USER)
        inherit(RoleEnum.REVIEWER, RoleEnum.USER)
        inherit(RoleEnum.ADMIN, RoleEnum.USER)
        inherit(RoleEnum.SUPER_ADMIN, RoleEnum.ADMIN)

        // 5. 初始用户
        fun createUser(userName: String, password: String, email: String, role: RoleEnum): Int {
            val userId = UserTable.insert {
                it[UserTable.userName] = userName
                it[UserTable.password] = PasswordUtils.hash(password)
                it[UserTable.email] = email
                it[isActive] = true
                it[avatar] = ""
            } get UserTable.id
            UserRoleTable.insert {
                it[UserRoleTable.userId] = userId.value
                it[UserRoleTable.roleId] = TestUsers.roleIds.getValue(role)
            }
            return userId.value
        }
        TestUsers.userIds.clear()
        TestUsers.userIds[TestUsers.ADMIN_NAME] =
            createUser(TestUsers.ADMIN_NAME, TestUsers.ADMIN_PASSWORD, "admin@test.com", RoleEnum.SUPER_ADMIN)
        TestUsers.userIds[TestUsers.USER_NAME] =
            createUser(TestUsers.USER_NAME, TestUsers.USER_PASSWORD, "user1@test.com", RoleEnum.USER)
        TestUsers.userIds[TestUsers.AUTHOR_NAME] =
            createUser(TestUsers.AUTHOR_NAME, TestUsers.AUTHOR_PASSWORD, "author1@test.com", RoleEnum.AUTHOR)
        TestUsers.userIds[TestUsers.REVIEWER_NAME] =
            createUser(TestUsers.REVIEWER_NAME, TestUsers.REVIEWER_PASSWORD, "reviewer1@test.com", RoleEnum.REVIEWER)
    }
}

/** 清空并重建测试存储目录（CONTENT_DIR/STATIC_DIR），避免文件残留影响断言 */
fun cleanTestStoreDirs() {
    val appConfig = com.qianrenni.common.AppConfig.load()
    listOf(appConfig.contentDir, appConfig.staticDir).forEach { dir ->
        val path = Path.of(dir)
        if (Files.exists(path)) {
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(path)
    }
}

/** 写入测试用目录 */
fun ensureDir(dir: String) {
    Files.createDirectories(Path.of(dir))
}

/** 插入一本测试书籍（默认已发布激活），返回 bookId */
suspend fun Application.insertTestBook(
    name: String = "测试书籍",
    author: String = "测试作者",
    category: String = "玄幻",
    status: BookStatus = BookStatus.PUBLISHED,
    isActive: Boolean = true,
    description: String = "测试描述",
): Int {
    return databaseManager.suspendedTransaction {
        BookTable.insert {
            it[BookTable.name] = name
            it[BookTable.author] = author
            it[BookTable.description] = description
            it[BookTable.category] = category
            it[BookTable.tags] = ""
            it[BookTable.totalChapter] = 1
            it[BookTable.wordsCount] = 1000
            it[BookTable.isActive] = isActive
            it[BookTable.isEnded] = false
            it[BookTable.status] = status
        } get BookTable.id
    }.value
}

/** 插入一章（默认已发布激活），返回 chapterId */
suspend fun Application.insertTestChapter(
    bookId: Int,
    title: String = "第一章",
    order: Float = 1f,
    status: BookStatus = BookStatus.PUBLISHED,
    isActive: Boolean = true,
): Int {
    return databaseManager.suspendedTransaction {
        BookChapterTable.insert {
            it[BookChapterTable.bookId] = bookId
            it[BookChapterTable.title] = title
            it[BookChapterTable.wordCount] = 500
            it[BookChapterTable.status] = status
            it[BookChapterTable.isActive] = isActive
            it[BookChapterTable.order] = order
        } get BookChapterTable.id
    }.value
}

// ==================== 共享断言辅助（Outbox/双存储测试复用） ====================

/** 读取测试 ContentStore 文件内容（与 OutboxService 消费路径拼接规则一致） */
suspend fun Application.readTestStore(storeDir: String, storeName: String, contentId: Int): String =
    // 全限定名：本文件已 import java.nio.file.Path（接口），此处需 Kotlin 的 Path 工厂函数
    ContentStoreService(name = storeName, baseDir = kotlin.io.path.Path(appConfig.contentDir, storeDir).toString())
        .use { it.readChapter(contentId) }

/** 查询 outbox 记录状态（status, retryCount） */
suspend fun Application.outboxStatusOf(recordId: Int): Pair<OutboxStatus, Int> =
    databaseManager.suspendedTransaction(readOnly = true) {
        val row = FileSyncOutboxTable.selectAll().where { FileSyncOutboxTable.id eq recordId }.single()
        row[FileSyncOutboxTable.status] to row[FileSyncOutboxTable.retryCount]
    }

/** 建立作者与书籍的绑定记录（AuthorService.checkAuthor 依赖 AuthorBookTable） */
suspend fun Application.bindAuthor(userId: Int, bookId: Int) {
    databaseManager.suspendedTransaction {
        AuthorBookTable.insert {
            it[AuthorBookTable.userId] = userId
            it[AuthorBookTable.bookId] = bookId
        }
    }
}