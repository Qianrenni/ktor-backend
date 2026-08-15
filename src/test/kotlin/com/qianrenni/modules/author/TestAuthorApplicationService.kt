package com.qianrenni.modules.author

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.models.tables.AuthorTable
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.withTestApplication
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AuthorApplicationService 集成测试：作者入驻申请、审核通过/驳回。
 */
class TestAuthorApplicationService {

    @Test
    fun `apply 提交申请成功`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val app = authorApplicationService.apply(userId, "我想写书")

        assertEquals("pending", app.status)
        assertEquals("我想写书", app.reason)
        assertEquals(userId, app.userId)
    }

    @Test
    fun `apply 重复提交待审核申请抛异常`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        authorApplicationService.apply(userId, "第一次申请")
        assertFailsWith<IllegalArgumentException> { authorApplicationService.apply(userId, "第二次申请") }
    }

    @Test
    fun `apply 已是作者抛异常`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        databaseManager.suspendedTransaction {
            AuthorTable.insert {
                it[AuthorTable.userId] = userId
                it[AuthorTable.name] = "已有作者"
            }
        }
        assertFailsWith<IllegalArgumentException> { authorApplicationService.apply(userId, "申请") }
    }

    @Test
    fun `getUserApplication 返回最近申请`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val app = authorApplicationService.apply(userId, "申请理由")
        val latest = authorApplicationService.getUserApplication(userId)
        assertNotNull(latest)
        assertEquals(app.id, latest.id)
        assertNull(authorApplicationService.getUserApplication(999999))
    }

    @Test
    fun `getApplications 按状态筛选`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val app = authorApplicationService.apply(userId, "理由")
        authorApplicationService.approve(adminId, app.id)

        val all = authorApplicationService.getApplications(null)
        assertEquals(1, all.size)
        val approved = authorApplicationService.getApplications("approved")
        assertEquals(1, approved.size)
        val pending = authorApplicationService.getApplications("pending")
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `approve 通过申请创建作者记录与作者角色`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val app = authorApplicationService.apply(userId, "想成为作者")

        authorApplicationService.approve(adminId, app.id)

        // 申请状态更新
        assertEquals("approved", authorApplicationService.getUserApplication(userId)?.status)

        // Author 记录创建
        val authorRows = databaseManager.suspendedTransaction(readOnly = true) {
            AuthorTable.selectAll().where { AuthorTable.userId eq userId }.toList()
        }
        assertEquals(1, authorRows.size)
        assertEquals(TestUsers.USER_NAME, authorRows[0][AuthorTable.name])

        // AUTHOR 角色已添加（继承 USER 权限的 USER 用户获得 AUTHOR 角色）
        val roles = rightService.getUserRoles(listOf(userId))[userId].orEmpty()
        assertTrue(roles.any { it.roleId == TestUsers.roleIds.getValue(com.qianrenni.common.RoleEnum.AUTHOR) })
    }

    @Test
    fun `approve 非 pending 状态不生效`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val app = authorApplicationService.apply(userId, "申请")
        authorApplicationService.reject(adminId, app.id, "资料不足")

        authorApplicationService.approve(adminId, app.id)

        // 已驳回的申请不会被再次通过
        assertEquals("rejected", authorApplicationService.getUserApplication(userId)?.status)
    }

    @Test
    fun `reject 驳回申请记录原因`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val app = authorApplicationService.apply(userId, "申请")

        authorApplicationService.reject(adminId, app.id, "资料不完整")

        val latest = authorApplicationService.getUserApplication(userId)
        assertEquals("rejected", latest?.status)
        assertEquals("资料不完整", latest?.rejectReason)
        assertEquals(adminId, latest?.handledBy)
    }
}
