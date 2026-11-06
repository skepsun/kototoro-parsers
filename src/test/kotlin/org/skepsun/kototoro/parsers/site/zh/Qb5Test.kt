package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.ogMeta

/**
 * 全本小说网 qb5.io 离线夹具测试：
 * - 分类页 `/shuku-{id}.html`：`dl.hot > dd`，`h4 a` 书名、`a img` 封面、`p:contains(作者) span` 作者；
 * - 详情 `/xs-{id}.html`：信息全在 `og:novel:*`，简介取 `og:description`；
 * - 目录 `/xs-{id}/`：`ul.list li a` 正序给全（近 4000 章不分页），顶部有重复链接要去重；
 * - 正文 `/xs-{id}/du-{n}.html`：`div.articlebody#content`，首个 `h1` 是章节标题，`span#banner` 是广告。
 */
class Qb5Test {

    private val parser = Qb5(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/qb5/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.qb5.io/",
    )

    @Test
    fun `only update order is advertised and search is off`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertFalse(parser.filterCapabilities.isSearchSupported)
        assertEquals(12, parser.pageSize)
    }

    @Test
    fun `filters expose the twelve book store channels`() = kotlinx.coroutines.runBlocking {
        val tags = parser.getFilterOptions().tagGroups.single().tags
        assertEquals(12, tags.size)
        assertEquals("奇幻玄幻", tags.first().title)
        assertEquals("1", tags.first().key)
        assertEquals("其它分类", tags.last().title)
        assertEquals("0", tags.last().key)
    }

    @Test
    fun `list cards carry cover author and relative url`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("傲世丹神", first.title)
        assertEquals("寂小贼", first.authors.single())
        assertEquals("/xs-1.html", first.url)
        assertEquals("https://www.qb5.io/xs-1.html", first.publicUrl)
        assertTrue(first.coverUrl!!.startsWith("https://img.c0m.io/"))
        assertNull(first.state)
        assertEquals("异世邪君", items[1].title)
        assertEquals("风凌天下", items[1].authors.single())
    }

    @Test
    fun `og metadata feeds title author category and state`() {
        val meta = fixture("detail.html").ogMeta()
        assertEquals("傲世丹神", meta["og:novel:book_name"])
        assertEquals("寂小贼", meta["og:novel:author"])
        assertEquals("奇幻玄幻小说", meta["og:novel:category"])
        assertEquals("完结", meta["og:novel:status"])
        assertEquals("https://www.qb5.io/xs-1/", meta["og:novel:read_url"])
        assertTrue(meta.getValue("og:description").contains("废柴少年得无上传承"))
    }

    @Test
    fun `description drops the book and author prefix`() {
        assertEquals(
            "废柴少年得无上传承,获逆天神脉",
            parser.cleanDescription("《傲世丹神》全本小说,作者:寂小贼,废柴少年得无上传承,获逆天神脉"),
        )
        assertEquals("已经是一句干净的简介", parser.cleanDescription("已经是一句干净的简介"))
        assertNull(parser.cleanDescription("   "))
        assertNull(parser.cleanDescription(null))
    }

    @Test
    fun `catalog keeps order and drops duplicate entries`() {
        val chapters = parser.parseChapterLinks(fixture("catalog.html"))
        // 夹具里 4 条独立章节 + 1 条重复链接
        assertEquals(4, chapters.size)
        assertEquals("第1章 地狱灵芝", chapters.first().title)
        assertEquals("/xs-1/du-1.html", chapters.first().url)
        assertEquals(1f, chapters.first().number)
        assertEquals("/xs-1/du-3818.html", chapters.last().url)
        assertEquals(chapters.map { it.number }.sorted(), chapters.map { it.number })
        assertEquals("https://www.qb5.io/xs-1/du-1.html", chapters.first().url.toAbsoluteUrlForTest())
    }

    @Test
    fun `chapter without a number inherits previous one`() {
        val doc = Jsoup.parse(
            """
            <html><body><ul class="list">
              <li><a href="/xs-9/du-1.html">第1章 开局</a></li>
              <li><a href="/xs-9/du-2.html">完本感言</a></li>
            </ul></body></html>
            """.trimIndent(),
            "https://www.qb5.io/",
        )
        val chapters = parser.parseChapterLinks(doc)
        assertEquals(listOf(1f, 2f), chapters.map { it.number })
    }

    @Test
    fun `reader keeps paragraphs and drops heading and ads`() {
        val html = parser.extractChapter(fixture("reader.html"))
        assertNotNull(html)
        assertTrue(html!!.contains("天空中乌云密布"))
        // h1 章节标题与 span#banner 广告都不应进正文（站点会在第一段再重复一次章名，那属于正文）
        assertFalse(html.contains("<h1"))
        assertFalse(html.contains("<span"))
        assertTrue(html.startsWith("<p>"))
    }

    @Test
    fun `missing content container returns null`() {
        val doc = Jsoup.parse("<html><body><div class=\"articlebody\">正文</div></body></html>", "https://www.qb5.io/")
        assertNull(parser.extractChapter(doc))
    }

    @Test
    fun `placeholder chapter is treated as empty`() {
        val doc = Jsoup.parse(
            "<html><body><div class=\"articlebody\" id=\"content\"><p>本章节内容正在手打中，请稍后访问。</p></div></body></html>",
            "https://www.qb5.io/",
        )
        assertNull(parser.extractChapter(doc))
    }

    private fun String.toAbsoluteUrlForTest(): String =
        if (startsWith("/")) "https://www.qb5.io$this" else this
}
