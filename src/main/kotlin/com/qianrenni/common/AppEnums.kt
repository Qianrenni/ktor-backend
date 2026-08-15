package com.qianrenni.common

/**
 * 应用枚举定义
 * 对应 Python 项目中的 app/enum/enum.py
 */

/**
 * 书籍状态枚举
 */
enum class BookStatus(val value: String) {
    PENDING("pending"),           // 待提交
    DELETING("deleting"),       // 删除中
    REVIEWING("reviewing"),       // 审核中
    APPROVED("approved"),         // 审核通过
    REJECTED("rejected"),         // 审核拒绝
    PUBLISHED("published"),       // 已发布
    DELETED("deleted");

    companion object {
        fun fromValue(value: String): BookStatus {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown book status: $value")
        }
    }
}

/**
 * 资源类型枚举
 */
enum class ResourceTypeEnum(val value: String) {
    BOOK("book"),
    USER("user"),
    PERMISSION("permission"),
    CHAPTER("chapter"),
    SHELF("shelf"),
    SYSTEM("system"),
    COMMENT("comment");

    companion object {
        fun fromValue(value: String): ResourceTypeEnum {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown resource type: $value")
        }
    }
}

/**
 * 操作类型枚举
 */
enum class ActionEnum(val value: String) {
    READ("read"),
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    AUDIT("audit"),
    MANAGE("manage");

    companion object {
        fun fromValue(value: String): ActionEnum {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown action: $value")
        }
    }
}

/**
 * 权限范围枚举
 */
enum class ScopeEnum(val value: String) {
    OWN("own"),      // 自己的资源
    ALL("all");      // 所有资源

    companion object {
        fun fromValue(value: String): ScopeEnum {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown scope: $value")
        }
    }
}

/**
 * 角色枚举
 */
enum class RoleEnum(val code: String) {
    USER("user"),                    // 普通用户
    REVIEWER("reviewer"),        // 审核员
    AUTHOR("author"),              // 作者
    ADMIN("admin"),                 // 管理员
    SUPER_ADMIN("super_admin"); // 超级管理员

    companion object {
        fun fromValue(value: String): RoleEnum? {
            return entries.find { it.code.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * 阅读事件类型枚举
 */
enum class ReportEnum(val value: String) {
    ENTER("enter"),       // 进入章节
    EXIT("exit"),         // 离开章节
    HEARTBEAT("heartbeat"); // 心跳(阅读中)

    companion object {
        fun fromValue(value: String): ReportEnum {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown report type: $value")
        }
    }
}