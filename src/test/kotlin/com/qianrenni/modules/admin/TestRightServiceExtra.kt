package com.qianrenni.modules.admin

import com.qianrenni.testutil.*

import com.qianrenni.common.RoleEnum
import com.qianrenni.testutil.TestUsers
import com.qianrenni.testutil.withTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RightService 角色管理补充测试（权限加载与 checkPermission 已在 TestInfraSmoke 覆盖）：
 * 用户角色增删、权限级别校验、角色 CRUD。
 */
class TestRightServiceExtra {

    @Test
    fun `addUserRole 管理员为用户添加角色`() = withTestApplication {
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)

        assertTrue(roleAdminService.addUserRole(adminId = adminId, updateUserId = userId, roleCode = RoleEnum.REVIEWER.name))

        val roles = rightService.getUserRoles(listOf(userId))[userId].orEmpty()
        assertTrue(roles.any { it.roleId == TestUsers.roleIds.getValue(RoleEnum.REVIEWER) }, "user1 应获得 REVIEWER 角色")
    }

    @Test
    fun `addUserRole 权限级别不足拒绝`() = withTestApplication {
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME) // USER(level 1)
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME) // AUTHOR(level 2)

        // USER 用户不能给 AUTHOR 用户添加角色（1 < 2）
        assertFailsWith<IllegalArgumentException> {
            roleAdminService.addUserRole(adminId = userId, updateUserId = authorId, roleCode = RoleEnum.REVIEWER.name)
        }
    }

    @Test
    fun `addUserRole 未知角色码返回 false`() = withTestApplication {
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        assertFalse(roleAdminService.addUserRole(adminId = adminId, updateUserId = userId, roleCode = "NO_SUCH_ROLE"))
    }

    @Test
    fun `addUserRoleById 按角色 ID 添加`() = withTestApplication {
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        roleAdminService.addUserRoleById(adminId = adminId, updateUserId = userId, roleId = TestUsers.roleIds.getValue(RoleEnum.AUTHOR))

        val roles = rightService.getUserRoles(listOf(userId))[userId].orEmpty()
        assertTrue(roles.any { it.roleId == TestUsers.roleIds.getValue(RoleEnum.AUTHOR) })
    }

    @Test
    fun `removeUserRole 移除角色`() = withTestApplication {
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val authorId = TestUsers.userIds.getValue(TestUsers.AUTHOR_NAME)
        val authorRoleId = TestUsers.roleIds.getValue(RoleEnum.AUTHOR)

        roleAdminService.removeUserRole(adminId = adminId, userId = authorId, roleId = authorRoleId)

        val roles = rightService.getUserRoles(listOf(authorId))[authorId].orEmpty()
        assertTrue(roles.none { it.roleId == authorRoleId }, "AUTHOR 角色应被移除")
    }

    @Test
    fun `getRolePermissions 返回角色权限`() = withTestApplication {
        val userRoleId = TestUsers.roleIds.getValue(RoleEnum.USER)
        val perms = rightService.getRolePermissions(userRoleId)
        assertTrue(perms.isNotEmpty())
        assertTrue(perms.any { it.name == "BOOK:READ:ALL" })
    }

    @Test
    fun `createRole 创建新角色并刷新缓存`() = withTestApplication {
        val role = roleAdminService.createRole(name = "测试角色", code = "TEST_ROLE", description = "测试用")
        assertEquals("TEST_ROLE", role.code)
        // 新角色在缓存中可用
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        assertTrue(roleAdminService.addUserRole(adminId = adminId, updateUserId = userId, roleCode = "TEST_ROLE"))
    }

    @Test
    fun `createRole 重复编码抛异常`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            roleAdminService.createRole(name = "重复", code = RoleEnum.USER.name)
        }
    }

    @Test
    fun `updateRole 更新角色名称与描述`() = withTestApplication {
        val role = roleAdminService.createRole(name = "旧名", code = "UPDATE_ROLE")
        val updated = roleAdminService.updateRole(role.id, name = "新名", description = "新描述")
        assertEquals("新名", updated.name)
        assertEquals("新描述", updated.description)
    }

    @Test
    fun `deleteRole 内置角色不能删除`() = withTestApplication {
        assertFailsWith<IllegalArgumentException> {
            roleAdminService.deleteRole(TestUsers.roleIds.getValue(RoleEnum.USER))
        }
    }

    @Test
    fun `deleteRole 删除无引用的自定义角色`() = withTestApplication {
        val role = roleAdminService.createRole(name = "临时角色", code = "TEMP_ROLE")
        roleAdminService.deleteRole(role.id)
        assertTrue(rightService.roleDict.none { it.value.id == role.id }, "自定义角色删除后不应存在")
    }

    @Test
    fun `getRolesSegments 合并多角色权限段`() = withTestApplication {
        val adminId = TestUsers.userIds.getValue(TestUsers.ADMIN_NAME)
        val userId = TestUsers.userIds.getValue(TestUsers.USER_NAME)
        roleAdminService.addUserRole(adminId = adminId, updateUserId = userId, roleCode = RoleEnum.AUTHOR.name)

        val roleIds = rightService.getUserRoles(listOf(userId))[userId].orEmpty().map { it.roleId }
        val segments = rightService.getRolesSegments(roleIds)
        assertTrue(segments.isNotEmpty())

        // USER 与 AUTHOR 的权限合并后仍能通过 checkPermission
        assertTrue(rightService.checkPermission(listOf("BOOK:READ:ALL"), segments))
        assertTrue(rightService.checkPermission(listOf("CHAPTER:UPDATE:OWN"), segments))
    }

    @Test
    fun `checkPermission 空权限码列表直接通过`() = withTestApplication {
        assertTrue(rightService.checkPermission(emptyList(), emptyList()))
    }

    @Test
    fun `checkPermission 未知权限码返回 false`() = withTestApplication {
        val segments = rightService.getRolesSegments(listOf(TestUsers.roleIds.getValue(RoleEnum.SUPER_ADMIN)))
        assertFalse(rightService.checkPermission(listOf("NO:SUCH:CODE"), segments))
    }
}
