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

// 樱花动漫（Next.js 站群）离线夹具测试：
// - 卡片标题只在 img[alt]，封面藏在 srcSet 的 /_next/image?url=%2Fmedia%2F... 里；
// - 详情正文在 ld+json 的 TVSeries 里，分集是 ol.ws-epgrid > a.ws-ep[title][href]；
// - 播放页没有 iframe/播放器脚本，m3u8 直链直接内联在 HTML 里。
class YhdmTest {

    private val parser = Yhdm(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/yhdm/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.dongman.hk/",
    )

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/yhdm/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name")

    @Test
    fun `capabilities advertise search with the only real sort order`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertEquals(24, parser.pageSize)
    }

    @Test
    fun `filter options expose the four channels`() = kotlinx.coroutines.runBlocking {
        val tags = parser.getFilterOptions().tagGroups.single().tags
        assertEquals(4, tags.size)
        assertEquals(setOf("dongman", "fanju", "guochuang", "dianying"), tags.map { it.key }.toSet())
        assertEquals("动画电影", tags.first { it.key == "dianying" }.title)
    }

    @Test
    fun `list page reads title from alt and cover from next image srcset`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("伤痕累累的圣女的复仇 第二季", first.title)
        assertEquals("/movie/5164875", first.url)
        assertEquals("https://www.dongman.hk/media/dianying88/2a/2aea7c8689d5b770206509020c55cc81d66d9d524cc493e4e2c0f1cea6b5515b.webp", first.coverUrl)
        assertEquals(ContentState.ONGOING, first.state)

        assertEquals("缔灵爱水心缠", items[1].title)
        assertEquals("/movie/25", items[1].url)
        assertTrue(items[1].coverUrl!!.startsWith("https://www.dongman.hk/media/"))
    }

    @Test
    fun `detail page reads ld json and every episode`() {
        val doc = fixture("detail.html")
        val ld = parser.parseLdJson(doc)
        assertEquals("伤痕累累的圣女的复仇 第二季", ld.getString("name"))
        assertTrue(ld.getString("image").startsWith("https://"))
        assertTrue(ld.getString("description").contains("黑化吧"))

        val chapters = parser.parseChapters(doc)
        assertEquals(6, chapters.size)
        assertEquals("第1集", chapters.first().title)
        assertEquals("/watch/5164875?s=0&e=0", chapters.first().url)
        assertEquals("第6集", chapters.last().title)
        assertEquals(1f, chapters.first().number)
        assertEquals(6f, chapters.last().number)
    }


    @Test
    fun `search page uses its own result cards`() {
        val items = parser.parseSearchPage(fixture("search.html"))
        assertEquals(2, items.size)
        assertEquals("进击！时光战队", items[0].title)
        assertEquals("/movie/72061", items[0].url)
        assertTrue(items[0].coverUrl!!.startsWith("https://www.dongman.hk/media/"))
        assertNotNull(items[0].description)
    }

    @Test
    fun `watch page yields inline m3u8`() {
        val pageUrl = "https://www.dongman.hk/watch/5164875?s=0&e=0"
        val videoUrl = parser.parseVideoUrl(fixtureText("watch.html"), pageUrl)
        assertEquals(
            "https://v.fengbao14.com/video/shanghenleileideshengnvdefuchoudierji/3536142214d4/index.m3u8",
            videoUrl,
        )
    }

    @Test
    fun `watch page without m3u8 is reported with site name`() {
        val error = runCatching {
            parser.parseVideoUrl("<html><body>没有播放地址</body></html>", "https://www.dongman.hk/watch/1")
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("樱花动漫"))
    }
}
