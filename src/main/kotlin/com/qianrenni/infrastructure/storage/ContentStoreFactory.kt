package com.qianrenni.infrastructure.storage

import com.qianrenni.common.AppConfig
import kotlin.io.path.Path

/**
 * 默认 [ChapterStoreFactory] 实现：基于 [ContentStoreService]（本地 LSM 文件存储）。
 *
 * 存储路径规则：{contentDir}/{storeDir}/{storeName}（storeName 即业务 id，如 bookId）。
 */
class ContentStoreFactory(private val config: AppConfig) : ChapterStoreFactory {
    override fun open(storeDir: String, storeName: String): ChapterStore =
        ContentStoreService(
            name = storeName,
            baseDir = Path(config.contentDir, storeDir).toString()
        )
}
