package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 风车动漫离线夹具测试：
 * - 分类 `/list/{码}-{页}.html`，卡片在 `.imgs ul li`，封面懒加载在 `data-original`，第二个 `<p>` 是连载状态；
 * - 搜索关键字走 Cookie `keyww`，返回同样的卡片结构；
 * - 详情信息块是「标签名：值」纯文本，标签用空格分隔，站点没有剧情简介；
 * - 播放页 `paly_js` 是混淆 base64：`-`→`a`、`.`→`=`。
 */
class FengcheTest {

    private val parser = Fengche(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/fengche/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        BASE_URI,
    )

    private fun html(name: String): String = javaClass.getResourceAsStream("/fixtures/fengche/$name")!!
        .bufferedReader().use { it.readText() }

    @Test
    fun `only the update order is advertised`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
    }

    @Test
    fun `filter options list the five animation channels`() = kotlinx.coroutines.runBlocking {
        val options = parser.getFilterOptions()
        assertEquals(
            listOf("国产动画", "日本动画", "欧美动画", "其他动画", "动画电影"),
            options.tagGroups.single().tags.map { it.title },
        )
        // 分类码是站点的内部数字 id，不能猜
        assertEquals(listOf("137", "138", "139", "157", "158"), options.availableTags.map { it.key })
    }

    @Test
    fun `list and search urls use the plain http host`() {
        // 该域名的 HTTPS 握手失败，只能走 http
        assertEquals("http://www.fcbbcc.com/list/137-2.html", parser.categoryUrl("137", 2))
        assertEquals("http://www.fcbbcc.com/common/search.aspx", parser.searchUrl())
    }

    @Test
    fun `parse category cards`() {
        val tag = ContentTag("国产动画", "137", parser.source)
        val items = parser.parseCards(fixture("list.html"), tag)
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("永生", first.title)
        assertEquals("/content/4997.html", first.url)
        assertEquals("http://www.fcbbcc.com/content/4997.html", first.publicUrl)
        assertTrue(first.coverUrl.orEmpty().startsWith("https://"), first.coverUrl.orEmpty())
        assertEquals(RATING_UNKNOWN, first.rating)
        assertEquals(ContentState.ONGOING, first.state)
        assertEquals(setOf(tag), first.tags)
    }

    @Test
    fun `parse search cards without channel tag`() {
        val items = parser.parseCards(fixture("search.html"), null)
        assertEquals(2, items.size)
        assertEquals("吞噬星空", items.first().title)
        assertTrue(items.first().tags.isEmpty())
    }

    @Test
    fun `parse detail info block`() {
        val info = parser.parseInfoBlock(fixture("detail.html"))
        assertEquals(ContentState.ONGOING, info.state)
        // 标签是空格分隔的纯文本，类型也算一个标签
        assertEquals(
            listOf("大陆", "奇幻", "玄幻", "战斗", "国产动画"),
            info.tags.map { it.title },
        )
        assertTrue(info.altTitles.isEmpty())
    }

    @Test
    fun `finished content is detected from detail info`() {
        val doc = Jsoup.parse(
            """<div class="intro r"><div class="alex"><p>更新：已完结</p><span>标签：日本 奇幻</span></div></div>""",
            BASE_URI,
        )
        assertEquals(ContentState.FINISHED, parser.parseInfoBlock(doc).state)
    }

    @Test
    fun `detail description is dropped when it only repeats the title`() {
        // 站点 meta description 基本就是「标题,标题在线观看」，这类内容不算简介
        assertNull(parser.parseDescription(fixture("detail.html"), "吞噬星空"))

        val withSynopsis = Jsoup.parse(
            """<meta name="description" content="一部关于少年冒险的长篇动画"/>""",
            BASE_URI,
        )
        assertEquals("一部关于少年冒险的长篇动画", parser.parseDescription(withSynopsis, "某作品"))
    }

    @Test
    fun `parse chapter list keeps the first playlist only`() {
        val chapters = parser.parseChapters(fixture("detail.html"))
        assertEquals(listOf("第01集", "第02集", "第03集", "第04集"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f, 4f), chapters.map { it.number })
        assertEquals("/playn/4631-4631-0.html", chapters.first().url)
        // 隐藏的「播放列表2」不能被混进来
        assertTrue(chapters.none { it.url.contains("9999") })
    }

    @Test
    fun `parse video url from obfuscated payload`() {
        val url = parser.parseVideoUrl(html("play.html"), "http://www.fcbbcc.com/playn/4631-4631-0.html")
        // 载荷里 `-` 代表 `a`、`.` 代表 `=`，解出来是带中文路径的 m3u8
        assertEquals("https://p.bvvvvvvvvv1f.com/video/tunshixingkong/第01集/index.m3u8", url)
    }

    @Test
    fun `player page without payload is rejected`() {
        val error = assertThrows(ParseException::class.java) {
            parser.parseVideoUrl(html("play_invalid.html"), "http://www.fcbbcc.com/playn/1-1-0.html")
        }
        assertTrue(error.message.orEmpty().contains("paly_js"))
    }

    @Test
    fun `card without content link is skipped`() {
        val doc = Jsoup.parse(
            """<div class="imgs"><ul><li><a href="/list/137-1.html"><img alt="频道"/></a>
            <p><a title="频道">频道</a></p></li></ul></div>""",
            BASE_URI,
        )
        assertTrue(parser.parseCards(doc, null).isEmpty())
    }

    private companion object {
        const val BASE_URI = "http://www.fcbbcc.com/"
    }
}
