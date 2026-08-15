package com.qianrenni.testutil

import com.qianrenni.infrastructure.database.databaseManager
import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.RoleEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.models.tables.PermissionTable
import com.qianrenni.models.tables.RoleTable
import com.qianrenni.models.tables.UserTable
import com.qianrenni.modules.admin.generatePermissionCode
import com.qianrenni.bootstrap.services
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 测试基础设施冒烟测试：验证 H2 schema 重建、种子数据、服务注册、权限加载全链路可用。
 * 后续所有集成测试的基座。
 */
class TestInfraSmoke {

    @Test
    fun `testConfigure 能启动并完成建表与种子数据`() = runTest {
        testApplication {
            application { testConfigure() }
            startApplication()
            val db = application.databaseManager.getDatabase()
            transaction(db) {
                assertEquals(84, PermissionTable.selectAll().count(), "应有 7×6×2=84 个权限")
                assertEquals(5, RoleTable.selectAll().count(), "应有 5 个角色")
                assertEquals(4, UserTable.selectAll().count(), "应有 4 个种子用户")
            }
        }
    }

    @Test
    fun `RightService 自动加载种子权限并正确校验`() = runTest {
        testApplication {
            application { testConfigure() }
            startApplication()
            val rs = application.services.admin.rightService
            // ApplicationStarted 已触发自动 start()，验证内存数据
            assertEquals(84, rs.permissionDict.size)
            assertEquals(5, rs.roleDict.size)

            val superAdminRoleId = TestUsers.roleIds.getValue(RoleEnum.SUPER_ADMIN)
            val adminRoleId = TestUsers.roleIds.getValue(RoleEnum.ADMIN)
            val userRoleId = TestUsers.roleIds.getValue(RoleEnum.USER)

            // 继承关系：SUPER_ADMIN 的祖先包含 ADMIN；ADMIN 的祖先包含 USER
            assertTrue(rs.roleInheritanceDict[superAdminRoleId]!!.contains(adminRoleId))
            assertTrue(rs.roleInheritanceDict[adminRoleId]!!.contains(userRoleId))
            // 层级：ADMIN > USER
            assertTrue(rs.roleLevels[adminRoleId]!! > rs.roleLevels[userRoleId]!!)

            val bookReadAll = generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.READ, ScopeEnum.ALL)
            val bookManageAll = generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.MANAGE, ScopeEnum.ALL)

            // SUPER_ADMIN 拥有全部权限
            assertTrue(
                rs.checkPermission(
                    listOf(bookReadAll, bookManageAll),
                    rs.getRolesSegments(listOf(superAdminRoleId))
                )
            )
            // USER 只能读，不能 manage
            val userSegments = rs.getRolesSegments(listOf(userRoleId))
            assertTrue(rs.checkPermission(listOf(bookReadAll), userSegments))
            assertFalse(rs.checkPermission(listOf(bookManageAll), userSegments))
            // AUTHOR 继承 USER：AUTHOR 能读，且拥有创建权限
            val authorRoleId = TestUsers.roleIds.getValue(RoleEnum.AUTHOR)
            val authorSegments = rs.getRolesSegments(listOf(authorRoleId))
            assertTrue(rs.checkPermission(listOf(bookReadAll), authorSegments))
            assertTrue(
                rs.checkPermission(
                    listOf(generatePermissionCode(ResourceTypeEnum.BOOK, ActionEnum.CREATE, ScopeEnum.OWN)),
                    authorSegments
                )
            )
            // 未知权限码返回 false
            assertFalse(rs.checkPermission(listOf("unknown:perm:code"), userSegments))
        }
    }
}
