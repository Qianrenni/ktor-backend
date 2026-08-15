package com.qianrenni.modules.system

import com.qianrenni.common.config.ConfigDomain
import com.qianrenni.common.config.ConfigService
import kotlinx.serialization.Serializable

/** 配置领域视图（管理端展示用） */
@Serializable
data class ConfigView(
    val domain: String,
    val values: Map<String, String>,
)

/**
 * 动态配置管理服务（管理端读写入口）。
 * 委托 [ConfigService] 完成读取/校验/写 Redis/失效本地缓存。
 */
class SystemConfigService(private val configService: ConfigService) {

    /** 所有领域的当前生效值 */
    fun list(): List<ConfigView> =
        ConfigDomain.entries.map { ConfigView(it.name, configService.currentValues(it)) }

    /**
     * 更新某领域配置（部分字段合并）。
     * @return 更新成功返回新视图；领域不存在或参数非法返回 null
     */
    fun update(domainName: String, values: Map<String, String>): ConfigView? {
        val domain = ConfigDomain.fromName(domainName) ?: return null
        return if (configService.update(domain, values)) {
            ConfigView(domain.name, configService.currentValues(domain))
        } else {
            null
        }
    }
}
