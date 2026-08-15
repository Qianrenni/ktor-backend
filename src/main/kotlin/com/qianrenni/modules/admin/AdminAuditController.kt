package com.qianrenni.modules.admin

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.PermissionCheck
import com.qianrenni.common.web.getCurrentUser
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 管理端 - 内容审核路由（/audit）。
 * 从原 499 行 AdminController 拆出，仅依赖 [AuditService]。
 */
fun Routing.adminAudit(auditService: AuditService) {
    authenticate("auth-jwt") {
        route("/audit") {
            install(PermissionCheck) {
                requiredPermissions = listOf(
                    generatePermissionCode(
                        resource = ResourceTypeEnum.BOOK,
                        action = ActionEnum.AUDIT,
                        scope = ScopeEnum.ALL
                    )
                )
            }

            // GET /audit/book - 获取审核中的书
            get("/book") {
                val user = call.getCurrentUser()
                val bookIds = call.request.queryParameters.getAll("bookIds")?.map { it.toInt() } ?: emptyList()
                val result = auditService.getAuditBooks(user.id, bookIds)
                call.respond(ResponseModel.Success(data = result))
            }

            // PATCH /audit/book - 审核书
            patch("/book") {
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val isPass = call.requireQueryParameter("isPass").toBoolean()
                auditService.updateBook(userId = user.id, bookId = bookId, isPass = isPass)
                call.respond(ResponseModel.Empty(message = "审核成功"))
            }

            // GET /audit/chapter - 获取审核中的章节
            get("/chapter") {
                val user = call.getCurrentUser()
                val result = auditService.getAuditChapters(user.id)
                call.respond(ResponseModel.Success(data = result))
            }

            // GET /audit/chapterByOrder - 按 order 获取审核章节
            get("/chapterByOrder") {
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val orders = call.request.queryParameters.getAll("orders")?.map { it.toFloat() } ?: emptyList()
                val result = auditService.getAuditChaptersByOrder(
                    userId = user.id,
                    bookId = bookId,
                    orders = orders
                )
                call.respond(ResponseModel.Success(data = result))
            }

            // PATCH /audit/chapter - 审核章节
            patch("/chapter") {
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val chapterId = call.requireQueryParameter("chapterId").toInt()
                val isPass = call.requireQueryParameter("isPass").toBoolean()
                auditService.updateBookChapter(
                    userId = user.id,
                    bookId = bookId,
                    chapterId = chapterId,
                    isPass = isPass
                )
                call.respond(ResponseModel.Empty(message = "审核成功"))
            }

            // GET /audit/content/chapter - 获取审核中的章节内容
            get("/content/chapter") {
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val orders = call.request.queryParameters.getAll("orders")?.map { it.toFloat() } ?: emptyList()
                val result = auditService.getAuditContentChapter(
                    userId = user.id,
                    bookId = bookId,
                    orders = orders
                )
                call.respond(ResponseModel.Success(data = result))
            }
        }
    }
}
