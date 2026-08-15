package com.qianrenni.infrastructure.storage

/**
 * TXT 书籍章节解析结果
 */
data class ParsedChapter(
    val title: String,
    val content: String
)

/**
 * TXT 格式书籍章节解析结果
 */
data class TxtParseResult(
    val description: String,
    val chapters: List<ParsedChapter>
)

/**
 * TXT 章节解析器
 *
 * 支持多种章节标题格式：
 * 1. "第X章 标题" — 如 "第一章 序章"、"第100章 尾声"
 * 2. "# 标题" — Markdown 风格的一级标题
 * 3. "第X节 标题" — 如 "第一节 开始"
 */
object TxtChapterParser {

    // 第一部分："第" + 中文数字/阿拉伯数字 + "章" + 可选标题（含"零"，如"第一百零二章"）
    private val chapterPattern1 = Regex("""^第[一二三四五六七八九十百千万零\d]+章\s*(.*)$""")

    // 第二部分：# 标题
    private val chapterPattern2 = Regex("""^#\s+(.*)$""")

    // 第三部分："第" + 中文数字/阿拉伯数字 + "节" + 可选标题（含"零"，如"第一百零二节"）
    private val chapterPattern3 = Regex("""^第[一二三四五六七八九十百千万零\d]+节\s*(.*)$""")

    /**
     * 判断一行是否为章节标题
     */
    private fun isChapterLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        chapterPattern1.find(trimmed)?.let { match ->
            val title = match.groupValues[1].ifBlank { trimmed }
            return title
        }
        chapterPattern2.find(trimmed)?.let { match ->
            val title = match.groupValues[1].ifBlank { trimmed }
            return title
        }
        chapterPattern3.find(trimmed)?.let { match ->
            val title = match.groupValues[1].ifBlank { trimmed }
            return title
        }
        return null
    }

    /**
     * 解析 TXT 文件内容，返回书籍描述和章节列表
     *
     * @param content TXT 文件全部内容
     * @return 解析结果
     */
    fun parse(content: String): TxtParseResult {
        val lines = content.lines()
        val chapters = mutableListOf<ParsedChapter>()
        val beforeFirstChapter = mutableListOf<String>()

        // 第一遍：找出所有章节标题行
        val chapterLineIndices = mutableListOf<Int>()
        for ((index, line) in lines.withIndex()) {
            val title = isChapterLine(line)
            if (title != null) {
                chapterLineIndices.add(index)
            }
        }

        if (chapterLineIndices.isEmpty()) {
            // 没有找到任何章节标题，整本书作为一个章节
            val body = lines.joinToString("\n").trim()
            if (body.isNotBlank()) {
                chapters.add(ParsedChapter(title = "第一章", content = body))
            }
            return TxtParseResult(description = "", chapters = chapters)
        }

        // 第一个章节标题之前的内容作为书籍描述
        for (i in 0 until chapterLineIndices[0]) {
            beforeFirstChapter.add(lines[i])
        }

        // 提取每个章节
        for (i in chapterLineIndices.indices) {
            val startLine = chapterLineIndices[i]
            val endLine = if (i + 1 < chapterLineIndices.size) chapterLineIndices[i + 1] else lines.size
            val chapterTitle = isChapterLine(lines[startLine]) ?: "第${i + 1}章"
            // 章节内容 = 标题行的下一行到下一个标题行之间的内容
            val contentLines = if (startLine + 1 < endLine) {
                lines.subList(startLine + 1, endLine)
            } else {
                emptyList()
            }
            val chapterContent = contentLines.joinToString("\n").trim()

            chapters.add(ParsedChapter(title = chapterTitle, content = chapterContent))
        }

        val description = beforeFirstChapter.joinToString("\n").trim()

        return TxtParseResult(description = description, chapters = chapters)
    }
}
