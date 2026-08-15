package com.qianrenni.modules.admin

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.Permission
import com.qianrenni.models.tables.PermissionTable
import com.qianrenni.models.tables.Role
import com.qianrenni.models.tables.RoleInheritance
import com.qianrenni.models.tables.RoleInheritanceTable
import com.qianrenni.models.tables.RolePermissionTable
import com.qianrenni.models.tables.RoleTable
import com.qianrenni.models.tables.toPermission
import com.qianrenni.models.tables.toRole
import com.qianrenni.models.tables.toRoleInheritance
import com.qianrenni.models.tables.toRolePermission
import org.slf4j.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.selectAll

/**
 * 权限数据的共享内存缓存（从原 RightService 拆出的状态与加载部分）。
 *
 * 职责：
 * - 持有全部 @Volatile 权限/角色内存字典（读端无锁快照）；
 * - 位图工具（positionToSegment / mergeSegment / flattenInheritRole）；
 * - 预加载（start / restart，由应用启动时或管理端手动触发）。
 *
 * 写操作（角色/权限增删改）不在此类，统一收敛到 [RoleAdminService]；
 * 读操作（权限校验 / 角色查询）在 [RightService]。
 * 二者共享同一个 [PermissionCache] 实例，保证内存字典单一来源。
 */
