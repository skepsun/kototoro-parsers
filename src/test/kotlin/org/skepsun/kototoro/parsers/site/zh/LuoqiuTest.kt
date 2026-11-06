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
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 落秋中文离线夹具测试：
 * - 列表中分类/作者/最新章节是同一行里的 `span.s1..s5`，不能把导航或推荐阅读的链接当条目；
 * - 详情页信息在 `og:*` / `og:novel:*` meta 里，`#intro` 后面跟着站点推荐语；
 * - 目录页 `#list dd` 只给前 99 章，后续目录页的页码来自 `<select>`；
 * - 正文是 `qsbs.bb()` 里的 base64 段落，另有纯明文和「正在手打中」占位页两种形态。
 */
class LuoqiuTest {

    private val parser = Luoqiu(ContentLoaderContextMock)

    private fun fixture(name: String, baseUri: String = BASE_URI): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/luoqiu/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        baseUri,
    )

    private fun content(url: String = "/book/100001/") = Content(
        id = 100001L,
        title = "落秋列表标题",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://www.luoqiuzw.com$url",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        description = null,
        chapters = null,
        source = parser.source,
    )

    @Test
    fun `list page reads category author and derives cover`() {
        val list = parser.parseListPage(fixture("list.html"))

        assertEquals(3, list.size, "导航与推荐链接都不应成为条目: ${list.map { it.title }}")
        val first = list[0]
        assertEquals("测试玄幻", first.title)
        assertEquals("/book/100001/", first.url)
        assertEquals("https://www.luoqiuzw.com/book/100001/", first.publicUrl)
        assertEquals("https://www.luoqiuzw.com/img/100001.jpg", first.coverUrl)
        assertEquals(setOf("测试作者甲"), first.authors)
        assertEquals(listOf("玄幻小说"), first.tags.map { it.title })
        assertEquals(listOf("测试玄幻", "测试都市", "测试无名"), list.map { it.title })
    }

    @Test
    fun `items without category or author stay usable`() {
        val third = parser.parseListPage(fixture("list.html")).last()

        assertTrue(third.tags.isEmpty(), "空分类 [ ] 不应造出标签: ${third.tags}")
        assertTrue(third.authors.isEmpty(), "空作者列不应造出作者")
    }

    @Test
    fun `details come from og meta instead of page chrome`() {
        val result = parser.parseDetails(fixture("detail.html"), content())

        assertEquals("测试作品", result.title)
        assertEquals("这是一段用来测试的简介，站点把它放在 og:description 里。", result.description)
        assertEquals(setOf("测试作者甲"), result.authors)
        assertEquals("https://www.luoqiuzw.com/img/100001.jpg", result.coverUrl)
        assertEquals(ContentState.FINISHED, result.state)
        assertEquals(listOf("玄幻小说"), result.tags.map { it.title })
    }

    @Test
    fun `seo blurb loses its site ad sentence`() {
        val result = parser.parseDetails(fixture("detail_generic.html"), content("/book/100004/"))

        val description = checkNotNull(result.description) { "简介为空" }
        assertTrue(description.contains("测试SEO作品是由作者：测试作者丁所著"), "作品介绍被误删: $description")
        assertFalse(description.contains("记住本站"), "站点广告句应被去掉: $description")
        assertFalse(description.contains("网址"), "广告里的网址应被去掉: $description")
        assertEquals(ContentState.ONGOING, result.state)
    }

    @Test
    fun `chapter index ignores recommendation links`() {
        val chapters = parser.parseChapterLinks(fixture("detail.html"))

        assertEquals(
            listOf("/book/100001/200001.html", "/book/100001/200002.html", "/book/100001/200009.html"),
            chapters.map { it.url },
        )
        assertEquals(listOf("第1章 重生", "第2章 试炼", "完本感言"), chapters.map { it.title })
    }

    @Test
    fun `full chapter index reads page count from select`() {
        val doc = fixture("chapters.html", "$BASE_URI/chapters/100001/1.html")

        assertEquals(3, parser.parseChapterPageCount(doc, "100001"))
        assertEquals(
            listOf("/book/100001/200100.html", "/book/100001/200101.html"),
            parser.parseChapterLinks(doc).map { it.url },
        )
    }

    @Test
    fun `base64 chapter paragraphs are decoded and cleaned`() {
        val html = checkNotNull(parser.extractChapterPart(fixture("chapter.html"), "第1章 重生")) {
            "base64 段落解析失败"
        }

        assertTrue(html.contains("蓉城，第一医院，重症监护室。"), "正文丢失: $html")
        assertTrue(html.contains("<p>测试正文第三段。</p>"), "多段正文未拼接: $html")
        assertFalse(html.contains("（第3页）"), "上游残留的分页标记应被去掉: $html")
        assertFalse(html.contains("第1章重生"), "与渲染标题重复的前缀应被去掉: $html")
        assertFalse(html.contains("xsbiquge"), "站点插入的域名水印应被去掉: $html")
        assertTrue(html.contains("测试正文第二段，含广告标记。"), "去水印后正文应完整: $html")
    }

    @Test
    fun `plain text chapter is kept when there are no base64 blocks`() {
        val html = checkNotNull(parser.extractChapterPart(fixture("chapter_plain.html"), "完本感言")) {
            "明文正文解析失败"
        }

        assertTrue(html.contains("感谢所有正版订阅和打赏本书的书友"), "明文正文丢失: $html")
        assertTrue(html.contains("写下全书完三个字的时候"), "明文正文只解析出一段: $html")
        assertFalse(html.contains("xsbiquge"), "明文正文里的水印也应被去掉: $html")
    }

    @Test
    fun `placeholder chapter is reported as empty instead of being shown as text`() {
        assertNull(
            parser.extractChapterPart(fixture("chapter_placeholder.html"), "第3196章 被抢了"),
            "「本章节内容正在手打中」不能当成正文",
        )
    }

    @Test
    fun `missing book response is not a details page`() {
        assertFalse(parser.isBookPage(fixture("error.html")), "「索引文件不存在！」不是详情页")
        assertTrue(parser.isBookPage(fixture("detail.html")))
    }

    @Test
    fun `filters expose categories while search stays unsupported`() = runBlocking {
        val options = parser.getFilterOptions()

        assertEquals(8, options.availableTags.size, "全部 + 7 个分类: ${options.availableTags.map { it.title }}")
        assertEquals(listOf("分类"), options.tagGroups.map { it.title })
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertFalse(parser.filterCapabilities.isSearchSupported, "站点搜索页不可用，不能声明搜索支持")
        assertTrue(
            parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "测试")).isEmpty(),
            "声明不支持搜索后不应再发无效请求",
        )
    }

    private companion object {
        const val BASE_URI = "https://www.luoqiuzw.com/book/100001/"
    }
}
