package com.qianrenni.infrastructure.storage

/**
 * 章节/评论内容存储抽象。
 *
 * 隔离底层 LSM 文件存储实现（[ContentStoreService]），业务层只依赖该接口，
 * 便于日后替换存储后端（对象存储 / 数据库 / 分布式文件系统）而不改动业务代码。
 */
interface ChapterStore : AutoCloseable {

    /** 读取内容，已删除返回空字符串 */
    suspend fun readChapter(contentId: Int): String

    /** 新增或更新内容（追加式日志 + 索引快照） */
    suspend fun update(contentId: Int, content: String)

    /** 逻辑删除内容（幂等） */
    suspend fun delete(contentId: Int)
}

/**
 * 内容存储工厂：按「目录类型（storeDir：book / comment/book / comment/chapter）
 * + 业务标识（storeName：bookId / chapterId）」打开一个存储单元。
 *
 * 业务代码通过此工厂获取 [ChapterStore]，不应直接实例化 [ContentStoreService]。
 */
interface ChapterStoreFactory {
    fun open(storeDir: String, storeName: String): ChapterStore
}
