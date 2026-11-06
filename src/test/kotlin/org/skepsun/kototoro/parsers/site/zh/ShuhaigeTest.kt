package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 书海阁离线夹具测试（m.shuhaige.net，layui 移动站）：
 * - 列表 `/{分类}/`、`/{分类}/list_{页码}.html`（20 部/页），卡片 `ul.list > li`；
 * - 详情 `/shu_{id}.html`，`div.detail img` 封面、`p.name strong` 书名、`p.author a` 作者、`div.intro` 简介；
 * - 目录 `/{id}/`、`/{id}_{页码}/`（50 章/页），章节在 `ul.read li a`，总页数在 `select option`；
 * - 正文 `/{id}/{章节id}.html`，只取 `div.content > p`，同页「大家在看」区块要丢掉。
 */
class ShuhaigeTest {

    private val parser = Shuhaige(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/shuhaige/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://m.shuhaige.net/",
    )

    @Test
    fun `only update order is advertised and search is on`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertEquals(20, parser.pageSize)
    }

    @Test
    fun `filters expose the sixteen site categories`() = kotlinx.coroutines.runBlocking {
        val tags = parser.getFilterOptions().tagGroups.single().tags
        assertEquals(16, tags.size)
        assertEquals("玄幻", tags.first().title)
        assertEquals("XuanHuan", tags.first().key)
        assertEquals("其他", tags.last().title)
        assertEquals("QiTa", tags.last().key)
    }

    @Test
    fun `list cards carry cover author state and relative url`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(3, items.size)

        val first = items[0]
        assertEquals("灵山", first.title)
        assertEquals("曳光", first.authors.single())
        assertEquals("/shu_450395.html", first.url)
        assertEquals("https://m.shuhaige.net/shu_450395.html", first.publicUrl)
        assertEquals("https://img.shuhaige.net/450395/358091.jpg", first.coverUrl)
        assertEquals(ContentState.ONGOING, first.state)
        assertTrue(items.all { it.url.matches(Regex("/shu_\\d+\\.html")) })
        assertTrue(items.all { it.coverUrl != null })
    }

    @Test
    fun `detail fields come from the layui blocks`() {
        val doc = fixture("detail.html")
        assertEquals("1", doc.selectFirst("body[article-id]")!!.attr("article-id"))
        assertEquals("诸天从秦时开始长生", doc.selectFirst("p.name strong")!!.text())
        assertEquals("天允山下的骨灰", doc.selectFirst("p.author a")!!.text())
        assertEquals("武侠", doc.selectFirst("div.detail p a.layui-btn")!!.text())
        assertEquals("连载中", doc.selectFirst("div.detail p span.layui-btn.layui-btn-normal")!!.text())

        val description = parser.parseDescription(doc)
        assertTrue(description != null && description.length > 30)
        assertTrue(description!!.startsWith("金光九界"))
    }

    @Test
    fun `catalog page yields chapters in order and detects the last page`() {
        val doc = fixture("catalog.html")
        val chapters = parser.parseCatalogPage(doc, "1")
        assertEquals(3, chapters.size)
        assertEquals("第1章 序章", chapters.first().title)
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertTrue(chapters.all { it.url.startsWith("/1/") && it.url.endsWith(".html") })
        assertEquals(5, parser.catalogLastPage(doc, "1"))
    }

    @Test
    fun `catalog parsing is scoped to the requested book`() {
        val doc = fixture("catalog.html")
        assertTrue(parser.parseCatalogPage(doc, "999").isEmpty())
        assertNull(parser.catalogLastPage(doc, "999"))
    }

    @Test
    fun `reader keeps only the chapter body`() {
        val html = requireNotNull(parser.extractChapter(fixture("reader.html")))
        assertTrue(html.contains("发生什么事了"))
        assertTrue(!html.contains("别的书"))
        assertTrue(!html.contains("shu_46209"))
    }

    @Test
    fun `reader without a content block returns null instead of empty html`() {
        assertNull(parser.extractChapter(Jsoup.parse("<html><body><div class=\"content\"></div></body></html>")))
        assertNull(parser.extractChapter(Jsoup.parse("<html><body></body></html>")))
    }
}
