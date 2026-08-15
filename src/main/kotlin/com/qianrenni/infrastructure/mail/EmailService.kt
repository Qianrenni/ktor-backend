package com.qianrenni.infrastructure.mail

import com.qianrenni.common.AppConfig
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger

class EmailService(
    private val config: AppConfig,
    private val logger: Logger,
) {
    private val session: Session by lazy {
        val props = java.util.Properties().apply {
            put("mail.smtp.host", config.smtpServer)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
        }
        Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(config.emailAccount, config.emailCode)
            }
        })
    }

    suspend fun sendEmail(
        toEmails: List<String>,
        subject: String,
        body: String,
        isHtml: Boolean = false,
        ccEmails: List<String>? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.emailAccount))
                setRecipients(Message.RecipientType.TO, toEmails.map { InternetAddress(it) }.toTypedArray())
                ccEmails?.let {
                    setRecipients(Message.RecipientType.CC, it.map { addr -> InternetAddress(addr) }.toTypedArray())
                }
                setSubject(subject, "UTF-8")
                val mimeBody = if (isHtml) MimeBodyPart().apply {
                    setContent(body, "text/html; charset=UTF-8")
                } else MimeBodyPart().apply {
                    setText(body, "UTF-8")
                }
                val multipart = MimeMultipart().apply { addBodyPart(mimeBody) }
                setContent(multipart)
            }

            Transport.send(message)
            logger.info("邮件已成功发送至: ${toEmails.joinToString(", ")}")
            true
        } catch (e: AuthenticationFailedException) {
            logger.error("SMTP 认证失败: 请检查邮箱或授权码是否正确", e)
            false
        } catch (e: SendFailedException) {
            logger.error("收件人地址被拒绝: 请检查邮箱格式或是否存在", e)
            false
        } catch (e: MessagingException) {
            logger.error("邮件发送失败: ${e.message}", e)
            false
        } catch (e: Exception) {
            logger.error("邮件发送失败: ${e.message}", e)
            false
        }
    }
}