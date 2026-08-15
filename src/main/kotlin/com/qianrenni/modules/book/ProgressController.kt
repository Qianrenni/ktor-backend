package com.qianrenni.modules.book

import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.book.ReadProgressService
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RequestReadingProgressAdd(
     val bookId: Int,
     val lastChapterId: Int,
     val lastPosition: Int,
)

fun Routing.userReadingProgress(readProgressService: ReadProgressService) {
    route("/user_reading_progress") {
        authenticate("auth-jwt") {
            get("/get") {
                val user = call.getCurrentUser()
                val result = readProgressService.get(user.id)
                call.respond(ResponseModel.Success(result))
            }
            patch("/add") {
                val user = call.getCurrentUser()
                val body = call.receive<RequestReadingProgressAdd>()
                readProgressService.add(
                    user.id,
                    bookId = body.bookId,
                    lastChapterId = body.lastChapterId,
                    lastPosition = body.lastPosition
                )
                call.respond(ResponseModel.Empty("更新书籍阅读历史成功"))
            }
            delete("/delete/{bookId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                readProgressService.delete(userId = user.id, bookId = bookId)
                call.respond(ResponseModel.Empty("删除书籍阅读历史成功"))
            }
        }

    }
}