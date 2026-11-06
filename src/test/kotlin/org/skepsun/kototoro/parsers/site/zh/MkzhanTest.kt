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
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

// 漫客栈离线夹具测试：
// - 分类页卡片在 div.cate-comic-list > div.common-comic-item，封面是懒加载的 img.lazy[data-src]；
// - 搜索结果页没有命中时站点返回「很遗憾…」+ 12 部推荐，推荐必须整页丢弃；
// - 详情页书名/作者/封面/状态/题材/简介/评分各有固定容器；
// - 目录条目 a.j-chapter-link 没有 href，地址只在 data-hreflink 上，且标题里混着图标字形；
// - 阅读页图片来自 chapter/content/v1/ 的 JSON，签名地址要改写成 oss 原图域名。
class MkzhanTest {

    private val parser = Mkzhan(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/mkzhan/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.mkzhan.com/",
    )

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/mkzhan/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name")

    @Test
    fun `search and two sort orders are advertised`() {
        assertEquals(setOf(SortOrder.POPULARITY, SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertEquals(30, parser.pageSize)
    }

    @Test
    fun `filter options expose the real theme ids`() = kotlinx.coroutines.runBlocking {
        val options = parser.getFilterOptions()
        val tags = options.tagGroups.single().tags
        assertEquals(23, tags.size)
        assertEquals("恋爱", tags.first { it.key == "3" }.title)
        assertEquals("真人", tags.last().title)
    }

    @Test
    fun `list page maps title url and lazy cover`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(3, items.size)

        val first = items[0]
        assertEquals("妖神记", first.title)
        // 作品 ID 与域名无关，列表里存相对 URL
        assertEquals("/207622/", first.url)
        assertEquals("https://www.mkzhan.com/207622/", first.publicUrl)
        // data-src 才是真封面，src 是占位图
        assertEquals(
            "https://oss.mkzcdn.com/comic/cover/20230106/63b78ffa50572-750x999.jpg!cover-400",
            first.coverUrl,
        )
        assertEquals(listOf("凤逆天下", "风起苍岚"), items.drop(1).map { it.title })
        assertTrue(items.all { it.coverUrl != null })
        assertTrue(items.all { it.rating < 0f })
    }

    @Test
    fun `list card without a real comic link is skipped`() {
        val html = """
            <html><body>
              <div class="cate-comic-list">
                <div class="common-comic-item">
                  <p class="comic__title"><a href="/212345/">正常作品</a></p>
                </div>
                <div class="common-comic-item">
                  <p class="comic__title"><a href="#">推荐占位</a></p>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val items = parser.parseListPage(Jsoup.parse(html, "https://www.mkzhan.com/"))
        assertEquals(listOf("正常作品"), items.map { it.title })
    }

    @Test
    fun `search page without hits is not treated as results`() {
        val html = """
            <html><body>
              <div class="search-page-wr" data-count="0">
                <div class="search-comic-list">
                  <div class="search_head">很遗憾，您搜索的内容暂时没有找到，我们为您推荐了以下漫画~</div>
                  <div class="common-comic-item">
                    <a class="cover" href="/217087/"><img class="lazy" data-src="https://oss.mkzcdn.com/a.jpg"></a>
                    <p class="comic__title"><a href="#">帝少的心尖宠</a></p>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        assertTrue(parser.parseSearchPage(Jsoup.parse(html, "https://www.mkzhan.com/")).isEmpty())
    }

    @Test
    fun `search page with hits keeps the real results`() {
        val html = """
            <html><body>
              <div class="search-page-wr" data-count="2">
                <div class="search-comic-list">
                  <div class="search_head">“妖神”搜索结果（2）</div>
                  <div class="common-comic-item">
                    <p class="comic__title"><a href="/207622/">妖神记</a></p>
                  </div>
                  <div class="common-comic-item">
                    <p class="comic__title"><a href="/215568/">废女妖神</a></p>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val items = parser.parseSearchPage(Jsoup.parse(html, "https://www.mkzhan.com/"))
        assertEquals(listOf("妖神记", "废女妖神"), items.map { it.title })
        assertEquals("/215568/", items[1].url)
    }

    @Test
    fun `details read every field from the site containers`() {
        val details = parser.parseDetails(fixture("detail.html"))
        assertNotNull(details)
        details!!

        assertEquals("妖神记", details.title)
        assertEquals("示例作者", details.author)
        assertEquals(ContentState.ONGOING, details.state)
        assertEquals(listOf("冒险", "热血", "玄幻"), details.genres)
        // 评分是百分制，归一化到 0..1
        assertEquals(0.88f, details.rating)
        assertTrue(details.description!!.startsWith("一部示例作品的简介"))
        assertFalse(details.description!!.contains("展开"))
        assertEquals(
            "https://oss.mkzcdn.com/comic/cover/20230106/63b78ffa50572-750x999.jpg!cover-400",
            details.coverUrl,
        )
    }

    @Test
    fun `details return null for a page that is not a comic`() {
        val doc = Jsoup.parse("<html><body><p>作品已下架</p></body></html>", "https://www.mkzhan.com/1/")
        assertNull(parser.parseDetails(doc))
    }

    @Test
    fun `chapter list is reversed and carries the data-hreflink urls`() {
        val chapters = parser.parseChapterList(fixture("detail.html"))
        assertEquals(5, chapters.size)

        // HTML 里是新章在前，解析后必须最旧的一话排第一
        assertEquals("第1话 重生", chapters.first().title)
        assertEquals("/207622/476064.html", chapters.first().url)
        assertEquals("第537话1 终于结束了", chapters.last().title)
        assertEquals("/207622/1070867.html", chapters.last().url)

        // 图标字形（U+E66E / VIP 角标）不能残留在标题里
        assertTrue(chapters.none { it.title!!.any { ch -> ch in '\uE000'..'\uF8FF' } })
        // 编号按目录位置生成，保证单调递增（站点话号本身不单调）
        assertEquals((1..5).map { it.toFloat() }, chapters.map { it.number })
        assertEquals(5, chapters.map { it.id }.toSet().size)
    }

    @Test
    fun `chapter list drops duplicates and accepts plain hrefs`() {
        val html = """
            <html><body>
              <div class="chapter__list">
                <a class="j-chapter-link" data-hreflink="/207622/30.html">第30话</a>
                <a class="j-chapter-link" data-hreflink="/207622/20.html">第20话</a>
                <a class="j-chapter-link" data-hreflink="/207622/20.html">第20话（重复）</a>
                <a class="j-chapter-link" href="/207622/10.html">第10话</a>
                <a class="j-chapter-link" href="/about/">不是章节</a>
              </div>
            </body></html>
        """.trimIndent()
        val chapters = parser.parseChapterList(Jsoup.parse(html, "https://www.mkzhan.com/"))
        assertEquals(listOf("第10话", "第20话", "第30话"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
    }

    @Test
    fun `reader json produces absolute image urls without the expiring signature`() {
        val pages = parser.parseChapterPages(fixtureText("reader.json"))
        assertEquals(4, pages.size)
        assertTrue(pages.all { it.startsWith("https://oss.mkzcdn.com/image/") })
        assertTrue(pages.none { it.contains("auth_key") })
        assertTrue(pages.none { it.contains("!page-800") })
        assertTrue(pages.first().endsWith("63da05fa1e740-800x1330.jpg"))
    }

    @Test
    fun `reader json without pages means the chapter is paywalled`() {
        // 真实响应：VIP 章节不登录时 code=302 / message=用户未登录
        val paywalled = """{"code":"302","message":"用户未登录","data":{"page":[]}}"""
        assertTrue(parser.parseChapterPages(paywalled).isEmpty())
        assertEquals("code=302, message=用户未登录", parser.parseChapterFailure(paywalled))
        val empty = """{"code":"200","message":"章节内容","data":{"buy_status":null,"page":[]}}"""
        assertTrue(parser.parseChapterPages(empty).isEmpty())
        assertTrue(parser.parseChapterPages("<html>oops</html>").isEmpty())
        assertEquals("响应不是合法 JSON", parser.parseChapterFailure("<html>oops</html>"))
    }

    @Test
    fun `image url normalisation keeps the original cdn path`() {
        assertEquals(
            "https://oss.mkzcdn.com/image/2024/a.jpg",
            parser.normalizeImageUrl("http://content.mkzcdn.com/image/2024/a.jpg!page-800?auth_key=1-0-0-x"),
        )
        assertEquals(
            "https://oss.mkzcdn.com/image/2024/a.jpg",
            parser.normalizeImageUrl("//content.mkzcdn.com/image/2024/a.jpg"),
        )
        // 已经是原图域名就只补 https
        assertEquals(
            "https://oss.mkzcdn.com/image/2024/a.jpg",
            parser.normalizeImageUrl("http://oss.mkzcdn.com/image/2024/a.jpg"),
        )
    }

    @Test
    fun `page url is the image url`() = kotlinx.coroutines.runBlocking {
        val image = parser.parseChapterPages(fixtureText("reader.json")).first()
        val page = ContentPage(id = 1L, url = image, preview = null, source = ContentParserSource.MKZHAN)
        // 图片地址已经在 normalizeImageUrl 里补成绝对地址，getPageUrl 不再拼域名
        assertEquals(image, parser.getPageUrl(page))
    }
}
