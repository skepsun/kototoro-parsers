package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.textOrNull

// 漫画社（311s.com）离线夹具测试：
// - 卡片是 div.comic-item：封面在 a.comic-cover img，状态在 span.update-badge；
// - 详情：评分/作者在 .comic-stats .stat-item，题材/状态在 .comic-tags span.tag，目录 .chapter-list；
// - 阅读页图片是 div.comic-content img.comic-image 直链 webp。
class ManhuasheTest {

    private val parser = Manhuashe(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/manhuashe/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.311s.com/",
    )

    @Test
    fun `capabilities advertise search only`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertEquals(20, parser.pageSize)
    }

    @Test
    fun `list page maps card title cover and state`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(3, items.size)
        assertEquals("一人之下", items[0].title)
        assertEquals("/comic_13871.html", items[0].url)
        assertEquals("https://comic.5um.net/comic/cover/yirenzhixia.webp", items[0].coverUrl)
        assertEquals(ContentState.ONGOING, items[0].state)
        assertEquals(setOf("米橙子"), items[0].authors)
    }

    @Test
    fun `detail page reads rating author tags state and chapters`() {
        val doc = fixture("detail.html")
        val tags = parser.parseTags(fixture("list.html"))
        assertTrue(tags.isEmpty(), "列表页没有题材链接")

        val chapters = parser.parseChapters(doc)
        assertEquals(5, chapters.size)
        assertEquals("第1话 1.姐姐1", chapters.first().title)
        assertEquals("/chapter_13871_4992.html", chapters.first().url)
        assertEquals(5f, chapters.last().number)

        val tagSpans = doc.select(".comic-tags span.tag").map { it.text() }
        assertEquals("热血 玄幻 搞笑 战斗 爆笑 格斗 冒险", tagSpans[0])
        assertEquals("连载", tagSpans[1])
    }

    @Test
    fun `reader page yields every inline webp`() {
        val images = parser.parsePageImages(fixture("reader.html"))
        assertEquals(3, images.size)
        assertTrue(images.all { it.startsWith("https://manhua.5um.net/") && it.endsWith(".webp") })
    }

    @Test
    fun `detail metadata keeps rating and author`() {
        val doc = fixture("detail.html")
        val stats = doc.select(".comic-stats .stat-item span").mapNotNull { it.textOrNull() }
        assertNotNull(stats.firstOrNull { it.contains("评分") })
        assertTrue(stats.any { it.contains("作者") })
    }

    @Test
    fun `tag nodes are parsed from category links`() {
        val doc = Jsoup.parse(
            """
            <div class="tags">
                <a href="/category/tags/10">玄幻</a>
                <a href="/category/tags/109">战斗</a>
                <a href="/category/tags/10">玄幻</a>
            </div>
            """.trimIndent(),
            "https://www.311s.com/",
        )
        val tags = parser.parseTags(doc)
        assertEquals(2, tags.size)
        assertEquals("10", tags[0].key)
        assertEquals("玄幻", tags[0].title)
    }
}
