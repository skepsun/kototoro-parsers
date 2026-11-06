package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

// 电影88（Next.js App Router 聚合站）离线夹具测试：
// - 列表/搜索卡片是 article.mcard，标题 mcard__title、封面在 srcSet 的 /_next/image?url= 里；
// - 详情元数据在 ld+json 的 TVSeries；线路名在 section[h2]，集链接 /play/{id}?s=&e=；
// - 播放地址只在详情页 RSC flight 数据里（"source":{"key":"bfzym3u8",…}），播放页没有 m3u8；
// - mgtv/qiyi/qq/youku 是正版跳转，不能当免费线路。
class Dianying88Test {

    private val parser = Dianying88(ContentLoaderContextMock)

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/dianying88/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name")

    private fun fixture(name: String): Document = Jsoup.parse(fixtureText(name), "https://dianying88.net/")

    @Test
    fun `capabilities advertise search with the only real sort order`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertEquals(20, parser.pageSize)
    }

    @Test
    fun `filter options expose site channels`() = runBlocking {
        val options = parser.getFilterOptions()
        val titles = options.tagGroups.flatMap { it.tags }.map { it.title }
        assertTrue(titles.containsAll(listOf("电影", "电视剧", "动漫", "综艺", "B站", "最近更新", "高分推荐")))
        assertEquals(listOf("频道"), options.tagGroups.map { it.title })
    }

    @Test
    fun `list cards carry title cover year and state`() {
        val items = parser.parseCards(fixture("list_type20.html"))
        assertEquals(3, items.size)
        val first = items.first()
        assertEquals("玩具总动员5", first.title)
        assertEquals("/vod/78365", first.url)
        assertNotNull(first.coverUrl)
        // 封面是 /_next/image?url=%2Fmedia%2F... 解码后的站点地址
        assertTrue(first.coverUrl!!.startsWith("https://dianying88.net/media/"), first.coverUrl)
        assertTrue(first.coverUrl!!.endsWith(".webp"), first.coverUrl)
        // 年份不进标签，类型/地区进标签
        assertEquals(setOf("2026"), first.altTitles)
        assertTrue(first.tags.any { it.title == "电影" })
        assertFalse(first.tags.any { it.title == "2026" })
        assertNotNull(first.description)
        assertTrue(items.all { it.state == ContentState.FINISHED || it.state == ContentState.ONGOING })
    }

    @Test
    fun `search result cards use the same card markup`() {
        val items = parser.parseCards(fixture("search.html"))
        assertEquals(2, items.size)
        assertTrue(items.all { it.url.startsWith("/vod/") })
        assertTrue(items.all { it.title.isNotBlank() })
        // 搜索卡片没有年份时 altTitles 为空，不该塞进去空串
        assertTrue(items.all { item -> item.altTitles.none { it.isBlank() } })
    }

    @Test
    fun `rsc sources separate free hls lines from official redirects`() {
        val lines = parser.parseSources(fixtureText("detail_free.html"))
        assertEquals(listOf("qiyi", "mgtv", "bfzym3u8"), lines.map { it.key })
        assertEquals(listOf(false, false, true), lines.map { it.isFree })
        assertEquals(listOf("爱奇艺", "芒果TV", "暴风线路"), lines.map { it.label })
        val free = lines.last()
        assertEquals(3, free.episodes.size)
        assertTrue(free.episodes.all { it.url.endsWith(".m3u8") })
        assertTrue(free.episodes.all { it.label.isNotBlank() })
        // 正版线路的分集是平台页面而不是视频流
        assertTrue(lines.first().episodes.all { it.url.startsWith("https://www.iqiyi.com/") })
    }

    @Test
    fun `line indexes come from the ssr episode links`() {
        val indexes = parser.parseLineIndexes(fixture("detail_free.html"))
        assertEquals(mapOf("爱奇艺" to 0, "芒果TV" to 1, "暴风线路" to 2), indexes)
        assertEquals(3, indexes.size)
    }

    @Test
    fun `chapters only contain the free line and point at play urls`() {
        val doc = fixture("detail_free.html")
        val chapters = parser.parseChapters(doc, "https://dianying88.net/vod/78390")
        assertEquals(3, chapters.size)
        assertTrue(chapters.all { it.branch == "暴风线路" })
        assertTrue(chapters.all { it.scanlator == "暴风线路" })
        // s=2 是暴风线路（正版线路被排除），e 按集递增
        assertEquals(
            listOf("/play/78390?s=2&e=0", "/play/78390?s=2&e=1", "/play/78390?s=2&e=2"),
            chapters.map { it.url },
        )
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertEquals(chapters.size, chapters.map { it.id }.toSet().size)
    }

    @Test
    fun `official only title gets a single explanatory chapter`() {
        val doc = fixture("detail_official.html")
        val chapters = parser.parseChapters(doc, "https://dianying88.net/vod/77555")
        assertEquals(1, chapters.size)
        val chapter = chapters.single()
        assertTrue(chapter.title.orEmpty().startsWith("仅正版跳转："), chapter.title)
        assertTrue(chapter.title.orEmpty().contains("芒果TV"))
        // 章节 url 指向正版平台，播放时由 getPages 报错
        assertTrue(chapter.url.startsWith("http"), chapter.url)
    }

    @Test
    fun `ld json provides title description genres and directors`() {
        val ld = parser.parseLdJson(fixture("detail_free.html"))
        assertEquals("贴身保镖", ld.getString("name"))
        assertTrue(ld.getString("description").orEmpty().isNotBlank())
        assertTrue(ld.getString("image").orEmpty().startsWith("https://"))
        assertTrue(ld.getJSONArray("genre").length() > 0)
    }

    @Test
    fun `official line is rejected with an actionable message`() = runBlocking {
        val chapter = ContentChapter(
            id = 1L,
            title = "仅正版跳转：芒果TV",
            number = 1f,
            volume = 0,
            url = "https://www.mgtv.com/b/908876/24675446.html",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = parser.source,
        )
        val error = assertThrows<ParseException> { runBlocking { parser.getPages(chapter) } }
        assertTrue(error.message.orEmpty().contains("正版平台跳转"), error.message)
    }

    @Test
    fun `unknown domain is never treated as a play url`() {
        // 反例：封面候选里没有 /media/ 或 http 开头时应返回 null，避免拼出畸形地址
        val img = Jsoup.parse("""<img src="/_next/image?url=%2Fbrand%2Flogo.png">""").selectFirst("img")
        assertNull(parser.parseCardCover(img, "dianying88.net"))
    }
}
