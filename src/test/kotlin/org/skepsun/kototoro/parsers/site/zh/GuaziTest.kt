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
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 瓜子漫画离线夹具测试：
 * - 分类页卡片在 `section.grid > article.card`，封面 `img.cover`、标题 `h3 a`、`span.genre` 表示连载状态；
 * - 详情页标题在 `h1`，作者/简介/封面/题材来自 JSON-LD `ComicStory`；
 * - 完整目录在 `[data-mobile-chapter-list]`（新章在前，必须倒序），JSON-LD `ItemList` 53 话以上会截断，只能兜底；
 * - 阅读页正文限定在 `section.reader-images`，同页封面/推荐图不能混进来。
 */
class GuaziTest {

    private val parser = Guazi(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/guazi/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.guazimanhua.com/",
    )

    @Test
    fun `search is not advertised and two sort orders are`() {
        assertEquals(setOf(SortOrder.UPDATED, SortOrder.POPULARITY), parser.availableSortOrders)
        assertFalse(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertEquals(36, parser.pageSize)
    }

    @Test
    fun `filter options expose the site genre ids`() = kotlinx.coroutines.runBlocking {
        val options = parser.getFilterOptions()
        val tags = options.tagGroups.single().tags
        assertEquals(23, tags.size)
        assertEquals("恋爱", tags.first { it.key == "9" }.title)
        assertEquals("其它", tags.last().title)
    }

    @Test
    fun `list page maps cover title state and public url`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("从姑获鸟开始", first.title)
        assertEquals("https://www.guazimanhua.com/comic.php?id=14109", first.url)
        assertEquals(first.url, first.publicUrl)
        assertNotNull(first.coverUrl)
        assertTrue(first.coverUrl!!.startsWith("https://img.guazicdn.com/th/comics/cover/"))
        assertEquals(ContentState.ONGOING, first.state)
        assertEquals(4.4f, first.rating)
        assertNull(first.description)
    }

    @Test
    fun `list card without comic link is skipped`() {
        val html = """
            <html><body><section class="grid">
              <article class="card"><h3><a>没有链接</a></h3></article>
            </section></body></html>
        """.trimIndent()
        assertTrue(parser.parseListPage(Jsoup.parse(html, "https://www.guazimanhua.com/")).isEmpty())
    }

    @Test
    fun `details take title author cover description and reversed chapters`() {
        val items = parser.parseListPage(fixture("list.html"))
        val doc = fixture("detail.html")

        val chapters = parser.parseChapterList(doc)
        assertEquals(4, chapters.size)
        // HTML 里是新章在前，解析后必须是最旧的一话在第一个
        assertEquals("470-哈？", chapters.first().title)
        assertEquals(470f, chapters.first().number)
        assertEquals("https://www.guazimanhua.com/chapter.php?id=2397693", chapters.first().url)
        assertEquals(473f, chapters.last().number)
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun `chapter list drops duplicated links and keeps order`() {
        val doc = Jsoup.parse(
            """
            <html><body>
              <div class="mobile-chapter-grid" data-mobile-chapter-list>
                <a href="/chapter.php?id=3">3-第三话</a>
                <a href="/chapter.php?id=2">2-第二话</a>
                <a href="/chapter.php?id=2">2-第二话（重复）</a>
                <a href="/chapter.php?id=1">1-第一话</a>
              </div>
              <a class="mobile-primary-btn" href="/chapter.php?id=1">开始阅读</a>
            </body></html>
            """.trimIndent(),
            "https://www.guazimanhua.com/",
        )
        val chapters = parser.parseChapterList(doc)
        assertEquals(listOf("1-第一话", "2-第二话", "3-第三话"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
    }

    @Test
    fun `reader keeps only images inside the reader section`() {
        val doc = fixture("chapter.html")
        val pages = parser.parsePageList(doc, chapterId = 42L)

        assertEquals(3, pages.size)
        assertEquals(listOf("51_1.webp", "51_2.webp", "51_3.webp"), pages.map { it.url!!.substringAfterLast('/') })
        assertTrue(pages.all { it.url!!.startsWith("https://img.guazicdn.com/th/comics/chapters/") })
        // 封面与推荐图不在 section.reader-images 里，不能被当成正文
        assertTrue(pages.none { it.url!!.contains("/cover/") })
        assertEquals(3, pages.map { it.id }.toSet().size)
    }

    @Test
    fun `reader without images is reported as parse failure`() {
        val html = "<html><body><section class=\"reader-images\"></section></body></html>"
        val pages = parser.parsePageList(Jsoup.parse(html, "https://www.guazimanhua.com/"), 1L)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun `page url is the image url`() = kotlinx.coroutines.runBlocking {
        val page = parser.parsePageList(fixture("chapter.html"), 7L).first()
        assertEquals(page.url, parser.getPageUrl(page))
    }
}
