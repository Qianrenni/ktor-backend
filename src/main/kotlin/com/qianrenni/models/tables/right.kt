package com.qianrenni.models.tables

import com.qianrenni.common.ActionEnum
import com.qianrenni.common.ResourceTypeEnum
import com.qianrenni.common.ScopeEnum
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

object PermissionTable : IntIdTable(name = "permission") {
    val name = varchar("name", 100)
    val resourceType = enumerationByName<ResourceTypeEnum>("resourceType", 25)
    val action = enumerationByName<ActionEnum>("action", 25)
    val scope = enumerationByName<ScopeEnum>("scope", 25)
    val bitPosition = integer("bitPosition")
    val createdAt = datetime(name = "createdAt").clientDefault { LocalDateTime.now() }
    val updatedAt = datetime(name = "updatedAt").clientDefault { LocalDateTime.now() }
}

object RoleTable : IntIdTable(name = "role") {
    val name = varchar("name", 50)
    val code = varchar("code", 50)
    val description = varchar("description", 255).nullable()
    val createdAt = datetime(name = "createdAt").clientDefault { LocalDateTime.now() }
    val updatedAt = datetime(name = "updatedAt").clientDefault { LocalDateTime.now() }
}

object RoleInheritanceTable : Table(name = "role_inheritance") {
    val childId = integer("childId").references(RoleTable.id)
    val parentId = integer("parentId").references(RoleTable.id)
    val createdAt = datetime(name = "createdAt").clientDefault { LocalDateTime.now() }
}

object RolePermissionTable : Table(name = "role_permission") {
    val roleId = integer("roleId").references(RoleTable.id)
    val permissionId = integer("permissionId").references(PermissionTable.id)
    val createdAt = datetime(name = "createdAt").clientDefault { LocalDateTime.now() }
}

object UserRoleTable : Table(name = "user_role") {
    val userId = integer("userId").references(UserTable.id)
    val roleId = integer("roleId").references(RoleTable.id)
    val grantedBy = integer("grantedBy").references(UserTable.id).nullable()
    val grantedAt = datetime(name = "grantedAt").clientDefault { LocalDateTime.now() }
}

@Serializable
data class Permission(
    val id: Int,
    val name: String,
    val resourceType: ResourceTypeEnum,
    val action: ActionEnum,
    val scope: ScopeEnum,
    val bitPosition: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class Role(
    val id: Int,
    val name: String,
    val code: String,
    val description: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class RoleInheritance(
    val childId: Int,
    val parentId: Int,
    val createdAt: String
)

@Serializable
data class RolePermission(
    val roleId: Int,
    val permissionId: Int,
    val createdAt: String
)

@Serializable
data class UserRole(
    val userId: Int,
    val roleId: Int,
    val grantedBy: Int?,
    val grantedAt: String
)

fun ResultRow.toUserRole() = UserRole(
    userId = this[UserRoleTable.userId],
    roleId = this[UserRoleTable.roleId],
    grantedBy = this[UserRoleTable.grantedBy],
    grantedAt = this[UserRoleTable.grantedAt].toString()
)

fun ResultRow.toRolePermission() = RolePermission(
    roleId = this[RolePermissionTable.roleId],
    permissionId = this[RolePermissionTable.permissionId],
    createdAt = this[RolePermissionTable.createdAt].toString()
)

fun ResultRow.toRoleInheritance() = RoleInheritance(
    childId = this[RoleInheritanceTable.childId],
    parentId = this[RoleInheritanceTable.parentId],
    createdAt = this[RoleInheritanceTable.createdAt].toString()
)

fun ResultRow.toRole() = Role(
    id = this[RoleTable.id].value,
    name = this[RoleTable.name],
    code = this[RoleTable.code],
    description = this[RoleTable.description],
    createdAt = this[RoleTable.createdAt].toString(),
    updatedAt = this[RoleTable.updatedAt].toString()
)

fun ResultRow.toPermission() = Permission(
    id = this[PermissionTable.id].value,
    name = this[PermissionTable.name],
    resourceType = this[PermissionTable.resourceType],
    action = this[PermissionTable.action],
    scope = this[PermissionTable.scope],
    bitPosition = this[PermissionTable.bitPosition],
    createdAt = this[PermissionTable.createdAt].toString(),
    updatedAt = this[PermissionTable.updatedAt].toString()
)
