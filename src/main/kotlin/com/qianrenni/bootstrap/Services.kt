package com.qianrenni.bootstrap

import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.infrastructure.storage.ContentStoreCompactor
import com.qianrenni.infrastructure.task.TaskManager
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

/**
 * 应用服务组合根（手工组合根模式）。
 *
 * 集中装配全部业务 Service 的显式依赖，替代原先「每个 Service 注入整个 Application、
 * 运行时从 attributes 按需取依赖」的服务定位器写法：
 * - Service 构造函数的参数即为它的全部依赖，可读、可测、可替换；
 * - Controller 通过参数注入拿到所需 Service，不再依赖 Application 的隐式扩展属性；
 * - 基础设施（DatabaseManager / RedisManager / AppConfig / Logger）仍由
 *   `Application.databaseManager` 等扩展属性提供，不重复挂载。
 *
 * 由 [Application.configService] 创建并挂到 Application attributes，
 * 通过 `Application.services` 访问（插件等跨切面代码的唯一入口）。
 */
class Services(
    val cacheService: CacheService,
    val captchaService: CaptchaService,
    val permissionCache: PermissionCache,
    val rightService: RightService,
    val roleAdminService: RoleAdminService,
    val emailService: EmailService,
    val userService: UserService,
    val outboxService: OutboxService,
    val auditService: AuditService,
    val bookService: BookService,
    val commentService: CommentService,
    val authorService: AuthorService,
    val adminService: AdminService,
    val authorApplicationService: AuthorApplicationService,
    val shelfService: ShelfService,
    val statisticsService: StatisticsService,
    val readProgressService: ReadProgressService,
    val systemService: SystemService,
    val taskManager: TaskManager,
    val contentStoreCompactor: ContentStoreCompactor,
)
