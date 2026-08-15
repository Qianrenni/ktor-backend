package com.qianrenni.modules.book

import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.BookTable
import com.qianrenni.models.tables.Shelf
import com.qianrenni.models.tables.ShelfTable
import com.qianrenni.models.tables.toShelf
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq


class ShelfService(private val databaseManager: DatabaseManager) {
    suspend fun get(userId: Int): List<Shelf> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            ShelfTable
                .innerJoin(BookTable, { ShelfTable.bookId }, { BookTable.id })
                .selectAll()
                .where { (ShelfTable.userId eq userId) and (BookTable.isActive eq true) }
                .map { it.toShelf() }
        }
    }

    suspend fun add(bookId: Int, userId: Int) {
        databaseManager.suspendedTransaction {
            ShelfTable.insert {
                it[ShelfTable.bookId] = bookId
                it[ShelfTable.userId] = userId
            }
        }
    }

    suspend fun delete(bookId: Int, userId: Int) {
        databaseManager.suspendedTransaction {
            ShelfTable.deleteWhere { (ShelfTable.bookId eq bookId) and (ShelfTable.userId eq userId) }
        }
    }
}