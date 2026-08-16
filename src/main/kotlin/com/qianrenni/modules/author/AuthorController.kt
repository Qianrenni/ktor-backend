package com.qianrenni.modules.author

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.web.PermissionCheck
import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.common.web.requirePermission
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.author.AuthorApplicationService
import com.qianrenni.modules.author.AuthorService
import com.qianrenni.modules.admin.generatePermissionCode
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.io.path.createTempFile

@Serializable
data class RequestUpdateBookChapter(
    val bookId: Int,
    val title: String,
    val order: Float,
    val content: String
) {
    init {
        require(title.isNotBlank(), { "标题不能为空" })
        require(title.length <= 50, { "标题不能超过50个字符" })
        require(content.isNotBlank(), { "内容不能为空" })
        require(content.length <= 10000, { "内容不能超过10000个字符" })
    }
}

@Serializable
data class ApplyAuthorRequest(val reason: String) {
    init {
        require(reason.isNotBlank(), { "申请理由不能为空" })
        require(reason.length <= 500, { "申请理由不能超过500个字符" })
    }
}

@Serializable
data class RejectAuthorRequest(val rejectReason: String? = null) {
    init {
        require(rejectReason == null || rejectReason.length <= 500, { "拒绝理由不能超过500字" })
    }
}

fun Routing.author(
    authorService: AuthorService,
    authorApplicationService: AuthorApplicationService,
) {
    route("/author") {
        authenticate("auth-jwt") {
            route("/count") {
                get {
                    // 安全加固（M7）：改用显式权限校验，避免组级 PermissionCheck 作用域错误
                    call.requirePermission(
                        listOf(
                            generatePermissionCode(
                                resource = ResourceTypeEnum.USER, action = ActionEnum.READ, scope = ScopeEnum.ALL
                            )
                        )
                    )
                    val result = authorService.getAuthorCount()
                    call.respond(ResponseModel.Success(result))
                }
            }
            // GET /author/book - 获取作者图书列表
            get("/book") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.READ, ScopeEnum.ALL))
                )
                val user = call.getCurrentUser()
                val bookIds = call.request.queryParameters.getAll("id")?.map { it.toInt() } ?: emptyList()
                val result = authorService.getBook(userId = user.id, bookIds = bookIds)
                call.respond(ResponseModel.Success(result))
            }

            // POST /author/book - 创建作者图书
            post("/book") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.CREATE, ScopeEnum.OWN))
                )
                val user = call.getCurrentUser()
                val multipartData = call.receiveMultipart(formFieldLimit = 1024 * 1024)
                var bookName: String? = null
                var author: String? = null
                var description: String? = null
                var category: String? = null
                var tags: String? = null
                var cover: File? = null
                multipartData.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "name" -> {
                                    require(part.value.length <= 20, { "书名不能超过20字" })
                                    bookName = part.value
                                }

                                "author" -> {
                                    require(part.value.length <= 20, { "作者不能超过20字" })
                                    author = part.value
                                }

                                "description" -> {
                                    require(part.value.length <= 500, { "书籍简介不能超过500字" })
                                    description = part.value
                                }

                                "category" -> {
                                    require(part.value.length <= 10, { "分类不能超过10字" })
                                    category = part.value
                                }

                                "tags" -> {
                                    require(part.value.length <= 100, { "标签不能超过100字" })
                                    tags = part.value
                                }
                            }
                        }

                        is PartData.FileItem -> {
                            val fileName = part.originalFileName
                            fileName?.let {
                                cover = createTempFile("cover_${user.id}", it.split(".").last()).toFile()
                            }
                            cover?.let {
                                part.provider().copyAndClose(it.writeChannel())
                            }
                        }

                        else -> {}
                    }
                    part.release()
                }
                //全部不为空才创建
                if (
                    bookName != null
                    && author != null
                    && description != null
                    && category != null
                    && tags != null
                    && cover != null
                ) {
                    authorService.createBook(
                        userId = user.id,
                        bookName = bookName,
                        author = author,
                        tags = tags,
                        description = description,
                        category = category,
                        coverFile = cover
                    )
                }
                call.respond(ResponseModel.Empty(message = "创建成功"))
            }

            // PATCH /author/book - 更新作者图书
            patch("/book") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.UPDATE, ScopeEnum.OWN))
                )
                val user = call.getCurrentUser()
                val multipartData = call.receiveMultipart(formFieldLimit = 1024 * 1024)
                var bookName: String? = null
                var author: String? = null
                var description: String? = null
                var category: String? = null
                var tags: String? = null
                var cover: File? = null
                var bookId: Int? = null
                multipartData.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "id" -> {
                                    bookId = part.value.toInt()
                                }

                                "name" -> {
                                    require(part.value.length <= 20, { "书名不能超过20字" })
                                    bookName = part.value
                                }

                                "author" -> {
                                    require(part.value.length <= 20, { "作者不能超过20字" })
                                    author = part.value
                                }

                                "description" -> {
                                    require(part.value.length <= 500, { "书籍简介不能超过500字" })
                                    description = part.value
                                }

                                "category" -> {
                                    require(part.value.length <= 10, { "分类不能超过10字" })
                                    category = part.value
                                }

                                "tags" -> {
                                    require(part.value.length <= 100, { "标签不能超过100字" })
                                    tags = part.value
                                }
                            }
                        }

                        is PartData.FileItem -> {
                            val fileName = part.originalFileName
                            fileName?.let {
                                cover = createTempFile("cover_${user.id}", it.split(".").last()).toFile()
                            }
                            cover?.let {
                                part.provider().copyAndClose(it.writeChannel())
                            }
                        }

                        else -> {}
                    }
                    part.release()
                }
                //全部不为空才创建
                if (
                    bookId != null
                    && bookName != null
                    && author != null
                    && description != null
                    && category != null
                    && tags != null
                ) {
                    authorService.updateBook(
                        userId = user.id,
                        bookId = bookId,
                        bookName = bookName,
                        author = author,
                        tags = tags,
                        description = description,
                        category = category,
                        coverFile = cover
                    )
                }
                call.respond(ResponseModel.Empty(message = "更新成功"))
            }

            // DELETE /author/book - 删除作者图书
            delete("/book") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.DELETE, ScopeEnum.OWN))
                )
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("id").toInt()
                authorService.deleteBook(userId = user.id, bookId = bookId)
                call.respond(ResponseModel.Empty(message = "删除成功"))
            }

            // GET /author/chapter - 获取作者图书章节
            get("/chapter") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.CHAPTER, ActionEnum.READ, ScopeEnum.ALL))
                )
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val chapterId = call.request.queryParameters.getAll("chapterId")?.map { it.toInt() }
                val result = authorService.getBookChapter(
                    userId = user.id,
                    bookId = bookId,
                    chapterId = chapterId
                )
                call.respond(ResponseModel.Success(result))
            }

            // PATCH /author/chapter - 更新作者图书章节
            patch("/chapter") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.CHAPTER, ActionEnum.UPDATE, ScopeEnum.OWN))
                )
                val user = call.getCurrentUser()
                val requestUpdateBookChapter = call.receive<RequestUpdateBookChapter>()
                authorService.updateBookChapter(
                    userId = user.id,
                    requestUpdateBookChapter = requestUpdateBookChapter
                )
                call.respond(ResponseModel.Empty(message = "更新成功"))
            }

            // DELETE /author/chapter - 删除作者图书章节
            delete("/chapter") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.CHAPTER, ActionEnum.DELETE, ScopeEnum.OWN))
                )
                val user = call.getCurrentUser()
                val chapterId = call.requireQueryParameter("chapterId").toInt()
                val bookId = call.requireQueryParameter("bookId").toInt()
                authorService.deleteBookChapter(
                    userId = user.id,
                    chapterId = chapterId,
                    bookId = bookId
                )
                call.respond(ResponseModel.Empty(message = "删除成功"))
            }

            // GET /author/book-statistics - 获取作者图书阅读数据
            get("/book-statistics") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.READ, ScopeEnum.ALL))
                )
                val user = call.getCurrentUser()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val result = authorService.getBookReadStatistic(userId = user.id, bookId = bookId)
                call.respond(ResponseModel.Success(result))
            }

            // GET /author/content - 获取章节内容
            get("/content") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.CHAPTER, ActionEnum.READ, ScopeEnum.ALL))
                )
                val user = call.getCurrentUser()
                val chapterIds = call.request.queryParameters.getAll("chapterId")?.map { it.toInt() } ?: emptyList()
                val bookId = call.requireQueryParameter("bookId").toInt()
                val result = authorService.getChapterContent(
                    userId = user.id,
                    chapterIds = chapterIds,
                    bookId = bookId
                )
                call.respond(ResponseModel.Success(result))
            }

            // GET /author/draft/chapter - 获取草稿章节
            route("/draft") {
                get("/chapter") {
                    call.requirePermission(
                        listOf(generatePermissionCode(ResourceTypeEnum.CHAPTER, ActionEnum.READ, ScopeEnum.ALL))
                    )
                    val user = call.getCurrentUser()
                    val result = authorService.getDraftChapter(userId = user.id)
                    call.respond(ResponseModel.Success(result))
                }
            }
            route("/status") {
                // PATCH /author/status/chapter - 更新章节状态
                patch("chapter") {
                    call.requirePermission(
                        listOf(generatePermissionCode(ResourceTypeEnum.CHAPTER, ActionEnum.UPDATE, ScopeEnum.OWN))
                    )
                    val user = call.getCurrentUser()
                    val bookId = call.requireQueryParameter("bookId").toInt()
                    val chapterId = call.requireQueryParameter("chapterId").toInt()
                    authorService.updateStatusChapter(
                        userId = user.id,
                        bookId = bookId,
                        chapterId = chapterId
                    )
                    call.respond(ResponseModel.Empty(message = "更新成功"))
                }

                // PATCH /author/status/book - 更新书籍状态
                patch("/book") {
                    call.requirePermission(
                        listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.UPDATE, ScopeEnum.OWN))
                    )
                    val user = call.getCurrentUser()
                    val bookId = call.requireQueryParameter("bookId").toInt()
                    authorService.updateStatusBook(userId = user.id, bookId = bookId)
                    call.respond(ResponseModel.Empty(message = "更新成功"))
                }
            }

        }
    }
    route("/author-application") {
        authenticate("auth-jwt") {
            // 用户提交申请
            post {
                val user = call.getCurrentUser()
                val request = call.receive<ApplyAuthorRequest>()
                val result = authorApplicationService.apply(
                    userId = user.id,
                    reason = request.reason
                )
                call.respond(
                    HttpStatusCode.Created,
                    ResponseModel.Success(data = result, message = "申请已提交，请等待审核")
                )
            }

            // 用户查看自己的申请状态
            get {
                val user = call.getCurrentUser()
                val result = authorApplicationService.getUserApplication(user.id)
                call.respond(ResponseModel.Success(data = result))
            }

            route("/admin") {
                install(PermissionCheck) {
                    requiredPermissions = listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.USER,
                            action = ActionEnum.MANAGE,
                            scope = ScopeEnum.ALL
                        )
                    )
                }

                // 获取所有申请（可按状态筛选）
                get {
                    val status = call.queryParameters["status"]
                    val result = authorApplicationService.getApplications(status)
                    call.respond(ResponseModel.Success(data = result))
                }

                // 审核通过
                patch("/{id}/approve") {
                    val admin = call.getCurrentUser()
                    val id = call.requirePathParameter("id").toInt()
                    authorApplicationService.approve(adminId = admin.id, applicationId = id)
                    call.respond(ResponseModel.Empty(message = "已通过作者申请"))
                }

                // 驳回申请
                patch("/{id}/reject") {
                    val admin = call.getCurrentUser()
                    val id = call.requirePathParameter("id").toInt()
                    val request = call.receive<RejectAuthorRequest>()
                    authorApplicationService.reject(
                        adminId = admin.id,
                        applicationId = id,
                        rejectReason = request.rejectReason
                    )
                    call.respond(ResponseModel.Empty(message = "已驳回作者申请"))
                }
            }
        }
    }
}
