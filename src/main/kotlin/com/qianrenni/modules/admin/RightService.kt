package com.qianrenni.modules.admin

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.models.tables.Permission
import com.qianrenni.models.tables.Role
import com.qianrenni.models.tables.RolePermissionTable
import com.qianrenni.models.tables.UserRole
import com.qianrenni.models.tables.UserRoleTable
import com.qianrenni.models.tables.toUserRole
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * 权限服务（只读 / 校验侧，从原 727 行 RightService 拆分而来）。
 *
 * 职责：
 * - 用户权限位图合并（getRolesSegments）；
 * - 权限校验（checkPermission）；
 * - 用户角色 / 角色权限查询（getUserRoles / getRolePermissions）；
 * - 向 Controller 暴露只读缓存快照（permissionDict / roleDict 等）。
 *
 * 状态与加载在 [PermissionCache]，写操作在 [RoleAdminService]，三者共享同一实例。
 */
class RightService(
    private val cache: PermissionCache,
    private val databaseManager: DatabaseManager,
) {

    // ==================== 只读缓存快照（委托共享缓存） ====================

    /** 权限 ID 到 Permission 对象的映射 */
    val permissionDict: Map<Int, Permission> get() = cache.permissionDict

    /** 权限编码到 bitPosition 的映射 */
    val permissionCodeDict: Map<String, Int> get() = cache.permissionCodeDict

    /** 角色 ID 到 Role 对象的映射 */
    val roleDict: Map<Int, Role> get() = cache.roleDict

    /** 角色继承关系：role_id -> 祖先角色 ID 列表(含自身) */
    val roleInheritanceDict: Map<Int, List<Int>> get() = cache.roleInheritanceDict

    /** 角色权限位图：role_id -> segments */
    val roleSegmentDict: Map<Int, List<Int>> get() = cache.roleSegmentDict

    /** 角色层级 */
    val roleLevels: Map<Int, Int> get() = cache.roleLevels

    // ==================== 缓存重载（委托） ====================

    /** 重新加载权限缓存（管理端手动刷新入口） */
    suspend fun restart() = cache.restart()

    // ==================== 无锁读取方法 ====================

    /**
     * 根据用户的角色 ID 列表,合并生成用户的完整权限位图。
     *
     * 合并规则:按位 OR(只要任一角色拥有该权限,用户就拥有)。
     * 线程安全:捕获 @Volatile 引用到局部变量,保证单次调用内使用同一快照。
     */
    fun getRolesSegments(roleIds: List<Int>): List<Int> {
        if (roleIds.isEmpty()) {
            return emptyList()
        }
        // 捕获 volatile 引用到局部变量,保证本次调用使用同一快照
        val segments = cache.roleSegmentDict
        var result = emptyList<Int>()
        val parentSegments = roleIds.mapNotNull { segments[it] }
        for (parentSegment in parentSegments) {
            result = cache.mergeSegment(parentSegment, result)
        }
        return result
    }

    /**
     * 校验用户是否拥有所有指定的权限。
     *
     * 流程:
     * 1. 将 requiredPermissionCodes 转换为 bitPosition 列表。
     * 2. 构建所需的权限位图(requireBitmap)。
     * 3. 与用户权限位图(userPermissionBitmap)按段比较。
     *
     * 安全说明:
     * - userPermissionBitmap 应来自可信 JWT payload(已签名,不可伪造)。
     * - 若 requiredPermissionCodes 中包含未知权限,直接返回 false。
     */
    fun checkPermission(
        requiredPermissionCodes: List<String>,
        userPermissionBitmap: List<Int>
    ): Boolean {
        // 捕获 volatile 引用到局部变量
        val codeDict = cache.permissionCodeDict

        // 1. 转换权限编码为 bitPosition
        val requiredBits = mutableListOf<Int>()
        for (code in requiredPermissionCodes) {
            val bitPos = codeDict[code]
            if (bitPos == null) {
                return false // 未知权限,拒绝访问
            }
            requiredBits.add(bitPos)
        }

        // 2. 构建所需的权限位图
        val requireSegment = cache.positionToSegment(requiredBits)
        // 3. 对齐长度:若 userBitmap 段数不足,视为高位为 0
        val userBitmapPadded = userPermissionBitmap.toMutableList()
        while (userBitmapPadded.size < requireSegment.size) {
            userBitmapPadded.add(0)
        }

        // 4. 逐段检查是否满足所有权限
        for (index in requireSegment.indices) {
            val reqSeg = requireSegment[index]
            val userSeg = userBitmapPadded[index]
            if ((userSeg and reqSeg) != reqSeg) {
                return false
            }
        }

        return true
    }

    // ==================== 用户角色查询 ====================

    /**
     * 获取用户角色(数据库查询,不涉及缓存,无需加锁)。
     * @param userIds 用户 ID 列表
     * @return userId -> UserRole 列表
     */
    suspend fun getUserRoles(userIds: List<Int>): Map<Int, List<UserRole>> {
        return databaseManager.suspendedTransaction(readOnly = true) {
            UserRoleTable
                .selectAll()
                .where { UserRoleTable.userId inList userIds }
                .map { it.toUserRole() }
                .groupBy { it.userId }
        }
    }

    /**
     * 获取角色的权限列表。
     * 线程安全:捕获 @Volatile permissionDict 引用到局部变量。
     */
    suspend fun getRolePermissions(roleId: Int): List<Permission> {
        val permDict = cache.permissionDict // 捕获 volatile 引用
        return databaseManager.suspendedTransaction(readOnly = true) {
            RolePermissionTable
                .selectAll()
                .where { RolePermissionTable.roleId eq roleId }
                .map { permDict[it[RolePermissionTable.permissionId]]!! }
        }
    }
}

/**
 * 生成权限编码
 *
 * @param resource 资源
 * @param action 动作
 * @param scope 范围
 * @return 权限编码
 */
fun generatePermissionCode(
    resource: ResourceTypeEnum,
    action: ActionEnum,
    scope: ScopeEnum
): String {
    return "${resource}:${action}:${scope}"
}
