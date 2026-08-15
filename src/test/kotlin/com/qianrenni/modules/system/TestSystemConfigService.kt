package com.qianrenni.modules.system

import com.qianrenni.common.config.ConfigDomain
import com.qianrenni.common.config.ConfigService
import com.qianrenni.testutil.InMemoryConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 动态配置管理服务测试：列表、更新成功、非法参数拒绝。
 */
class TestSystemConfigService {

    private fun service(source: InMemoryConfigSource) = SystemConfigService(ConfigService(source))

    @Test
    fun `list 返回所有领域默认值`() {
        val svc = service(InMemoryConfigSource())
        val list = svc.list()
        assertEquals(ConfigDomain.entries.size, list.size)
        val compact = list.first { it.domain == ConfigDomain.COMPACT.name }
        assertEquals("0.4", compact.values["garbageThreshold"])
    }

    @Test
    fun `update 成功并返回新视图`() {
        val source = InMemoryConfigSource(
            mapOf(
                ConfigDomain.COMPACT to mapOf(
                    "garbageThreshold" to "0.4",
                    "minLiveBytes" to "65536",
                    "maxFileBytes" to "1048576"
                )
            )
        )
        val svc = service(source)
        val view = svc.update(ConfigDomain.COMPACT.name, mapOf("garbageThreshold" to "0.6"))
        assertNotNull(view)
        assertEquals("0.6", view.values["garbageThreshold"])
        assertEquals("65536", view.values["minLiveBytes"])
    }

    @Test
    fun `update 领域不存在或参数非法返回 null`() {
        val source = InMemoryConfigSource()
        val svc = service(source)

        assertNull(svc.update("NOT_EXISTS", mapOf("garbageThreshold" to "0.6")))
        assertNull(
            svc.update(
                ConfigDomain.COMPACT.name,
                mapOf("garbageThreshold" to "2.0", "minLiveBytes" to "1", "maxFileBytes" to "2")
            )
        )
    }
}
