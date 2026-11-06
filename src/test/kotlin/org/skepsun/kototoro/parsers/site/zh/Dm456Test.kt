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
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 动漫456（MacCMS conch 模板）离线夹具测试：
 * - 分类页 `/fenlei/{码}/` 与后续页 `/fenlei/{码}-{页码}/`，卡片封面是懒加载的 `data-original`；
 * - `remarks` 就是连载状态（「更新至 N 集」/「全 N 集」）；
 * - 详情页信息块是 `<em>标签名：</em>` 后跟取值，类型/声优都在链接里；
 * - 剧集指到 `/player/{id}-{线路}-{集}/`，播放地址只存在 `player_aaaa` 配置里。
 */
class Dm456Test {

    private val parser = Dm456(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/dm456/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        BASE_URI,
    )

    private fun html(name: String): String = javaClass.getResourceAsStream("/fixtures/dm456/$name")!!
        .bufferedReader().use { it.readText() }

    @Test
    fun `only the update order is advertised`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
    }

    @Test
    fun `filter options list the ten site channels`() = kotlinx.coroutines.runBlocking {
        val options = parser.getFilterOptions()
        assertEquals(
            listOf("番剧", "国创", "剧场", "少儿", "美漫", "真人", "布袋", "原盘", "特典", "花絮"),
            options.tagGroups.single().tags.map { it.title },
        )
        // 分类码大小写是站点约定（xCCCCS/bCCCCS/qCCCCS 是小写开头），不能统一大写
        assertTrue(options.availableTags.any { it.key == "xCCCCS" })
        assertTrue(options.availableTags.any { it.key == "SCCCCS" })
    }

    @Test
    fun `category and search urls match site routes`() {
        assertEquals("https://www.dm456.com/fenlei/SCCCCS/", parser.categoryUrl("SCCCCS", 1))
        assertEquals("https://www.dm456.com/fenlei/SCCCCS-2/", parser.categoryUrl("SCCCCS", 2))
        assertEquals(
            "https://www.dm456.com/vodsearch/-------------/?wd=%E9%AC%BC%E7%81%AD",
            parser.searchUrl("鬼灭", 1),
        )
        assertEquals(
            "https://www.dm456.com/vodsearch/-------------/?wd=%E9%AC%BC%E7%81%AD&page=2",
            parser.searchUrl("鬼灭", 2),
        )
    }

    @Test
    fun `parse list cards`() {
        val tag = ContentTag("番剧", "SCCCCS", parser.source)
        val items = parser.parseList(fixture("list.html"), tag)
        assertEquals(3, items.size)

        val first = items[0]
        assertEquals("归还者的魔法要特别 第二季", first.title)
        assertEquals("/donghua/17180/", first.url)
        // 封面懒加载在 data-original 上，且要补成绝对地址
        assertTrue(first.coverUrl.orEmpty().startsWith("https://www.dm456.com/upload/vod/"), first.coverUrl.orEmpty())
        // 卡片评分是 10 分制
        assertEquals(0.3f, first.rating)
        assertEquals(ContentState.ONGOING, first.state)
        assertEquals(setOf(tag), first.tags)

        val second = items[1]
        assertEquals("黑暗机器", second.title)
        assertEquals(0.9f, second.rating)

        val finished = items.single { it.title.contains("测试作品") }
        assertEquals(ContentState.FINISHED, finished.state)
    }

    @Test
    fun `parse search cards without channel tag`() {
        val items = parser.parseList(fixture("search.html"), null)
        assertEquals(2, items.size)
        assertTrue(items[0].title.contains("鬼灭之刃"))
        assertTrue(items[0].tags.isEmpty())
        assertTrue(items[0].url.startsWith("/donghua/"))
    }

    @Test
    fun `parse detail description`() {
        // 简介整段在 `.hl-content-text > em` 里，删掉 em 会得到空串
        val description = parser.parseDescription(fixture("detail.html")).orEmpty()
        assertTrue(description.length > 20, description)
        assertTrue(description.contains("Dark Machine"), description.take(80))
    }

    @Test
    fun `parse detail info block`() {
        val info = parser.parseInfoBlock(fixture("detail.html"))
        assertEquals(ContentState.ONGOING, info.state)
        assertEquals(listOf("科幻", "机战"), info.tags.map { it.title })
        assertEquals(listOf("科幻", "机战"), info.tags.map { it.key })
        assertEquals(setOf("小野贤章", "泽城美雪", "内山昂辉", "杉山里穗", "会泽纱弥"), info.authors)
    }

    @Test
    fun `parse detail without status reports null`() {
        val doc = Jsoup.parse(
            """
            <div class="hl-detail-content"><div class="hl-vod-data"><ul>
            <li><em class="hl-text-muted">地区：</em><span>日本</span></li>
            </ul></div></div>
            """.trimIndent(),
            BASE_URI,
        )
        val info = parser.parseInfoBlock(doc)
        assertNull(info.state)
        assertTrue(info.tags.isEmpty())
        assertTrue(info.authors.isEmpty())
    }

    @Test
    fun `parse chapter list`() {
        val chapters = parser.parseChapters(fixture("detail.html"))
        assertEquals(listOf("第01集", "第02集", "第03集", "第04集"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f, 4f), chapters.map { it.number })
        assertEquals("/player/17225-1-1/", chapters.first().url)
        assertEquals(chapters.map { it.url }.distinct(), chapters.map { it.url })
    }

    @Test
    fun `parse video url from player config`() {
        val url = parser.parseVideoUrl(html("player.html"), "https://www.dm456.com/player/17225-1-1/")
        assertEquals("https://v.lzcdn31.com/20261014/12670_f83a3012/index.m3u8", url)
    }

    @Test
    fun `parse base64 encoded video url`() {
        val url = parser.parseVideoUrl(html("player_encrypted.html"), "https://www.dm456.com/player/17225-1-2/")
        assertEquals("https://v.example.com/20261014/1_fixture/index.m3u8", url)
    }

    @Test
    fun `player page without config is rejected`() {
        val error = assertThrows(ParseException::class.java) {
            parser.parseVideoUrl(html("error.html"), "https://www.dm456.com/player/1-1-1/")
        }
        assertTrue(error.message.orEmpty().contains("player_aaaa"))
    }

    @Test
    fun `card without detail link is skipped`() {
        val doc = Jsoup.parse(
            """<ul><li class="hl-list-item"><div class="hl-item-text">
            <div class="hl-item-title"><a href="/topic/1/">专题</a></div></div></li></ul>""",
            BASE_URI,
        )
        assertTrue(parser.parseList(doc, null).isEmpty())
    }

    @Test
    fun `content keeps unknown rating semantics`() {
        val doc = Jsoup.parse(
            """
            <ul><li class="hl-list-item">
            <a class="hl-item-thumb" href="/donghua/42/" title="无评分作品" data-original="/upload/a.jpg"></a>
            <div class="hl-item-text"><div class="hl-item-title"><a href="/donghua/42/">无评分作品</a></div>
            <div class="hl-item-sub">某人&nbsp;</div></div>
            </li></ul>
            """.trimIndent(),
            BASE_URI,
        )
        val item: Content = parser.parseList(doc, null).single()
        assertEquals(RATING_UNKNOWN, item.rating)
        assertNull(item.state)
        assertEquals("https://www.dm456.com/upload/a.jpg", item.coverUrl)
    }

    private companion object {
        const val BASE_URI = "https://www.dm456.com/"
    }
}
