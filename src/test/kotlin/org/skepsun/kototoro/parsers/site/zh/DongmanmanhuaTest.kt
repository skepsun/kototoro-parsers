package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import java.time.LocalDate
import java.time.ZoneId

/**
 * 咚漫中文离线夹具测试：
 * - 分类页一次给出全部作品、按 12 个题材分段，同一部作品会在题材段和「完结」段各出现一次，必须按作品归并；
 * - 卡片封面带 `x-oss-process` 缩放参数，解析时要还原成原图；
 * - 详情页作者在 `span.author` 里还嵌着「作家信息」链接，只能用 ownText；
 * - 目录 `#_listUl` 新话在前，翻页靠 `page` 参数，合并后需翻序并补序号；
 * - 阅读页正文图只在 `#_imageList` 的 `img._images[data-url]`，同页 300 多张 `_thumbnailImages` 是缩略图，不能混进来。
 */
class DongmanmanhuaTest {

    private val parser = Dongmanmanhua(ContentLoaderContextMock)

    private fun fixture(name: String, baseUri: String = BASE_URI): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/dongmanmanhua/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        baseUri,
    )

    private fun content(url: String = "/LOVE/dibaodebaizhou/list?title_no=2892") = Content(
        id = 2892L,
        title = "列表标题",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://www.dongmanmanhua.cn$url",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = parser.source,
    )

    @Test
    fun `sort orders match site capability`() {
        assertEquals(setOf(SortOrder.UPDATED, SortOrder.POPULARITY), parser.availableSortOrders)
    }

    @Test
    fun `filter options list all genres`() = runBlocking {
        val options = parser.getFilterOptions()
        assertTrue(options.availableTags.any { it.key == "ALL" })
        assertEquals(12, options.availableTags.count { it.key != "ALL" })
        assertEquals(listOf("恋爱", "少年", "古风", "奇幻", "搞笑", "校园", "都市", "治愈", "悬疑", "励志", "影视化", "完结"),
            options.tagGroups.single().tags.map { it.title })
    }

    @Test
    fun `parse ranking page cards`() {
        val items = parser.parseRankingPage(fixture("ranking.html"))
        assertEquals(5, items.size)
        assertEquals("地堡的白昼", items[0].title)
        assertEquals("不过是黎明时分", items[1].title)
        assertEquals("甜蜜婚礼", items[2].title)
        // 卡片链接是协议相对的站点内路径，不能把域名写进 id 依据
        assertEquals("/LOVE/dibaodebaizhou/list?title_no=2892", items[0].url)
        assertFalse(items[0].coverUrl.orEmpty().contains("x-oss-process"))
        assertTrue(items[0].coverUrl.orEmpty().startsWith("https://cdn.dongmanmanhua.cn/"))
        assertEquals(setOf("Lee Myeon Ji"), items[0].authors)
        // 段落即题材
        assertEquals(listOf("恋爱"), items[0].tags.map { it.title })
        assertEquals(listOf("搞笑"), items[3].tags.map { it.title })
        // 题材段里的作品默认不判定完结
        assertNull(items[0].state)
    }

    @Test
    fun `merge duplicated titles and mark finished`() {
        val items = parser.parseRankingPage(fixture("ranking_finished.html"))
        // 同一部作品既在恋爱段又在完结段，只保留一条并合并两个题材
        assertEquals(2, items.size)
        val merged = items.single { it.url.contains("1200") || it.tags.size == 2 }
        assertEquals(listOf("恋爱", "完结"), merged.tags.map { it.title })
        assertEquals(ContentState.FINISHED, merged.state)
        // 只出现在完结段的作品也算已完结
        assertEquals(ContentState.FINISHED, items.first { it !== merged }.state)
    }

    @Test
    fun `slice page uses fixed page size`() {
        val all = (1..65).map { content("/LOVE/book$it/list?title_no=$it") }
        assertEquals(30, parser.slicePage(all, 1).size)
        assertEquals("/LOVE/book31/list?title_no=31", parser.slicePage(all, 2).first().url)
        assertEquals(5, parser.slicePage(all, 3).size)
        assertTrue(parser.slicePage(all, 4).isEmpty())
        assertTrue(parser.slicePage(all, 0).isEmpty())
    }

    @Test
    fun `parse search page`() {
        val items = parser.parseSearchPage(fixture("search.html"))
        assertEquals(2, items.size)
        assertEquals("恋爱星群", items[0].title.trim())
        assertTrue(items[0].tags.isEmpty())
        assertTrue(items[0].url.startsWith("/"))
    }

    @Test
    fun `parse details header`() {
        val items = parser.parseRankingPage(fixture("ranking.html"))
        val details = parser.parseDetails(fixture("detail.html"), items[0].copy(tags = items[0].tags))
        assertEquals("咚咚社", details.title)
        // span.author 里嵌着「作家信息」链接，只能取 ownText
        assertEquals(setOf("咚咚妹"), details.authors)
        assertTrue(details.coverUrl.orEmpty().startsWith("https://cdn.dongmanmanhua.cn/6a1ce62e"))
        assertTrue(details.description.orEmpty().contains("代理社长"))
        // h2.genre 是「活动专区」，不是题材，不能污染列表里已有的题材
        assertEquals(items[0].tags, details.tags)
    }

    @Test
    fun `details add genre tag from header`() {
        val doc = Jsoup.parse(
            """
            <html><body>
            <div class="detail_header type_white">
            <div class="info"><h2 class="genre g_love">恋爱</h2><h1 class="subj">测试作品</h1></div>
            </div>
            </body></html>
            """.trimIndent(),
            BASE_URI,
        )
        val details = parser.parseDetails(doc, content())
        assertEquals(listOf("恋爱"), details.tags.map { it.title })
        assertEquals(listOf("LOVE"), details.tags.map { it.key })
    }

    @Test
    fun `parse chapter list keeps site order and dates`() {
        val chapters = parser.parseChapterLinks(fixture("detail.html"))
        assertEquals(listOf("每周免费看丨第7期", "每周免费看丨第6期", "国庆追漫赢1999咚豆！"), chapters.map { it.title })
        assertTrue(chapters[0].url.contains("episode_no=154"))
        val expected = LocalDate.of(2026, 10, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, chapters[0].uploadDate)
        assertEquals(0f, chapters[0].number)
    }

    @Test
    fun `chapters are reversed and numbered ascending`() {
        val chapters = listOf(
            chapter("https://www.dongmanmanhua.cn/LOVE/a/c3/viewer?title_no=1&episode_no=3", "第3话"),
            chapter("https://www.dongmanmanhua.cn/LOVE/a/c2/viewer?title_no=1&episode_no=2", "第2话"),
            chapter("https://www.dongmanmanhua.cn/LOVE/a/c1/viewer?title_no=1&episode_no=1", "第1话"),
        )
        val ordered = parser.toChapters(chapters)
        assertEquals(listOf("第1话", "第2话", "第3话"), ordered.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), ordered.map { it.number })
    }

    @Test
    fun `viewer images ignore thumbnails and strip oss params`() {
        val images = parser.parseViewerImages(fixture("viewer.html"))
        assertEquals(3, images.size)
        images.forEach { url ->
            assertFalse(url.contains("x-oss-process"), url)
            assertFalse(url.contains("?"), url)
            assertTrue(url.startsWith("https://cdn.dongmanmanhua.cn/"), url)
        }
        assertEquals(images.distinct(), images)
    }

    @Test
    fun `locked viewer has no images`() {
        assertTrue(parser.parseViewerImages(fixture("viewer_locked.html")).isEmpty())
    }

    @Test
    fun `details page detection`() {
        assertTrue(parser.isDetailsPage(fixture("detail.html")))
        // 不存在的 title_no 会被站点回落到分类页
        assertFalse(parser.isDetailsPage(fixture("notfound.html")))
    }

    private fun chapter(url: String, title: String) = ContentChapter(
        id = url.hashCode().toLong(),
        title = title,
        number = 0f,
        volume = 0,
        url = url,
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = parser.source,
    )

    private companion object {
        const val BASE_URI = "https://www.dongmanmanhua.cn/"
    }
}
