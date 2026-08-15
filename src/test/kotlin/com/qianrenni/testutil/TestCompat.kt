package com.qianrenni.testutil

import com.qianrenni.bootstrap.services
import com.qianrenni.infrastructure.cache.CacheService
import com.qianrenni.infrastructure.mail.EmailService
import com.qianrenni.infrastructure.outbox.OutboxService
import com.qianrenni.modules.admin.AdminService
import com.qianrenni.modules.admin.AuditService
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
import io.ktor.server.application.Application

/**
 * 测试兼容扩展：重构后业务 Service 不再以 `Application.xxxService` 散落扩展属性暴露，
 * 测试代码统一经由组合根 [Application.services] 访问。此扩展仅为既有测试的
 * `xxxService.processPending() / xxxService.get(...)` 调用提供同包别名。
 */
val Application.outboxService: OutboxService
    get() = services.outboxService

val Application.userService: UserService
    get() = services.userService

val Application.captchaService: CaptchaService
    get() = services.captchaService

val Application.shelfService: ShelfService
    get() = services.shelfService

val Application.statisticsService: StatisticsService
    get() = services.statisticsService

val Application.bookService: BookService
    get() = services.bookService

val Application.commentService: CommentService
    get() = services.commentService

val Application.readProgressService: ReadProgressService
    get() = services.readProgressService

val Application.authorService: AuthorService
    get() = services.authorService

val Application.auditService: AuditService
    get() = services.auditService

val Application.adminService: AdminService
    get() = services.adminService

val Application.rightService: RightService
    get() = services.rightService

val Application.roleAdminService: RoleAdminService
    get() = services.roleAdminService

val Application.emailService: EmailService
    get() = services.emailService

val Application.systemService: SystemService
    get() = services.systemService

val Application.cacheService: CacheService
    get() = services.cacheService

val Application.authorApplicationService: AuthorApplicationService
    get() = services.authorApplicationService
