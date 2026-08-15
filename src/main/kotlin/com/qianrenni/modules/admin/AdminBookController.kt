package com.qianrenni.modules.admin

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.ResponseModel
import com.qianrenni.common.web.requirePermission
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import java.io.File
import kotlin.io.path.createTempFile

/**
 * 管理端 - 书籍管理路由（/admin/books）。
 * 从原 499 行 AdminController 拆出，仅依赖 [AdminService]。
 */
fun Routing.adminBook(adminService: AdminService) {
    authenticate("auth-jwt") {
        route("/admin/books") {
            // GET /admin/books - 分页获取所有书籍（管理员视图）
            get {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.READ, ScopeEnum.ALL))
                )
                val page = call.request.queryParameters["page"]?.toInt() ?: 1
                val size = call.request.queryParameters["size"]?.toInt() ?: 20
                val keyword = call.queryParameters["keyword"]
                val result = adminService.getAdminBooks(page, size, keyword)
                call.respond(ResponseModel.Success(data = result))
            }

            // GET /admin/books/{id} - 获取单本书籍详情
            get("/{id}") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.READ, ScopeEnum.ALL))
                )
                val bookId = call.requirePathParameter("id").toInt()
                val result = adminService.getAdminBookDetail(bookId)
                call.respond(ResponseModel.Success(data = result))
            }

            // PUT /admin/books/{id} - 更新书籍基本信息
            put("/{id}") {
                val user = call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val bookId = call.requirePathParameter("id").toInt()
                val multipartData = call.receiveMultipart(formFieldLimit = 1024 * 1024 * 10)
                var name: String? = null
                var author: String? = null
                var description: String? = null
                var category: String? = null
                var tags: String? = null
                var coverFile: File? = null

                multipartData.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "name" -> name = part.value
                                "author" -> author = part.value
                                "description" -> description = part.value
                                "category" -> category = part.value
                                "tags" -> tags = part.value
                            }
                        }

                        is PartData.FileItem -> {
                            val fileName = part.originalFileName
                            fileName?.let {
                                coverFile = createTempFile("cover_admin_${user.id}", it.split(".").last()).toFile()
                            }
                            coverFile?.let {
                                part.provider().copyAndClose(it.writeChannel())
                            }
                        }

                        else -> {}
                    }
                    part.release()
                }

                adminService.updateAdminBook(
                    bookId = bookId,
                    name = name,
                    author = author,
                    description = description,
                    category = category,
                    tags = tags,
                    coverFile = coverFile
                )
                call.respond(ResponseModel.Empty(message = "书籍更新成功"))
            }

            // POST /admin/books/upload - 上传书籍（TXT）
            post("/upload") {
                val user = call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.MANAGE, ScopeEnum.ALL))
                )

                val multipartData = call.receiveMultipart(formFieldLimit = 1024 * 1024 * 50)
                var name: String? = null
                var author: String? = null
                var description: String? = null
                var category: String? = null
                var tags: String? = null
                var coverFile: File? = null
                var txtFile: File? = null

                multipartData.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "name" -> name = part.value
                                "author" -> author = part.value
                                "description" -> description = part.value
                                "category" -> category = part.value
                                "tags" -> tags = part.value
                            }
                        }

                        is PartData.FileItem -> {
                            when (part.name) {
                                "cover" -> {
                                    val fileName = part.originalFileName
                                    fileName?.let {
                                        coverFile =
                                            createTempFile("cover_admin_${user.id}", it.split(".").last()).toFile()
                                    }
                                    coverFile?.let {
                                        part.provider().copyAndClose(it.writeChannel())
                                    }
                                }

                                "txtFile" -> {
                                    txtFile = createTempFile("upload_book", "txt").toFile()
                                    txtFile?.let {
                                        part.provider().copyAndClose(it.writeChannel())
                                    }
                                }
                            }
                        }

                        else -> {}
                    }
                    part.release()
                }

                require(name != null) { "书名不能为空" }
                require(author != null) { "作者不能为空" }
                require(category != null) { "分类不能为空" }
                require(tags != null) { "标签不能为空" }
                require(txtFile != null) { "TXT 文件不能为空" }

                val bookId = adminService.uploadBookWithTxt(
                    name = name,
                    author = author,
                    description = description ?: "",
                    category = category,
                    tags = tags,
                    coverFile = coverFile,
                    txtFile = txtFile
                )
                call.respond(ResponseModel.Success(data = bookId, message = "书籍上传成功"))
            }

            // PATCH /admin/books/{id}/status - 切换书籍激活状态
            patch("/{id}/status") {
                call.requirePermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.MANAGE, ScopeEnum.ALL))
                )
                val bookId = call.requirePathParameter("id").toInt()
                val body = call.receive<UpdateUserStatusBody>()
                adminService.toggleBookActiveStatus(bookId, body.isActive)
                call.respond(ResponseModel.Empty(message = "书籍状态已更新"))
            }
        }
    }
}
