package com.qianrenni.bootstrap

import com.qianrenni.common.appConfig
import com.qianrenni.common.config.ConfigService
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.config.RedisConfigSource
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.infrastructure.database.redisManager
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
import io.ktor.server.application.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 应用服务组合根装配点（手工组合根 + 领域分组）。
 *
 * 按「基础设施 → 权限/管理 → 用户 → 书籍 → 作者 → 系统」的依赖顺序，
 * 通过各 `assembleXxx` 函数构造领域服务组并聚合到 [Services]：
 * - 各领域组定义见 `ServiceGroups.kt`；
 * - Service 构造参数即其全部依赖，编译期可见、可 mock、可替换；
 * - 新增服务只改对应领域装配函数，不再改动顶层 [Services]。
 *
 * 调用方：
 * - 生产：`Application.main` 在 configureRouting 之前调用本方法；
 * - 测试：`Application.testConfigure` 调用本方法后按需手动触发 Outbox/定时任务。
 */
fun Application.configService(): Services {
    val ctx = AppContext(
        config = appConfig,
        db = databaseManager,
        redis = redisManager,
        monitor = monitor,
        logger = environment.log,
        coroutineContext = coroutineContext,
    )

    // 基础设施（最底层，被所有领域依赖）
    val infra = assembleInfra(ctx)
    // 权限/管理域（被 user/author 依赖，先于它们装配）
    val admin = assembleAdmin(ctx, infra)
    // 用户域
    val user = assembleUser(ctx, admin)
    // 书籍/评论域
    val book = assembleBook(ctx, infra, user)
    // 作者域
    val author = assembleAuthor(ctx, infra, admin, user)
    // 系统域
    val system = assembleSystem(infra)

    val services = Services(infra, user, book, admin, author, system)
    attributes[ServicesKey] = services

    // 应用启动后预加载权限/角色数据
    monitor.subscribe(ApplicationStarted) {
        runBlocking(Dispatchers.Default) { admin.permissionCache.start() }
    }
    return services
}

private fun assembleInfra(ctx: AppContext): InfraServices {
    val contentStoreFactory = ContentStoreFactory(ctx.config)
    // 动态配置：Redis 源 + 本地缓存服务；变更通知回调绑定到 ConfigService.invalidate
    val configSource = RedisConfigSource(ctx.redis, ctx.logger)
    val configService = ConfigService(configSource)
    configSource.onChange = configService::invalidate
    return InfraServices(
        cacheService = CacheService(ctx.logger, ctx.redis),
        emailService = EmailService(ctx.config, ctx.logger),
        outboxService = OutboxService(ctx.config, ctx.db, ctx.logger, contentStoreFactory),
        contentStoreFactory = contentStoreFactory,
        contentStoreCompactor = ContentStoreCompactor(ctx.config, configService, ctx.logger),
        configService = configService,
        configSource = configSource,
        taskManager = TaskManager(ctx.monitor, ctx.logger),
    )
}

private fun assembleAdmin(ctx: AppContext, infra: InfraServices): AdminServices {
    val permissionCache = PermissionCache(ctx.config, ctx.db, ctx.logger)
    val rightService = RightService(permissionCache, ctx.db)
    val roleAdminService = RoleAdminService(permissionCache, ctx.db)
    val auditService = AuditService(ctx.config, ctx.db, infra.contentStoreFactory)
    val adminService = AdminService(ctx.config, ctx.db, rightService, infra.outboxService, infra.cacheService)
    return AdminServices(permissionCache, rightService, roleAdminService, adminService, auditService)
}

private fun assembleUser(ctx: AppContext, admin: AdminServices): UserServices {
    val captchaService = CaptchaService(ctx.config, ctx.redis)
    val userService = UserService(ctx.config, ctx.db, admin.rightService, captchaService)
    return UserServices(userService, captchaService)
}

private fun assembleBook(ctx: AppContext, infra: InfraServices, user: UserServices): BookServices {
    val bookService = BookService(ctx.config, ctx.db, infra.cacheService, infra.contentStoreFactory)
    val commentService = CommentService(ctx.config, ctx.db, infra.outboxService, user.userService, infra.contentStoreFactory)
    return BookServices(
        bookService = bookService,
        commentService = commentService,
        shelfService = ShelfService(ctx.db),
        statisticsService = StatisticsService(ctx.db),
        readProgressService = ReadProgressService(ctx.db),
    )
}

private fun assembleAuthor(
    ctx: AppContext,
    infra: InfraServices,
    admin: AdminServices,
    user: UserServices,
): AuthorServices {
    val authorApplicationService = AuthorApplicationService(ctx.db, admin.roleAdminService)
    val authorService = AuthorService(
        ctx.config, ctx.db, infra.outboxService, admin.auditService,
        user.userService, infra.emailService, infra.contentStoreFactory, infra.cacheService,
        notifyScope = CoroutineScope(ctx.coroutineContext),
    )
    return AuthorServices(authorService, authorApplicationService)
}

private fun assembleSystem(infra: InfraServices): SystemServices =
    SystemServices(SystemService(), SystemConfigService(infra.configService))

private val ServicesKey = AttributeKey<Services>("Services")

/** 应用服务组合根访问点（插件等跨切面代码的唯一入口） */
val Application.services: Services
    get() = attributes[ServicesKey]