package com.qianrenni.bootstrap

import com.qianrenni.common.appConfig
import com.qianrenni.modules.admin.adminAudit
import com.qianrenni.modules.admin.adminBook
import com.qianrenni.modules.admin.adminPermission
import com.qianrenni.modules.admin.adminUser
import com.qianrenni.modules.author.author
import com.qianrenni.modules.book.book
import com.qianrenni.modules.book.comment
import com.qianrenni.modules.book.shelf
import com.qianrenni.modules.book.statistics
import com.qianrenni.modules.book.userReadingProgress
import com.qianrenni.modules.system.system
import com.qianrenni.modules.system.systemConfig
import com.qianrenni.modules.user.auth
import com.qianrenni.modules.user.captcha
import com.qianrenni.modules.user.user
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting() {
    // 服务实例由组合根 [Application.services] 统一提供，路由仅接收所需依赖（参数注入）
    val services = this.services
    routing {
        staticFiles("/static", File(appConfig.staticDir))
        captcha(services.user.captchaService)
        auth(services.user.userService, services.infra.cacheService, services.infra.emailService)
        user(services.user.userService, services.user.captchaService, services.infra.cacheService, services.infra.emailService)
        book(services.book.bookService)
        userReadingProgress(services.book.readProgressService)
        statistics(services.book.statisticsService)
        shelf(services.book.shelfService)
        adminAudit(services.admin.auditService)
        adminPermission(services.admin.rightService, services.admin.roleAdminService)
        adminUser(services.admin.adminService, services.admin.roleAdminService)
        adminBook(services.admin.adminService)
        comment(services.book.commentService)
        author(services.author.authorService, services.author.authorApplicationService)
        system(services.system.systemService)
        systemConfig(services.system.systemConfigService)
        if (appConfig.environment == "dev") {
            test(services.infra.emailService)
        }
    }

}