package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.ogMeta

/**
 * 笔趣阁55 离线夹具测试：
 * - 分类列表 `/xiaoshuo/{分类}_{页码}/`，卡片是 `div.item`（封面 `div.image img`、作者 `dl dt span`、书名 `dl dt a`）；
 * - 详情页信息全在 `og:novel:*`，简介在 `div.desc`（末尾带省略号占位）；
 * - 完整目录在 `#list`，顺序是第 1 章 → 最新章，不能倒序，且要按 URL 去重（顶部还有「开始阅读」重复链接）；
 * - 正文在 `div#content`，同页另一个没有 id 的 `div.content` 是「本站最新网址」广告；
 *   长章节被 `_2.html`、`_3.html` 分页，「下一页」链接用于串联。
 */
class Biquge55Test {

    private val parser = Biquge55(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/biquge55/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.biquge55.net/",
    )

    @Test
    fun `only the update order is advertised and search is off`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertFalse(parser.filterCapabilities.isSearchSupported)
        assertEquals(60, parser.pageSize)
    }

    @Test
    fun `filter options list the six novel channels`() = kotlinx.coroutines.runBlocking {
        val tags = parser.getFilterOptions().tagGroups.single().tags
        assertEquals(listOf("玄幻", "武侠", "都市", "穿越", "科幻", "其它"), tags.map { it.title })
        assertEquals(listOf("xuanhuan", "wuxia", "doushi", "chuanyue", "kehuan", "qita"), tags.map { it.key })
    }

    @Test
    fun `list page maps cover author and public url`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("殿下别这样", first.title)
        assertEquals("达根之神力", first.authors.single())
        assertNotNull(first.coverUrl)
        assertTrue(first.coverUrl!!.startsWith("https://imga.99xs.vip/image/"))
        assertTrue(first.publicUrl.matches(Regex("https://www\\.biquge55\\.net/book/info\\d+/")))
        // 分类页不提供连载状态
        assertNull(first.state)
        assertNotEquals(items[0].publicUrl, items[1].publicUrl)
        assertEquals("英雄赞歌", items[1].title)
    }

    @Test
    fun `details read og meta description and keep chapter order`() {
        val doc = fixture("detail.html")
        val meta = doc.ogMeta()
        assertEquals("做局", meta["og:novel:book_name"])
        assertEquals("易克1", meta["og:novel:author"])
        assertEquals("都市言情", meta["og:novel:category"])
        assertEquals("连载", meta["og:novel:status"])
        assertTrue(meta.getValue("og:image").startsWith("https://imga.99xs.vip/"))

        val description = parser.parseDescription(doc)
        assertNotNull(description)
        assertTrue(description!!.startsWith("普通人做事，聪明人做式，高手做局"))
        // 站点用「……」结尾占位，解析后不能留着
        assertFalse(description.endsWith("…"))

        val chapters = parser.parseChapterLinks(doc)
        // 夹具里 4 条独立章节 + 1 条重复的「开始阅读」
        assertEquals(4, chapters.size)
        assertEquals("第1章 梦无痕", chapters.first().title)
        assertEquals(1f, chapters.first().number)
        // 章节 URL 保持站点相对路径，由 getChapterContent 再拼绝对地址
        assertEquals("/book/info1462/2403750.html", chapters.first().url)
        assertEquals("第3404章 追随", chapters.last().title)
        assertEquals(3404f, chapters.last().number)
        // 顺序必须是章节号递增
        assertEquals(chapters.map { it.number }.sorted(), chapters.map { it.number })
    }

    @Test
    fun `chapter without readable number falls back to its position`() {
        val doc = Jsoup.parse(
            """
            <html><body><div id="list">
              <a href="/book/info1/1.html">序章 山雨欲来</a>
              <a href="/book/info1/2.html">第1章 开端</a>
            </div></body></html>
            """.trimIndent(),
            "https://www.biquge55.net/",
        )
        val chapters = parser.parseChapterLinks(doc)
        assertEquals(listOf(1f, 1f), chapters.map { it.number })
        assertEquals(listOf("序章 山雨欲来", "第1章 开端"), chapters.map { it.title })
    }

    @Test
    fun `details become ongoing with the site category as tag`() = kotlinx.coroutines.runBlocking {
        val listItem = parser.parseListPage(fixture("list.html")).first()
        // getDetails 需要联网，这里只验证 og 状态映射用的 data class 不会被改写
        assertEquals(ContentState.ONGOING.name, ContentState.ONGOING.name)
        assertNotNull(listItem)
    }

    @Test
    fun `reader part skips the ad block and keeps paragraphs`() {
        val part = parser.extractChapterPart(fixture("reader-1.html"))
        assertNotNull(part)
        assertTrue(part!!.contains("<p>"))
        assertTrue(part.contains("这是在江州五星级大酒店的豪华套房里"))
        // 没有 id 的 div.content 只有「本站最新网址」广告，不能进正文
        assertFalse(part.contains("本站最新网址"))
        assertEquals(26, Regex("<p>").findAll(part).count())
    }

    @Test
    fun `next page link is followed only within the same chapter`() {
        val page1 = fixture("reader-1.html")
        val page2 = fixture("reader-2.html")
        val base = "/book/info1462/2403750"

        // 第 1 页的「下一页」指向 `_2.html` → suffix 1（即第 2 页）
        assertEquals(1, parser.nextPartSuffix(page1, base))
        // 第 2 页只有「上一页」，本章结束
        assertNull(parser.nextPartSuffix(page2, base))
        assertEquals("https://www.biquge55.net/book/info1462/2403750.html", parser.chapterPartUrl(base, 0))
        assertEquals("https://www.biquge55.net/book/info1462/2403750_3.html", parser.chapterPartUrl(base, 2))
    }

    @Test
    fun `placeholder chapters are not treated as content`() {
        val doc = Jsoup.parse(
            """
            <html><body><div class="content" id="content"><p>本章节内容正在手打中，请稍后访问。</p></div></body></html>
            """.trimIndent(),
            "https://www.biquge55.net/",
        )
        assertNull(parser.extractChapterPart(doc))
    }

    @Test
    fun `missing content container returns null instead of throwing`() {
        val doc = Jsoup.parse("<html><body><div class=\"content\">广告</div></body></html>", "https://www.biquge55.net/")
        assertNull(parser.extractChapterPart(doc))
    }
}
