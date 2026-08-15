package com.qianrenni.bootstrap

import com.qianrenni.common.appConfig
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.infrastructure.database.redisManager
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.infrastructure.storage.ContentStoreFactory
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
import io.ktor.server.application.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 应用服务组合根装配点（手工组合根）。
 *
 * 按依赖顺序显式构造全部 Service 并挂到 [Services] 组合根，替代旧的
 * 「每个 Service 注入整个 Application、运行时从 attributes 按需取依赖」的服务定位器写法：
 * - Service 构造参数即其全部依赖，编译期可见、可 mock、可替换；
 * - 依赖创建顺序在此集中管理，避免循环依赖与初始化遗漏。
 *
 * 调用方：
 * - 生产：`Application.main` 在 configureRouting 之前调用本方法；
 * - 测试：`Application.testConfigure` 调用本方法后按需手动触发 Outbox/定时任务。
 */
fun Application.configService(): Services {
    val config = appConfig
    val db = databaseManager
    val redis = redisManager
    val logger = environment.log

    val cacheService = CacheService(logger, redis)
    val captchaService = CaptchaService(config, redis)
    val permissionCache = PermissionCache(config, db, logger)
    val rightService = RightService(permissionCache, db)
    val roleAdminService = RoleAdminService(permissionCache, db)
    val contentStoreFactory = ContentStoreFactory(config)
    val emailService = EmailService(config, logger)
    val outboxService = OutboxService(config, db, logger, contentStoreFactory)
    val userService = UserService(config, db, rightService, captchaService)
    val auditService = AuditService(config, db, contentStoreFactory)
    val bookService = BookService(config, db, cacheService, contentStoreFactory)
    val commentService = CommentService(config, db, outboxService, userService, contentStoreFactory)
    val authorApplicationService = AuthorApplicationService(db, roleAdminService)
    val authorService = AuthorService(config, db, outboxService, auditService, userService, emailService, contentStoreFactory, cacheService)
    val adminService = AdminService(config, db, rightService, outboxService, cacheService)
    val shelfService = ShelfService(db)
    val statisticsService = StatisticsService(db)
    val readProgressService = ReadProgressService(db)
    val systemService = SystemService()
    val taskManager = TaskManager(monitor, logger)

    val services = Services(
        cacheService = cacheService,
        captchaService = captchaService,
        permissionCache = permissionCache,
        rightService = rightService,
        roleAdminService = roleAdminService,
        emailService = emailService,
        userService = userService,
        outboxService = outboxService,
        auditService = auditService,
        bookService = bookService,
        commentService = commentService,
        authorService = authorService,
        adminService = adminService,
        authorApplicationService = authorApplicationService,
        shelfService = shelfService,
        statisticsService = statisticsService,
        readProgressService = readProgressService,
        systemService = systemService,
        taskManager = taskManager,
    )
    attributes[ServicesKey] = services

    // 保留原 registerRightService 的行为：应用启动后预加载权限/角色数据
    monitor.subscribe(ApplicationStarted) {
        runBlocking(Dispatchers.Default) { permissionCache.start() }
    }
    return services
}

private val ServicesKey = AttributeKey<Services>("Services")

/** 应用服务组合根访问点（插件等跨切面代码的唯一入口） */
val Application.services: Services
    get() = attributes[ServicesKey]