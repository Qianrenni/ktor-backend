package com.qianrenni.modules.book

import com.qianrenni.common.ReportEnum
import com.qianrenni.common.web.getCurrentUser
import com.qianrenni.common.ResponseModel
import com.qianrenni.modules.book.StatisticsService
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RequestStatisticsReadEvent(
     val bookId: Int,
     val chapterId: Int,
     val eventType: String,
)

fun Routing.statistics(statisticsService: StatisticsService) {
    route("/statistic") {
        authenticate("auth-jwt") {
            post("/book-chapter") {
                val user = call.getCurrentUser()
                val body = call.receive<RequestStatisticsReadEvent>()
                statisticsService.addUserReadEvent(
                    userId = user.id,
                    bookId = body.bookId,
                    chapterId = body.chapterId,
                    eventType = ReportEnum.fromValue(body.eventType),
                )
                call.respond(ResponseModel.Empty("添加成功"))
            }
        }
    }
}