class PermissionCache(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val logger: Logger,
) {
    private val lock = Mutex()

    // 权限 ID 到 Permission 对象的映射
    @Volatile
    var permissionDict: Map<Int, Permission> = emptyMap()
        internal set

    // 权限编码(如 "admin:user:read:all")到 bitPosition 的映射,用于快速查找
    @Volatile
    var permissionCodeDict: Map<String, Int> = emptyMap()
        internal set

    // 角色 ID 到 Role 对象的映射
    @Volatile
    var roleDict: Map<Int, Role> = emptyMap()
        internal set

    // 角色继承关系:role_id -> 祖先角色 ID 列表(包含自身)
    @Volatile
    var roleInheritanceDict: Map<Int, List<Int>> = emptyMap()
        internal set

    // 角色权限位图:role_id -> [segment0, segment1, ...]
    @Volatile
    var roleSegmentDict: Map<Int, List<Int>> = emptyMap()
        internal set

    @Volatile
    var roleLevels: Map<Int, Int> = emptyMap()
        internal set

    // ==================== 位图工具方法 ====================

    /**
     * 将一组 bitPosition 设置到位图段列表中。
     * @param bitPositions 要设置的权限位位置列表(如 [7, 70])
     * @return 修改后的 segments
     */
    internal fun positionToSegment(bitPositions: List<Int>): List<Int> {
        val result = mutableListOf<Int>()
        for (bitPos in bitPositions) {
            val segmentIndex = bitPos / config.permissionBitLength
            val offset = bitPos % config.permissionBitLength
            val neededSegments = segmentIndex + 1

            // 如果当前段数不足,扩展至 neededSegments
            repeat(neededSegments - result.size) {
                result.add(0)
            }

            // 在对应段中设置该位
            result[segmentIndex] = result[segmentIndex] or (1 shl offset)
        }
        return result.toList()
    }

    /**
     * 合并多个父角色的权限位图和子角色的权限位图。
     * @return 合并后的权限位图列表
     */
    internal fun mergeSegment(
        parentsSegment: List<Int>,
        childSegment: List<Int>
    ): List<Int> {
        val result = childSegment.toMutableList()
        if (parentsSegment.size > result.size) {
            repeat(parentsSegment.size - result.size) {
                result.add(0)
            }
        }
        for (index in parentsSegment.indices) {
            result[index] = result[index] or parentsSegment[index]
        }
        return result.toList()
    }

    /**
     * 递归展开角色继承关系,构建角色权限位图。
     * @return Pair(角色 -> 祖先列表, 角色 -> 层级)
     */
    private fun flattenInheritRole(
        roleInheritanceList: List<RoleInheritance>
    ): Pair<Map<Int, List<Int>>, Map<Int, Int>> {
        // child -> parents
        val childToParents = mutableMapOf<Int, MutableList<Int>>()
        // parent -> children
        val parentToChildren = mutableMapOf<Int, MutableList<Int>>()
        // 入度计数
        val inDegree = mutableMapOf<Int, Int>()
        // 所有角色
        val allRoles = mutableSetOf<Int>()

        for (rel in roleInheritanceList) {
            val childId = rel.childId
            val parentId = rel.parentId

            childToParents.getOrPut(childId) { mutableListOf() }.add(parentId)
            parentToChildren.getOrPut(parentId) { mutableListOf() }.add(childId)
            inDegree[childId] = inDegree.getOrDefault(childId, 0) + 1
            allRoles.add(childId)
            allRoles.add(parentId)
        }

        // 初始化结果
        val result = mutableMapOf<Int, List<Int>>()
        val queue = ArrayDeque<Int>()
        val levels = mutableMapOf<Int, Int>()
        // 入度为 0 的角色(根)
        for (role in allRoles) {
            if (inDegree.getOrDefault(role, 0) == 0) {
                queue.add(role)
                result[role] = mutableListOf(role)
                levels[role] = 0
            }
        }

        // 拓扑排序 + 合并权限
        while (queue.isNotEmpty()) {
            val role = queue.removeFirst()
            var ancestors = result[role]!!

            // 聚合所有父角色的祖先
            for (parent in childToParents.getOrDefault(role, emptyList())) {
                val parentAncestors = result[parent]!!
                ancestors = ancestors + parentAncestors
            }

            // 去重(保留顺序)
            result[role] = ancestors.distinct()

            // 推进子节点
            for (child in parentToChildren.getOrDefault(role, emptyList())) {
                inDegree[child] = inDegree.getOrDefault(child, 1) - 1
                if (inDegree[child] == 0) {
                    queue.add(child)
                    result[child] = listOf(child)
                    levels[child] = levels.getOrDefault(role, 0) + 1
                }
            }
        }
        return Pair(result, levels)
    }

    // ==================== 核心加载逻辑 ====================

    /**
     * 【内部方法】实际的权限数据加载逻辑,不加锁。
     * 调用方必须已持有 lock,或在启动时(单线程)直接调用。
     */
    private suspend fun doStart() {
        logger.debug("正在预加载权限、角色及角色-权限关联数据...")
        databaseManager.suspendedTransaction(readOnly = true) {
            // 查询所有权限
            val permissions = PermissionTable.selectAll().map { it.toPermission() }
            // 构建角色继承关系
            val roleInheritanceList =
                RoleInheritanceTable.selectAll().map { it.toRoleInheritance() }
            val rolePermissions = RolePermissionTable.selectAll().map { it.toRolePermission() }
            // 构建角色字典
            val roles = RoleTable.selectAll().map { it.toRole() }
            // 构建权限字典
            val newPermissionDict = permissions.associateBy { it.id }
            val newPermissionCodeDict = permissions.associate {
                "${it.resourceType}:${it.action}:${it.scope}" to it.bitPosition
            }
            val newRoleDict = roles.associateBy { it.id }

            // 构建角色权限位图
            val roleSegmentMap = mutableMapOf<Int, List<Int>>()
            for (rp in rolePermissions) {
                val roleId = rp.roleId
                val permissionId = rp.permissionId
                val perm = newPermissionDict[permissionId]
                val bitPos = perm!!.bitPosition
                val segments = roleSegmentMap.getOrPut(roleId) { emptyList() }
                roleSegmentMap[roleId] = mergeSegment(
                    positionToSegment(listOf(bitPos)),
                    segments
                )
            }

            val flatResult = flattenInheritRole(roleInheritanceList)
            val newRoleInheritanceDict = flatResult.first
            val newRoleLevels = flatResult.second

            for ((roleId, ancestors) in newRoleInheritanceDict) {
                for (ancestor in ancestors) {
                    val ancestorSegments = roleSegmentMap[ancestor] ?: emptyList()
                    val currentSegments = roleSegmentMap[roleId] ?: emptyList()
                    roleSegmentMap[roleId] = mergeSegment(ancestorSegments, currentSegments)
                }
            }

            // 一次性替换所有 volatile 引用,尽量保证读端看到一致的数据
            permissionDict = newPermissionDict
            permissionCodeDict = newPermissionCodeDict
            roleDict = newRoleDict
            roleInheritanceDict = newRoleInheritanceDict
            roleLevels = newRoleLevels
            roleSegmentDict = roleSegmentMap.toMap()

            logger.debug("权限信息加载完成")
            logger.debug("权限字典: {}", permissionCodeDict)
            logger.debug("权限继承关系: {}", roleInheritanceDict)
            logger.debug("角色权限位图: {}", roleSegmentDict)
            logger.debug("角色级别: {}", roleLevels)
        }
    }

    /**
     * 应用启动时调用(或外部手动触发重新加载):预加载所有权限、角色及角色-权限关联数据到内存。
     * 加锁调用 doStart()。
     */
    suspend fun start() {
        lock.withLock { doStart() }
    }

    /**
     * 重新加载权限缓存(公开方法)。
     */
    suspend fun restart() {
        start()
    }

    /**
     * 在缓存锁内执行写操作，保证「校验 → DB 写入 → 缓存刷新」的原子性（防 TOCTOU）。
     * 供 [RoleAdminService] 使用；读端（[RightService]）走 volatile 快照无需加锁。
     */
    suspend fun <T> withLock(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * 【内部方法】在已持有锁的上下文中刷新内存缓存。
     * 调用方必须已通过 [withLock] 持锁，避免递归死锁。
     */
    internal suspend fun refreshLocked() = doStart()
}
