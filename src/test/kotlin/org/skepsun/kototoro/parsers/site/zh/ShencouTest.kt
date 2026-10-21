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
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * 神凑轻小说离线夹具测试：
 * 站点迁移到 wowenku.com 后书籍页为 /books/read_{id}.html，旧的 /article/{id}.html 会返回
 * 200 + 「404错误，页面不存在，或文章已删除」提示页，解析器必须识别而不是把警告当简介/正文。
 */
class ShencouTest {

    private val parser = Shencou(ContentLoaderContextMock)

    private fun fixture(name: String, baseUri: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/shencou/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        baseUri,
    )

    private fun content(url: String = "/books/read_695.html") = Content(
        id = 695L,
        title = "测试作品",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://www.wowenku.com$url",
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
    fun `details take description between intro and notice, not the page chrome`() {
        val result = parser.parseDetails(
            fixture("detail.html", "https://www.wowenku.com/books/read_695.html"),
            content(),
        )

        val description = result.description ?: error("简介为空")
        assertTrue(description.startsWith("真正的实力、平等究竟为何？"), "简介应从内容简介标记开始: $description")
        assertTrue(description.contains("实力至上主义学校"))
        assertFalse(description.contains("本书公告"), "公告不应混入简介: $description")
        assertFalse(description.contains("关键字"), "关键字不应混入简介: $description")
        assertEquals(setOf("测试作者"), result.authors)
        assertEquals(ContentState.ONGOING, result.state)
        assertEquals("https://www.wowenku.com/files/article/image/0/695/695s.jpg", result.coverUrl)
    }

    @Test
    fun `site 404 page is recognised and contributes no description`() {
        val doc = fixture("error404.html", "https://www.wowenku.com/article/695.html")

        assertTrue(parser.isSiteErrorPage(doc), "站点 404 提示页必须被识别")
        val result = parser.parseDetails(doc, content("/article/695.html"))
        assertNull(result.description, "404 提示页不能污染简介")
    }

    @Test
    fun `chapter index resolves volumes and relative chapter urls`() {
        val chapters = parser.parseChapters(
            fixture("chapter_index.html", "https://www.wowenku.com/read/0/695/index.html"),
        )

        assertEquals(3, chapters.size)
        assertEquals(listOf(1, 1, 2), chapters.map { it.volume })
        assertEquals(
            listOf("/read/0/695/145879.html", "/read/0/695/145880.html", "/read/0/695/145898.html"),
            chapters.map { it.url },
        )
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertEquals("KEYWORDS", chapters.first().title)
    }

    @Test
    fun `chapter body comes from the go over markers only`() {
        val chapter = ContentChapter(
            id = 1L,
            title = "测试短篇",
            number = 1f,
            volume = 1,
            url = "/read/0/695/242565.html",
            scanlator = null,
            uploadDate = 0,
            branch = null,
            source = parser.source,
        )
        val html = parser.parseChapterHtml(
            fixture("chapter.html", "https://www.wowenku.com/read/0/695/242565.html"),
            chapter,
        ) ?: error("正文为空")

        assertTrue(html.contains("第一段正文内容"))
        assertTrue(html.contains("第三段正文内容"))
        assertFalse(html.contains("上一章"), "导航不应混入正文")
        assertFalse(html.contains("BookSee_Ads"), "广告占位不应混入正文")
    }

    @Test
    fun `user agent is configurable and defaults to a browser ua`() {
        val keys = mutableListOf<ConfigKey<*>>()
        parser.onCreateConfig(keys)
        val uaKey = keys.filterIsInstance<ConfigKey.UserAgent>().singleOrNull()
        assertNotNull(uaKey, "神凑应暴露 UA 设置项: ${keys.map { it.key }}")
        assertTrue(uaKey!!.defaultValue.contains("Edg/"))

        val headers = parser.getRequestHeaders()
        assertEquals(uaKey.defaultValue, headers["User-Agent"])
        assertTrue(headers["Sec-CH-UA"]!!.contains("v=\"143\""), "客户端提示版本应与 UA 一致")
        assertEquals("https://www.wowenku.com/", headers["Referer"])
    }
}
