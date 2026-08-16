package com.qianrenni.modules.user

import com.qianrenni.common.AppConfig
import com.qianrenni.infrastructure.database.RedisManager
import com.qianrenni.common.util.KaptchaImageGenerator
import com.qianrenni.common.util.TokenGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

class CaptchaService(
    private val config: AppConfig,
    private val redisManager: RedisManager,
) {
    companion object {
        /** 验证码最大尝试次数（超过后作废，防暴力破解） */
        const val MAX_VERIFY_ATTEMPTS = 5

        /** 失败计数窗口（秒） */
        const val MAX_VERIFY_ATTEMPTS_WINDOW_SECONDS = 600L
    }

    /**
     * 生成随机验证码文本
     * @param length 验证码长度,默认4
     * @return 由字母和数字组成的随机字符串
     */
    fun generateCaptchaText(length: Int = 4): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }

    suspend fun getCaptcha(): Pair<String, ByteArray> {
        val (text, image) = withContext(Dispatchers.Default) {
            val text = generateCaptchaText()
            val image = KaptchaImageGenerator.generate(text)
            Pair(text, image)
        }
        val redis = redisManager.getAsyncCommands()
        val captchaId = TokenGenerator.uuid()
        redis.setex(captchaId, config.captchaExpire.toLong(), text).await()
        return Pair(captchaId, image)
    }

    suspend fun verifyCaptcha(text: String, captchaId: String): Boolean {
        val redis = redisManager.getAsyncCommands()
        val storedText = redis.get(captchaId).await() ?: return false

        // 验证成功后删除验证码(防止重放攻击)
        redis.del(captchaId).await()

        return storedText.equals(text, ignoreCase = true)
    }

    /**
     * 获取数字验证码(用于忘记密码等场景)
     * @param keyPrefix 缓存键前缀
     * @param length 验证码长度,默认6
     * @param expire 有效期,默认120秒
     * @return 验证码
     */
    suspend fun getVerifyCode(
        keyPrefix: String,
        length: Int = 6,
        expire: Long = config.captchaExpire.toLong()
    ): String {
        val answer = generateCaptchaText(length)
        val redis = redisManager.getAsyncCommands()
        val existing = redis.get(keyPrefix).await()
        if (existing != null) {
            throw IllegalArgumentException("Previous verify code exists, please try again later")
        }
        redis.setex(keyPrefix, expire, answer).await()
        return answer
    }

    /**
     * 验证数字验证码
     * @param keyPrefix 缓存键前缀
     * @param answer 用户输入的验证码
     * @return 验证结果
     */
    suspend fun verifyCode(keyPrefix: String, answer: String): Boolean {
        val redis = redisManager.getAsyncCommands()
        val cachedAnswer = redis.get(keyPrefix).await() ?: return false
        if (answer == cachedAnswer) {
            redis.del(keyPrefix).await()
            redis.del("$keyPrefix:attempts").await()
            return true
        }
        // 安全加固（L3）：失败计数，达到上限后作废验证码并清空计数，防止暴力破解
        val attemptsKey = "$keyPrefix:attempts"
        val attempts = redis.incr(attemptsKey).await().toInt()
        if (attempts == 1) {
            redis.expire(attemptsKey, MAX_VERIFY_ATTEMPTS_WINDOW_SECONDS).await()
        }
        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            redis.del(keyPrefix).await()
            redis.del(attemptsKey).await()
        }
        return false
    }
}