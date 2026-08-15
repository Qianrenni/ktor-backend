package com.qianrenni.modules.user

import com.qianrenni.common.AppConfig
import com.qianrenni.modules.admin.RightService
import com.qianrenni.modules.user.RequestTokenGet
import com.qianrenni.infrastructure.database.DatabaseManager
import com.qianrenni.common.RoleEnum
import com.qianrenni.models.tables.FullUser
import com.qianrenni.models.tables.RoleTable
import com.qianrenni.models.tables.UserRoleTable
import com.qianrenni.models.tables.UserTable
import com.qianrenni.models.tables.toFullUser
import com.qianrenni.common.util.PasswordUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update


class UserService(
    private val config: AppConfig,
    private val databaseManager: DatabaseManager,
    private val rightService: RightService,
    private val captchaService: CaptchaService,
) {

    fun getUserAvatar(userId: Int) = "${config.serverUrl}/static/guga.webp"
    /**
     * 根据用户ID获取用户信息
     * @param userId 用户ID
     * @return 用户对象
     */
    suspend fun getUserById(userId: Int): FullUser {
        val user = databaseManager.suspendedTransaction(readOnly = true) {
            UserTable.selectAll()
                .where { UserTable.id eq userId }
                .limit(1)
                .singleOrNull()
        }
        val fullUser = user?.toFullUser()
            ?: throw IllegalArgumentException("用户不存在")
        if (!fullUser.isActive) {
            throw IllegalArgumentException("用户已禁用")
        }
        val rolIds =
            rightService.getUserRoles(listOf(fullUser.id))[fullUser.id]?.map { it.roleId } ?: emptyList()
        check(rolIds.isNotEmpty()) { "用户没有无权限" }
        return fullUser.copy(right = rightService.getRolesSegments((rolIds)))
    }

    /**
     * 用户登录
     * @param xCaptchaId 验证码ID
     * @param requestTokenGet 登录请求数据
     * @return 用户对象
     */
    suspend fun login(xCaptchaId: String, requestTokenGet: RequestTokenGet): FullUser {
        // 1. 先校验验证码
        val isCaptchaValid = captchaService.verifyCaptcha(requestTokenGet.captcha, xCaptchaId)
        if (!isCaptchaValid) {
            throw IllegalArgumentException("验证码错误或已过期")
        }

        // 2. 数据库查询必须放在 IO 线程
        val user = databaseManager.suspendedTransaction {

            UserTable.selectAll()
                .where { UserTable.email eq requestTokenGet.userName }
                .limit(1)
                .singleOrNull()
        }
        return when(user){
            null->throw IllegalArgumentException("账号不存在")
            else -> {
                if (!user[UserTable.isActive]) {
                    throw IllegalArgumentException("账号已禁用")
                }
                when (PasswordUtils.verify(requestTokenGet.password, user[UserTable.password])) {
                    false->throw IllegalArgumentException("密码错误")
                    else -> {
                        val res = user.toFullUser()
                        val roleIds = rightService.getUserRoles(listOf(res.id))[res.id]?.map { it.roleId }
                            ?: emptyList()
                        check(roleIds.isNotEmpty()) { "用户没有无权限" }
                        res.copy(right = rightService.getRolesSegments(roleIds))
                    }
                }
            }
        }

    }

    /**
     * 获取用户数量
     */
    suspend fun getUserCount(): Int {
        return databaseManager.suspendedTransaction(readOnly = true) {
            UserTable.selectAll().count()
        }.toInt()
    }

    /**
     * 创建新用户(注册)
     * @param username 用户名
     * @param email 邮箱
     * @param password 密码
     * @param avatar 头像URL
     */
    suspend fun createUser(username: String, email: String, password: String, avatar: String = "") {
        // 检查用户名是否已存在
        databaseManager.suspendedTransaction(readOnly = true) {
            UserTable.selectAll()
                .where { (UserTable.userName eq username) and (UserTable.email eq email) }
                .limit(1)
                .singleOrNull()
        }?.let { throw IllegalArgumentException("邮箱已被注册") }

        // 创建新用户
        val hashedPassword = PasswordUtils.hash(password)
        databaseManager.suspendedTransaction {
            val userId = UserTable.insert {
                it[userName] = username
                it[UserTable.password] = hashedPassword
                it[UserTable.email] = email
                it[UserTable.avatar] = avatar
                it[isActive] = true
            } get UserTable.id
            // 添加用户角色：直接插入 user_role，而不是调用 addUserRole。
            // addUserRole 内部自己开事务（newSuspendedTransaction 总是新连接），
            // 在 FK 检查时看不到本事务未提交的 user 行——H2 立即报错，MySQL 依赖 InnoDB 锁等待侥幸通过。
            val userRoleId = RoleTable.selectAll().where { RoleTable.code eq RoleEnum.USER.name }.single()[RoleTable.id].value
            UserRoleTable.insert {
                it[UserRoleTable.userId] = userId.value
                it[UserRoleTable.roleId] = userRoleId
                it[UserRoleTable.grantedBy] = null
            }
        }

    }

    /**
     * 更新用户密码
     * @param userEmail 用户邮箱
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     */
    suspend fun updatePassword(userEmail: String, oldPassword: String, newPassword: String): Boolean {
        val user = databaseManager.suspendedTransaction(readOnly = true) {
            UserTable.selectAll()
                .where { UserTable.email eq userEmail }
                .limit(1)
                .singleOrNull()
        }

        if (user == null) {
            throw IllegalArgumentException("账号不存在")
        }

        if (!PasswordUtils.verify(oldPassword, user[UserTable.password])) {
            throw IllegalArgumentException("旧密码错误")
        }

        val hashedPassword = PasswordUtils.hash(newPassword)
        databaseManager.suspendedTransaction {
            UserTable.update({ UserTable.email eq userEmail }) {
                it[UserTable.password] = hashedPassword
            }
        }

        return true
    }

    /**
     * 根据邮箱获取用户
     * @param userEmail 用户邮箱
     */
    suspend fun getUserByEmail(userEmail: String): FullUser {
        val user = databaseManager.suspendedTransaction(readOnly = true) {
            UserTable.selectAll()
                .where { UserTable.email eq userEmail }
                .limit(1)
                .singleOrNull()
        }

        return user?.toFullUser()
            ?: throw IllegalArgumentException("用户不存在")
    }

    /**
     * 忘记密码
     * @param userAccount 用户账号(邮箱)
     * @param newPassword 新密码
     * @param verifyCode 验证码
     */
    suspend fun forgotPassword(userAccount: String, newPassword: String, verifyCode: String): Boolean {
        val isVerifyCodeValid = captchaService.verifyCode(
            keyPrefix = "forgot_password:$userAccount",
            answer = verifyCode
        )
        if (!isVerifyCodeValid) {
            throw IllegalArgumentException("验证码错误")
        }

        val hashedPassword = PasswordUtils.hash(newPassword)
        databaseManager.suspendedTransaction {
            UserTable.update({ UserTable.email eq userAccount }) {
                it[UserTable.password] = hashedPassword
            }
        }

        return true
    }
}