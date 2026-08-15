package com.qianrenni.infrastructure.storage

import com.qianrenni.testutil.*

import com.qianrenni.infrastructure.storage.TxtChapterParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TxtChapterParser 单元测试：覆盖三种章节标题格式、无章节兜底、描述提取等纯解析逻辑。
 */
class TestTxtChapterParser {

    @Test
    fun `解析 第X章 格式`() {
        val content = """
            这是一本书的描述
            # 楔子

            楔子内容第一行
            楔子内容第二行

            第一章 初入江湖

            江湖内容

            第100章 尾声

            尾声内容
        """.trimIndent()

        val result = TxtChapterParser.parse(content)
        // "# 楔子" 匹配 Markdown 标题格式，是第一个章节而非描述的一部分；标题规范化后去除 "# " 前缀
        assertEquals("这是一本书的描述", result.description)
        assertEquals(3, result.chapters.size)

        assertEquals("楔子", result.chapters[0].title)
        assertEquals("楔子内容第一行\n楔子内容第二行", result.chapters[0].content)

        assertEquals("初入江湖", result.chapters[1].title)
        assertEquals("江湖内容", result.chapters[1].content)

        assertEquals("尾声", result.chapters[2].title)
        assertEquals("尾声内容", result.chapters[2].content)
    }

    @Test
    fun `解析 第X节 格式`() {
        val content = """
            第一节 开始

            开始内容

            第二节 继续

            继续内容
        """.trimIndent()

        val result = TxtChapterParser.parse(content)
        assertEquals("", result.description)
        assertEquals(2, result.chapters.size)
        assertEquals("开始", result.chapters[0].title)
        assertEquals("继续", result.chapters[1].title)
    }

    @Test
    fun `解析 Markdown 井号标题格式`() {
        val content = """
            # 第一章

            内容A

            # 第二章

            内容B
        """.trimIndent()

        val result = TxtChapterParser.parse(content)
        assertEquals(2, result.chapters.size)
        assertEquals("第一章", result.chapters[0].title)
        assertEquals("内容A", result.chapters[0].content)
    }

    @Test
    fun `无章节标题时整本书作为单章`() {
        val content = """
            纯文本内容没有标题
            第二行内容
        """.trimIndent()

        val result = TxtChapterParser.parse(content)
        assertEquals(1, result.chapters.size)
        assertEquals("第一章", result.chapters[0].title)
        assertTrue(result.chapters[0].content.contains("纯文本内容"))
        assertEquals("", result.description)
    }

    @Test
    fun `空内容返回空结果`() {
        val result = TxtChapterParser.parse("")
        assertEquals(0, result.chapters.size)
        assertEquals("", result.description)
    }

    @Test
    fun `标题行本身作为标题当无副标题时`() {
        // "第一章" 无副标题 → 标题保留整行原文
        val result = TxtChapterParser.parse("第一章\n内容")
        assertEquals(1, result.chapters.size)
        assertEquals("第一章", result.chapters[0].title)
        assertEquals("内容", result.chapters[0].content)
    }

    @Test
    fun `中文数字章节号解析`() {
        val content = """
            第一章 序
            内容1
            第一百零二章 终
            内容2
        """.trimIndent()

        val result = TxtChapterParser.parse(content)
        // 含"零"的中文数字（如一百零二）必须被识别
        assertEquals(2, result.chapters.size)
        assertEquals("序", result.chapters[0].title)
        assertEquals("终", result.chapters[1].title)
        assertEquals("内容1", result.chapters[0].content)
        assertEquals("内容2", result.chapters[1].content)
    }

    @Test
    fun `空白行不产生章节`() {
        val content = "\n\n\n   \n"
        val result = TxtChapterParser.parse(content)
        assertEquals(0, result.chapters.size)
    }
}
