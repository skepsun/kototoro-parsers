package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentParserSource

internal class CopyContentChapterDedupeTest {

    private val source = ContentParserSource.COPYMANGA

    private fun chapter(
        url: String,
        number: Float,
        branch: String,
        title: String? = "第${number.toInt()}话",
    ): ContentChapter = ContentChapter(
        id = url.hashCode().toLong(),
        title = title,
        number = number,
        volume = 0,
        url = url,
        scanlator = null,
        uploadDate = 0,
        branch = branch,
        source = source,
    )

    @Test
    fun `duplicate numbers in one branch are renumbered uniquely and keep order`() {
        val input = listOf(
            chapter("b/a", 1f, "默认"),
            chapter("b/b", 1f, "默认"),
            chapter("b/c", 2f, "默认"),
        )
        val out = dedupeChapterNumbers(input)
        assertEquals(3, out.size)
        val numbers = out.map { it.number }
        assertTrue(numbers.toSet().size == numbers.size, "章节编号应唯一: $numbers")
        assertEquals(listOf(1f, 2f, 3f), numbers)
    }

    @Test
    fun `parallel numbers across branches are preserved`() {
        // 简体/繁體 各自的"第1话、第2话"应原样保留，互不重排
        val input = listOf(
            chapter("b1/c1", 1f, "简体"),
            chapter("b1/c2", 2f, "简体"),
            chapter("b2/c1", 1f, "繁體"),
            chapter("b2/c2", 2f, "繁體"),
        )
        val deduped = input.groupBy { it.branch }.values.flatMap { dedupeChapterNumbers(it) }
        assertEquals(
            mapOf("简体" to listOf(1f, 2f), "繁體" to listOf(1f, 2f)),
            deduped.groupBy({ it.branch }, { it.number }),
        )
    }

    @Test
    fun `exact duplicate urls are dropped once`() {
        val input = listOf(chapter("b/a", 1f, "默认"), chapter("b/a", 1f, "默认"))
        val out = dedupeChapterNumbers(input)
        assertEquals(1, out.size)
    }

    @Test
    fun `already unique chapters stay unchanged`() {
        val input = listOf(chapter("b/a", 1f, "默认"), chapter("b/b", 2f, "默认"), chapter("b/c", 3f, "默认"))
        val out = dedupeChapterNumbers(input)
        assertEquals(input, out)
    }

    @Test
    fun `decimal numbers also stay distinct`() {
        val input = listOf(chapter("b/a", 1.5f, "默认"), chapter("b/b", 2f, "默认"))
        val out = dedupeChapterNumbers(input)
        assertEquals(listOf(1.5f, 2f), out.map { it.number })
    }
}
