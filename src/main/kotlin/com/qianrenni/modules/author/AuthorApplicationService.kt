package com.qianrenni.modules.author

import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.modules.admin.RoleAdminService
import com.qianrenni.common.RoleEnum
import com.qianrenni.models.tables.*
import org.jetbrains.exposed.sql.*
import java.time.LocalDateTime

class AuthorApplicationService(
    private val databaseManager: DatabaseManager,
    private val roleAdminService: RoleAdminService,
) {
    /**
     * 用户提交作者申请
     */
    suspend fun apply(userId: Int, reason: String): AuthorApplication {
        // 检查是否已有待处理的申请
        val existing = databaseManager.suspendedTransaction(readOnly = true) {
            AuthorApplicationTable.selectAll()
                .where {
                    (AuthorApplicationTable.userId eq userId) and
                            (AuthorApplicationTable.status inList listOf("pending"))
                }
                .firstOrNull()
        }
        if (existing != null) {
            throw IllegalArgumentException("您已提交过申请，请等待审核")
        }

        // 检查是否已经是作者
        val alreadyAuthor = databaseManager.suspendedTransaction(readOnly = true) {
            AuthorTable.selectAll().where { AuthorTable.userId eq userId }.firstOrNull()
        }
        if (alreadyAuthor != null) {
            throw IllegalArgumentException("您已经是作者了")
        }

        return databaseManager.suspendedTransaction {
            AuthorApplicationTable.insertAndGetId {
                it[AuthorApplicationTable.userId] = userId
                it[AuthorApplicationTable.reason] = reason
                it[AuthorApplicationTable.status] = "pending"
            }.let { id ->
                AuthorApplicationTable.selectAll().where { AuthorApplicationTable.id eq id }.single()
                    .toAuthorApplication()
            }
        }
    }

    /**
     * 获取用户的申请记录
     */
    suspend fun getUserApplication(userId: Int): AuthorApplication? {
        return databaseManager.suspendedTransaction(readOnly = true) {
            AuthorApplicationTable.selectAll()
                .where { AuthorApplicationTable.userId eq userId }
                .orderBy(AuthorApplicationTable.createdAt, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toAuthorApplication()
        }
    }

    /**
     * 管理员获取所有申请（按状态筛选）
     */
    suspend fun getApplications(status: String?): List<AuthorApplication> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            val query = AuthorApplicationTable.selectAll()
            if (!status.isNullOrBlank()) {
                query.where { AuthorApplicationTable.status eq status }
            }
            query.orderBy(AuthorApplicationTable.createdAt, SortOrder.DESC)
                .map { it.toAuthorApplication() }
        }
    }

    /**
     * 管理员审核通过申请
     */
    suspend fun approve(adminId: Int, applicationId: Int) {
        databaseManager.suspendedTransaction {
            // 更新申请状态
            val updateCount =
                AuthorApplicationTable.update({ (AuthorApplicationTable.id eq applicationId) and (AuthorApplicationTable.status eq "pending") }) {
                it[AuthorApplicationTable.status] = "approved"
                it[AuthorApplicationTable.handledBy] = adminId
                    it[AuthorApplicationTable.handledAt] = LocalDateTime.now()
            }

            if (updateCount == 1) {
                // 创建作者记录
                val userId = AuthorApplicationTable.selectAll().where { AuthorApplicationTable.id eq applicationId }
                    .single()[AuthorApplicationTable.userId]
                val userRow = UserTable.selectAll().where { UserTable.id eq userId }.single()
                val userName = userRow[UserTable.userName]
                val alreadyAuthor = AuthorTable.selectAll().where { AuthorTable.userId eq userId }.firstOrNull()
                if (alreadyAuthor == null) {
                    AuthorTable.insert {
                        it[AuthorTable.userId] = userId
                        it[AuthorTable.name] = userName
                    }
                }

                // 添加作者角色
                roleAdminService.addUserRole(
                    adminId = adminId,
                    updateUserId = userId,
                    roleCode = RoleEnum.AUTHOR.name
                )
            }
        }
    }

    /**
     * 管理员驳回申请
     */
    suspend fun reject(adminId: Int, applicationId: Int, rejectReason: String?) {
        databaseManager.suspendedTransaction {
            AuthorApplicationTable.update({ (AuthorApplicationTable.id eq applicationId) and (AuthorApplicationTable.status eq "pending") }) {
                it[AuthorApplicationTable.status] = "rejected"
                it[AuthorApplicationTable.handledBy] = adminId
                it[AuthorApplicationTable.rejectReason] = rejectReason
                it[AuthorApplicationTable.handledAt] = LocalDateTime.now()
            }
        }
    }
}
