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
        captcha(services.captchaService)
        auth(services.userService, services.cacheService, services.emailService)
        user(services.userService, services.captchaService, services.cacheService, services.emailService)
        book(services.bookService)
        userReadingProgress(services.readProgressService)
        statistics(services.statisticsService)
        shelf(services.shelfService)
        adminAudit(services.auditService)
        adminPermission(services.rightService, services.roleAdminService)
        adminUser(services.adminService, services.roleAdminService)
        adminBook(services.adminService)
        comment(services.commentService)
        author(services.authorService, services.authorApplicationService)
        system(services.systemService)
        if (appConfig.environment == "dev") {
            test(services.emailService)
        }
    }

}