package com.qianrenni.common.util

import at.favre.lib.crypto.bcrypt.BCrypt


object PasswordUtils {

    /**
     * 对原始密码进行加盐哈希加密
     * @param plainPassword 原始明文密码
     * @return 加密后的哈希字符串 (包含盐值)
     */
    fun hash(plainPassword: String): String {
        // BCrypt.withDefaults() 使用默认的强度 (cost factor = 10)
        // 你也可以指定强度,例如: BCrypt.with(BCrypt.Version.VERSION_2A, 12)
        return BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray())
    }

    /**
     * 验证原始密码是否与加密后的哈希匹配
     * @param plainPassword 用户输入的原始明文密码
     * @param hashedPassword 数据库中存储的加密哈希字符串
     * @return true 如果匹配,false 如果不匹配
     */
    fun verify(plainPassword: String, hashedPassword: String): Boolean {
        val result = BCrypt.verifyer().verify(plainPassword.toCharArray(), hashedPassword)
        return result.verified
    }
}