package com.qianrenni.bootstrap

import com.qianrenni.common.AppConfig
import com.qianrenni.common.config.ConfigService
import com.qianrenni.common.config.ConfigSource
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.config.RedisConfigSource
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.infrastructure.database.RedisManager
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.infrastructure.storage.ContentStoreCompactor
import com.qianrenni.infrastructure.storage.ContentStoreFactory
import com.qianrenni.infrastructure.task.TaskManager
import com.qianrenni.modules.system.SystemConfigService
import com.qianrenni.modules.admin.AdminService
import com.qianrenni.modules.admin.AuditService
import com.qianrenni.modules.admin.PermissionCache
import com.qianrenni.modules.admin.RightService
import com.qianrenni.modules.admin.RoleAdminService
import com.qianrenni.modules.author.AuthorApplicationService
import com.qianrenni.modules.author.AuthorService
import com.qianrenni.modules.book.BookService
import com.qianrenni.modules.book.CommentService
import com.qianrenni.modules.book.ReadProgressService
import com.qianrenni.modules.book.ShelfService
import com.qianrenni.modules.book.StatisticsService
import com.qianrenni.modules.system.SystemService
import com.qianrenni.modules.user.CaptchaService
import com.qianrenni.modules.user.UserService
import io.ktor.events.Events
import org.slf4j.Logger

/**
 * 装配上下文：组合根内各领域装配函数共享的底层资源。
 * 由 [Application.configService] 构造后传给各 `assembleXxx` 函数。
 */
class AppContext(
    val config: AppConfig,
    val db: DatabaseManager,
    val redis: RedisManager,
    val monitor: Events,
    val logger: Logger,
)

/**
 * 领域服务组：按领域聚合服务，替代原先 `Services` 顶层 20 字段平铺。
 * - Controller / 定时任务通过 `services.<group>.<service>` 取得所需依赖（参数注入），
 * - 新增服务只改对应领域组，不再改动顶层 [Services]。
 */
class InfraServices(
    val cacheService: CacheService,
    val emailService: EmailService,
    val outboxService: OutboxService,
    val contentStoreFactory: ContentStoreFactory,
    val contentStoreCompactor: ContentStoreCompactor,
    val configService: ConfigService,
    val configSource: ConfigSource,
    val taskManager: TaskManager,
) {
    /** 启动动态配置变更订阅（仅生产装配调用；测试环境 Redis 不可达时内部容错） */
    fun startConfigSubscription() {
        (configSource as? RedisConfigSource)?.start()
    }

    /** 停止订阅（应用停止时调用） */
    fun stopConfigSubscription() {
        (configSource as? RedisConfigSource)?.close()
    }
}

class UserServices(
    val userService: UserService,
    val captchaService: CaptchaService,
)

class BookServices(
    val bookService: BookService,
    val commentService: CommentService,
    val shelfService: ShelfService,
    val statisticsService: StatisticsService,
    val readProgressService: ReadProgressService,
)

class AdminServices(
    val permissionCache: PermissionCache,
    val rightService: RightService,
    val roleAdminService: RoleAdminService,
    val adminService: AdminService,
    val auditService: AuditService,
)

class AuthorServices(
    val authorService: AuthorService,
    val authorApplicationService: AuthorApplicationService,
)

class SystemServices(
    val systemService: SystemService,
    val systemConfigService: SystemConfigService,
)
