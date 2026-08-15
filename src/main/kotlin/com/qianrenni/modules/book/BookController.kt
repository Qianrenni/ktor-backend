package com.qianrenni.modules.book

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.common.web.requirePermission
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.book.BookService
import com.qianrenni.modules.admin.generatePermissionCode
import com.ucasoft.ktor.simpleCache.cacheOutput
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes


fun Routing.book(bookService: BookService) {
    route("/book") {
        authenticate("auth-jwt") {
            get("/count") {
                call.requirePermission(
                    listOf(
                        generatePermissionCode(
                            resource = ResourceTypeEnum.PERMISSION,
                            action = ActionEnum.READ,
                            scope = ScopeEnum.ALL
                        )
                    )
                )
                val result = bookService.getBookCount()
                call.respond(ResponseModel.Success(result))
            }
        }
        get("/category") {
            val result = bookService.getCategory()
            call.respond(ResponseModel.Success(result))
        }
        get("/recommend") {
            val query = call.requireQueryParameter("query")
            val result = bookService.getRecommendBook(query)
            call.respond(ResponseModel.Success(result))
        }
        get("/search") {
            val query = call.requireQueryParameter("q")
            val result = bookService.getSearchBook(query)
            call.respond(ResponseModel.Success(result))
        }
        get("/list") {
            val bookIds = call.request.queryParameters.getAll("bookIds")?.map { it.toInt() } ?: emptyList()
            val result = bookService.getBookList(bookIds)
            call.respond(ResponseModel.Success(result))
        }
        cacheOutput(30.minutes) {
            get("/toc/{bookId}") {
                val bookId = call.requirePathParameter("bookId").toInt()
                val result = bookService.getBookCatalog(bookId)
                call.respond(ResponseModel.Success(result))
            }
            get("/{bookId}/read-count") {
                val bookId = call.requirePathParameter("bookId").toInt()
                val result = bookService.getReadCount(bookId)
                call.respond(ResponseModel.Success(result))
            }
            get("/{bookId}/favorite-count") {
                val bookId = call.requirePathParameter("bookId").toInt()
                val result = bookService.getFavoriteCount(bookId)
                call.respond(ResponseModel.Success(result))
            }
        }
        authenticate("auth-jwt") {
            rateLimit(RateLimitName("protected")) {
                cacheOutput(30.minutes) {
                    get("/chapter/{chapterId}") {
                        call.requirePermission(
                            permissions = listOf(
                                generatePermissionCode(
                                    resource = ResourceTypeEnum.BOOK,
                                    action = ActionEnum.READ,
                                    scope = ScopeEnum.ALL
                                )
                            )
                        )
                        val chapterId = call.requirePathParameter("chapterId").toInt()
                        val bookId = call.requireQueryParameter("bookId").toInt()
                        val result = bookService.getBookChapter(chapterId, bookId)
                        call.respond(ResponseModel.Success(result))
                    }
                }
            }
        }
        get("/select") {
            val category = call.requireQueryParameter("category")
            val offset = call.requireQueryParameter("offset").toInt()
            val limit = call.requireQueryParameter("limit").toInt()
            val result = bookService.getBookSelect(category, offset, limit)
            call.respond(ResponseModel.Success(result))
        }
        get("/{bookId}") {
            val bookId = call.requirePathParameter("bookId").toInt()
            val result = bookService.getBookList(listOf(bookId))
            call.respond(
                ResponseModel.Success(result.first()),
            )
        }
    }
}