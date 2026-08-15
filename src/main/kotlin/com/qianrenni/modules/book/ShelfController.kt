package com.qianrenni.modules.book

import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.book.ShelfService
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RequestShelfAdd(
     val bookId: Int,
)

fun Routing.shelf(shelfService: ShelfService) {
    route("/shelf") {
        authenticate("auth-jwt") {
            get("/get") {
                val user = call.getCurrentUser()
                val result = shelfService.get(user.id)
                call.respond(ResponseModel.Success(result))
            }
            post("/add") {
                val user = call.getCurrentUser()
                val body = call.receive<RequestShelfAdd>()
                shelfService.add(bookId = body.bookId, userId = user.id)
                call.respond(ResponseModel.Empty("添加书籍到书架成功"))

            }
            delete("/delete/{bookId}") {
                val user = call.getCurrentUser()
                val bookId = call.requirePathParameter("bookId").toInt()
                shelfService.delete(bookId = bookId, userId = user.id)
                call.respond(ResponseModel.Empty("移除书架书籍成功"))
            }
        }
    }
}