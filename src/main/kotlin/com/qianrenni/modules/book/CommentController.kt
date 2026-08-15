package com.qianrenni.modules.book

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.BookStatus
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.common.web.requirePermission
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.author.AuthorService
import com.qianrenni.modules.admin.generatePermissionCode
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ContentBody(val content: String)

@Serializable
data class LineCommentBody(val line: Int, val content: String)

@Serializable
data class CommentStatusBody(val status: String)

fun Routing.comment(commentService: CommentService, authorService: AuthorService) {
    // ==================== 无需认证 - 公开读取 ====================
    route("/comment") {

        // GET /comment/book/{bookId} - 分页获取书评列表
        get("/book/{bookId}") {
            val bookId = call.requirePathParameter("bookId").toInt()
            val page = call.request.queryParameters["page"]?.toInt() ?: 1
            val size = call.request.queryParameters["size"]?.toInt() ?: 20
            val parentId = call.request.queryParameters["parentId"]?.toInt()
            val result = commentService.getBookReviews(bookId, page, size, parentId)
            call.respond(ResponseModel.Success(data = result))
        }

        // GET /comment/chapter/{bookId}/{chapterId} - 获取章所有行评论
        get("/chapter/{bookId}/{chapterId}") {
            val bookId = call.requirePathParameter("bookId").toInt()
            val chapterId = call.requirePathParameter("chapterId").toInt()
            val result = commentService.getChapterComments(chapterId)
            call.respond(ResponseModel.Success(data = result))
        }
    }

    // ==================== 需认证 - 用户操作 ====================
    authenticate("auth-jwt") {
        route("/comment") {

            // ===== 书评 =====

            // POST /comment/book/{bookId} - 创建/更新书评
            post("/book/{bookId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                val body = call.receive<ContentBody>()
                val result = commentService.createBookReview(
                    userId = user.id,
                    bookId = bookId,
                    content = body.content
                )
                call.respond(ResponseModel.Success(data = result))
            }

            // DELETE /comment/book/{bookId} - 删除自己的书评
            delete("/book/{bookId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                commentService.deleteMyReview(userId = user.id, bookId = bookId)
                call.respond(ResponseModel.Empty(message = "书评已删除"))
            }

            // GET /comment/book/{bookId}/mine - 获取自己的书评
            get("/book/{bookId}/mine") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                val result = commentService.getMyBookReview(userId = user.id, bookId = bookId)
                call.respond(ResponseModel.Success(data = result))
            }

            // ===== 章节行评论 =====

            // POST /comment/chapter/{bookId}/{chapterId} - 创建/更新行评论
            post("/chapter/{bookId}/{chapterId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                val chapterId = call.requirePathParameter("chapterId").toInt()
                val body = call.receive<LineCommentBody>()
                val result = commentService.upsertLineComment(
                    userId = user.id,
                    bookId = bookId,
                    chapterId = chapterId,
                    line = body.line,
                    content = body.content
                )
                call.respond(ResponseModel.Success(data = result))
            }

            // DELETE /comment/chapter/{bookId}/{chapterId} - 删除自己的行评论
            delete("/chapter/{bookId}/{chapterId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                val chapterId = call.requirePathParameter("chapterId").toInt()
                val commentId = call.requireQueryParameter("commentId").toInt()
                commentService.deleteLineComment(
                    userId = user.id,
                    chapterId = chapterId,
                    commentId = commentId
                )
                call.respond(ResponseModel.Empty(message = "评论已删除"))
            }
        }
    }

    // ==================== 管理端 - 需权限 ====================
    authenticate("auth-jwt") {
        route("/admin/comments") {

            // GET /admin/comments - 分页获取书评列表（可按状态/书籍/用户名过滤）
            get {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.COMMENT, ActionEnum.READ, ScopeEnum.ALL))
                )
                val page = call.request.queryParameters["page"]?.toInt() ?: 1
                val size = call.request.queryParameters["size"]?.toInt() ?: 20
                val status = call.request.queryParameters["status"]?.let { BookStatus.fromValue(it) }
                val bookId = call.request.queryParameters["bookId"]?.toIntOrNull()
                val keyword = call.request.queryParameters["keyword"]
                val result = commentService.listBookReviews(bookId, page, size, status, keyword)
                call.respond(ResponseModel.Success(data = result))
            }

            // PATCH /admin/comments/{id}/status - 审核评论（改状态）
            patch("/{id}/status") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.COMMENT, ActionEnum.AUDIT, ScopeEnum.ALL))
                )
                val commentId = call.requirePathParameter("id").toInt()
                val body = call.receive<CommentStatusBody>()
                val updated = commentService.auditBookReview(commentId, BookStatus.fromValue(body.status))
                if (updated) {
                    call.respond(ResponseModel.Empty(message = "审核成功"))
                } else {
                    call.respond(ResponseModel.Error(message = "评论不存在"))
                }
            }

            // DELETE /admin/comments/{id} - 强制删除评论
            delete("/{id}") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.COMMENT, ActionEnum.DELETE, ScopeEnum.ALL))
                )
                val commentId = call.requirePathParameter("id").toInt()
                commentService.forceDeleteBookReview(commentId)
                call.respond(ResponseModel.Empty(message = "评论已删除"))
            }
        }
    }

    // ==================== 作者端 ====================
    authenticate("auth-jwt") {
        route("/author/comments") {

            // GET /author/comments/book/{bookId} - 获取作者某本书的评论
            get("/book/{bookId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                authorService.checkAuthor(user.id, bookId)
                val page = call.request.queryParameters["page"]?.toInt() ?: 1
                val size = call.request.queryParameters["size"]?.toInt() ?: 20
                val status = call.request.queryParameters["status"]?.let { BookStatus.fromValue(it) }
                val result = commentService.listBookReviews(bookId, page, size, status, null)
                call.respond(ResponseModel.Success(data = result))
            }
        }
    }
}
