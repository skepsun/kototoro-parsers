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
 * 全本小说网 quanben.io 离线夹具测试：
 * - 分类/搜索页共用 `div.list2` 卡片（`h3 a` 书名、`img` 封面、`p:contains(作者) span` 作者）；
 * - 详情 `/n/{slug}/` 有 `og:novel:*` 和 `div.description`；
 * - 目录 `/n/{slug}/list.html` 的 `ul.list3` 一次性给全；站点标题只有「第N章」没有章名；
 * - 正文 `/n/{slug}/{序号}.html` 在 `div#content`，`span#ad` 是广告、`h1.headline` 只是章号。
 */
class QuanbenTest {

    private val parser = Quanben(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/quanben/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.quanben.io/",
    )

    @Test
    fun `update order and search capability`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertEquals(12, parser.pageSize)
    }

    @Test
    fun `filters expose eighteen categories`() = kotlinx.coroutines.runBlocking {
        val tags = parser.getFilterOptions().tagGroups.single().tags
        assertEquals(18, tags.size)
        assertEquals("玄幻", tags.first().title)
        assertEquals("xuanhuan", tags.first().key)
        assertTrue(tags.any { it.title == "耽美" && it.key == "danmei" })
    }

    @Test
    fun `list cards parse title author and cover`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("傲世丹神", first.title)
        assertEquals("寂小贼", first.authors.single())
        assertEquals("/n/aoshidanshen/", first.url)
        assertEquals("https://www.quanben.io/n/aoshidanshen/", first.publicUrl)
        assertNotNull(first.coverUrl)
        assertNull(first.state)
    }

    @Test
    fun `details read og metadata and description`() {
        val doc = fixture("detail.html")
        val meta = doc.ogMeta()
        assertEquals("寝室美狼", meta["og:novel:book_name"])
        assertEquals("布丁猪", meta["og:novel:author"])
        assertEquals("耽美小说", meta["og:novel:category"])
        assertEquals("完结", meta["og:novel:status"])

        val description = parser.parseDescription(doc)
        assertNotNull(description)
        assertTrue(description!!.startsWith("lm学院"))
        assertFalse(description.endsWith("…"))
    }

    @Test
    fun `catalog is ordered and tolerates the final unnumbered entry`() {
        val chapters = parser.parseChapterLinks(fixture("catalog.html"))
        assertEquals(4, chapters.size)
        assertEquals("/n/qinshimeilang_2/1.html", chapters.first().url)
        assertEquals(1f, chapters.first().number)
        assertEquals("第1章", chapters.first().title)
        // 最后一条是「完结」，没有章号，按前一章编号 +1 兜底
        assertEquals("完结", chapters.last().title)
        assertEquals(chapters.dropLast(1).last().number + 1, chapters.last().number)
        assertEquals(chapters.map { it.number }.sorted(), chapters.map { it.number })
    }

    @Test
    fun `reader strips ad span and keeps paragraphs`() {
        val html = parser.extractChapter(fixture("reader.html"))
        assertNotNull(html)
        assertTrue(html!!.contains("<p>"))
        assertFalse(html.contains("<span"))
        assertFalse(html.contains("<h1"))
        assertTrue(html.length > 300)
    }

    @Test
    fun `missing container returns null`() {
        val doc = Jsoup.parse("<html><body><div class=\"articlebody\">正文</div></body></html>", "https://www.quanben.io/")
        assertNull(parser.extractChapter(doc))
    }
}
