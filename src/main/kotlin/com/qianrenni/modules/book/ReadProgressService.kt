package com.qianrenni.modules.book

import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.BookTable
import com.qianrenni.models.tables.UserReadingProgress
import com.qianrenni.models.tables.UserReadingProgressTable
import com.qianrenni.models.tables.toUserReadingProgress
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ReadProgressService(private val databaseManager: DatabaseManager) {
    suspend fun get(userId: Int): List<UserReadingProgress> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            UserReadingProgressTable
                .innerJoin(BookTable, { UserReadingProgressTable.bookId }, { BookTable.id })
                .selectAll()
                .where { (UserReadingProgressTable.userId eq userId) and (BookTable.isActive eq true) }
                .map { it.toUserReadingProgress() }
        }
    }

    suspend fun add(userId: Int, bookId: Int, lastChapterId: Int, lastPosition: Int) {
        databaseManager.suspendedTransaction() {
            UserReadingProgressTable.selectAll()
                .where((UserReadingProgressTable.userId eq userId) and (UserReadingProgressTable.bookId eq bookId))
                .firstOrNull()?.let {
                    UserReadingProgressTable.update({ (UserReadingProgressTable.userId eq userId) and (UserReadingProgressTable.bookId eq bookId) }) {
                        it[UserReadingProgressTable.lastChapterId] = lastChapterId
                        it[UserReadingProgressTable.lastPosition] = lastPosition
                    }
                    return@suspendedTransaction
                }
            UserReadingProgressTable.insert {
                it[UserReadingProgressTable.userId] = userId
                it[UserReadingProgressTable.bookId] = bookId
                it[UserReadingProgressTable.lastChapterId] = lastChapterId
                it[UserReadingProgressTable.lastPosition] = lastPosition
            }
        }
    }

    suspend fun delete(userId: Int, bookId: Int) {
        databaseManager.suspendedTransaction() {
            UserReadingProgressTable.deleteWhere { (UserReadingProgressTable.userId eq userId) and (UserReadingProgressTable.bookId eq bookId) }
        }
    }
}