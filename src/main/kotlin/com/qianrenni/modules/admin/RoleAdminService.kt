package com.qianrenni.modules.admin

import com.qianrenni.common.RoleEnum
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.Role
import com.qianrenni.models.tables.RoleInheritanceTable
import com.qianrenni.models.tables.RolePermissionTable
import com.qianrenni.models.tables.RoleTable
import com.qianrenni.models.tables.UserRoleTable
import com.qianrenni.models.tables.toUserRole
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

/**
 * 角色与权限管理服务（从原 RightService 拆出的写操作部分）。
 *
 * 职责：角色 CRUD、角色权限分配/回收、角色继承、用户角色绑定。
 * 所有写操作统一在 [PermissionCache.withLock] 内完成「校验 → DB 写入 → 缓存刷新」，
 * 与 [RightService]（只读查询/校验）共享同一个 [PermissionCache] 实例。
 */
class RoleAdminService(
    private val cache: PermissionCache,
    private val databaseManager: DatabaseManager,
) {

    /**
     * 为用户添加角色。
     * 锁策略：权限级别校验 + DB 写入在锁内完成，保证校验与写入的原子性。
     */
    suspend fun addUserRole(adminId: Int? = null, updateUserId: Int, roleCode: String): Boolean {
        // 慢操作:在锁外查询用户角色
        val userRolesMap = adminId?.let { getUserRoles(listOf(it, updateUserId)) }

        return cache.withLock {
            // 权限级别校验:使用局部快照保证一致性
            val levels = cache.roleLevels
            adminId?.let {
                val p = userRolesMap!!
                val adminLevel = p[adminId]?.maxOf { ur -> levels[ur.roleId]!! }
                val userLevel = p[updateUserId]?.maxOf { ur -> levels[ur.roleId]!! }
                require(adminLevel != null && userLevel != null) { "用户权限不足" }
                require(adminLevel >= userLevel) { "权限不足" }
            }

            // 查找角色并插入
            val roles = cache.roleDict
            for (role in roles.values) {
                if (role.code == roleCode) {
                    databaseManager.suspendedTransaction {
                        UserRoleTable.insert {
                            it[UserRoleTable.userId] = updateUserId
                            it[UserRoleTable.roleId] = role.id
                            it[UserRoleTable.grantedBy] = adminId
                        }
                    }
                    return@withLock true
                }
            }
            false
        }
    }

    /**
     * 添加用户角色(按角色ID)。
     * 直接委托给 [addUserRole]，由其内部自行管理事务。
     */
    suspend fun addUserRoleById(adminId: Int? = null, updateUserId: Int, roleId: Int) {
        val role = cache.roleDict[roleId]
        require(role != null) { "角色不存在: $roleId" }
        addUserRole(adminId, updateUserId, role.code)
    }

    /**
     * 移除用户角色。
     * 锁策略：权限级别校验 + DB 删除在锁内完成，保证校验与删除的原子性。
     */
    suspend fun removeUserRole(adminId: Int? = null, userId: Int, roleId: Int) {
        // 慢操作:在锁外查询用户角色
        val userRolesMap = adminId?.let { getUserRoles(listOf(it, userId)) }

        cache.withLock {
            // 权限级别校验:使用局部快照保证一致性
            val levels = cache.roleLevels
            adminId?.let {
                val p = userRolesMap!!
                val adminLevel = p[adminId]?.maxOf { ur -> levels[ur.roleId]!! }
                val userLevel = p[userId]?.maxOf { ur -> levels[ur.roleId]!! }
                require(adminLevel != null && userLevel != null) { "用户权限不足" }
                require(adminLevel >= userLevel) { "权限不足" }
            }

            // 删除操作也在锁内,保证原子性
            databaseManager.suspendedTransaction {
                UserRoleTable.deleteWhere {
                    (UserRoleTable.userId eq userId) and (UserRoleTable.roleId eq roleId)
                }
            }
        }
    }

    /**
     * 创建角色。
     * 锁策略：校验 + DB 写入 + 缓存刷新,全部在同一个锁内完成,防止 TOCTOU。
     */
    suspend fun createRole(name: String, code: String, description: String? = null): Role {
        return cache.withLock {
            // 检查编码是否已存在(锁内,原子性)
            require(cache.roleDict.none { it.value.code.equals(code, ignoreCase = true) }) {
                "角色编码已存在: $code"
            }

            // DB 写入
            databaseManager.suspendedTransaction {
                RoleTable.insert {
                    it[RoleTable.name] = name
                    it[RoleTable.code] = code.uppercase()
                    description?.let { text -> it[RoleTable.description] = text }
                }
            }

            // 重新加载缓存(直接调用 doStart,因为已持有锁)
            cache.refreshLocked()

            cache.roleDict.values.firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: throw Exception("角色创建后未找到: $code")
        }
    }

    /**
     * 更新角色。
     * 锁策略：校验 + DB 写入 + 缓存刷新,全部在同一个锁内完成。
     */
    suspend fun updateRole(roleId: Int, name: String? = null, description: String? = null): Role {
        return cache.withLock {
            require(cache.roleDict.containsKey(roleId)) { "角色不存在: $roleId" }

            databaseManager.suspendedTransaction {
                RoleTable.update({ RoleTable.id eq roleId }) {
                    name?.let { newName -> it[RoleTable.name] = newName }
                    description?.let { newDesc -> it[RoleTable.description] = newDesc }
                }
            }

            cache.refreshLocked()
            cache.roleDict[roleId]!!
        }
    }

    /**
     * 删除角色。
     * 锁策略：校验 + 引用检查 + DB 删除 + 缓存刷新,全部在同一个锁内完成。
     */
    suspend fun deleteRole(roleId: Int) {
        cache.withLock {
            require(cache.roleDict.containsKey(roleId)) { "角色不存在: $roleId" }
            require(RoleEnum.fromValue(cache.roleDict[roleId]!!.code) == null) { "内置角色不能删除" }

            // 检查引用(全部在锁内)
            databaseManager.suspendedTransaction(readOnly = true) {
                val userCount =
                    UserRoleTable.selectAll().where { UserRoleTable.roleId eq roleId }.count()
                if (userCount > 0) {
                    throw IllegalArgumentException("该角色仍有 $userCount 个用户关联，请先解除用户角色绑定")
                }
                val inheritCount = RoleInheritanceTable.selectAll()
                    .where {
                        (RoleInheritanceTable.childId eq roleId) or (RoleInheritanceTable.parentId eq roleId)
                    }
                    .count()
                if (inheritCount > 0) {
                    throw IllegalArgumentException("该角色仍有 $inheritCount 个继承关系，请先解除角色继承")
                }
            }

            databaseManager.suspendedTransaction {
                RolePermissionTable.deleteWhere { RolePermissionTable.roleId eq roleId }
                RoleTable.deleteWhere { RoleTable.id eq roleId }
            }

            cache.refreshLocked()
        }
    }

    /**
     * 添加角色继承关系。
     * 锁策略：所有校验 + DB 写入 + 缓存刷新,全部在同一个锁内完成。
     */
    suspend fun addRoleInheritance(childId: Int, parentId: Int) {
        require(childId != parentId) { "子角色和父角色不能相同" }

        cache.withLock {
            require(cache.roleDict.containsKey(childId)) { "子角色不存在: $childId" }
            require(cache.roleDict.containsKey(parentId)) { "父角色不存在: $parentId" }

            // 检查是否已存在(锁内)
            databaseManager.suspendedTransaction(readOnly = true) {
                val exists = RoleInheritanceTable.selectAll()
                    .where {
                        (RoleInheritanceTable.childId eq childId) and (RoleInheritanceTable.parentId eq parentId)
                    }
                    .count() > 0
                if (exists) {
                    throw IllegalArgumentException("该继承关系已存在")
                }
            }

            // 循环继承检测(锁内,使用最新的内存数据)
            val ancestors = cache.roleInheritanceDict[parentId] ?: listOf(parentId)
            if (childId in ancestors) {
                throw IllegalArgumentException("循环继承检测失败：父角色已继承自该子角色")
            }

            databaseManager.suspendedTransaction {
                RoleInheritanceTable.insert {
                    it[RoleInheritanceTable.childId] = childId
                    it[RoleInheritanceTable.parentId] = parentId
                }
            }

            cache.refreshLocked()
        }
    }

    /**
     * 移除角色继承关系。
     * 锁策略：DB 删除 + 缓存刷新在锁内完成。
     */
    suspend fun removeRoleInheritance(childId: Int, parentId: Int) {
        cache.withLock {
            databaseManager.suspendedTransaction {
                RoleInheritanceTable.deleteWhere {
                    (RoleInheritanceTable.childId eq childId) and (RoleInheritanceTable.parentId eq parentId)
                }
            }
            cache.refreshLocked()
        }
    }

    /**
     * 为角色批量分配权限。
     * 锁策略：所有校验 + DB 写入 + 缓存刷新在同一个锁内完成。
     */
    suspend fun assignPermissionsToRole(roleId: Int, permissionIds: List<Int>) {
        cache.withLock {
            require(cache.roleDict.containsKey(roleId)) { "角色不存在: $roleId" }

            val permDict = cache.permissionDict
            for (permId in permissionIds) {
                require(permDict.containsKey(permId)) { "权限不存在: $permId" }
            }

            databaseManager.suspendedTransaction {
                for (permId in permissionIds) {
                    val exists = RolePermissionTable.selectAll()
                        .where {
                            (RolePermissionTable.roleId eq roleId) and (RolePermissionTable.permissionId eq permId)
                        }
                        .count() > 0
                    if (!exists) {
                        RolePermissionTable.insert {
                            it[RolePermissionTable.roleId] = roleId
                            it[RolePermissionTable.permissionId] = permId
                        }
                    }
                }
            }

            cache.refreshLocked()
        }
    }

    /**
     * 批量回收角色权限。
     * 锁策略：校验 + DB 删除 + 缓存刷新在同一个锁内完成。
     */
    suspend fun revokePermissionsFromRole(roleId: Int, permissionIds: List<Int>) {
        cache.withLock {
            require(cache.roleDict.containsKey(roleId)) { "角色不存在: $roleId" }

            databaseManager.suspendedTransaction {
                RolePermissionTable.deleteWhere {
                    (RolePermissionTable.roleId eq roleId) and (RolePermissionTable.permissionId inList permissionIds)
                }
            }

            cache.refreshLocked()
        }
    }

    /**
     * 获取用户角色(数据库查询,不涉及缓存,无需加锁)。
     * 供写操作锁外的「权限级别校验」复用。
     */
    private suspend fun getUserRoles(userIds: List<Int>): Map<Int, List<com.qianrenni.models.tables.UserRole>> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            UserRoleTable
                .selectAll()
                .where { UserRoleTable.userId inList userIds }
                .map { it.toUserRole() }
                .groupBy { it.userId }
        }
    }
}
