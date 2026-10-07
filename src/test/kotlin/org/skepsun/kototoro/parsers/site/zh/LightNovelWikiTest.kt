package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.util.Base64

class LightNovelWikiTest {
    @Test
    fun `chapter data url survives page resolution`() = runBlocking {
        val parser = LightNovelWiki(OfflineContentLoaderContext(mapOf(
            "https://lnovel.org/books/1/chapters/1" to "lightnovelwiki/chapter.html",
        )))
        val chapter = ContentChapter(
            id = 1L, title = "第1章", number = 1f, volume = 0, url = "/books/1/chapters/1",
            scanlator = null, uploadDate = 0, branch = null, source = parser.source,
        )
        val page = parser.getPages(chapter).single()
        assertTrue(page.url.startsWith("data:text/html;"))
        assertEquals(page.url, parser.getPageUrl(page))
        val html = String(Base64.getDecoder().decode(page.url.substringAfter("base64,")), Charsets.UTF_8)
        val doc = Jsoup.parse(html)
        assertEquals(listOf("第一段正文。", "第二段正文。"), doc.select("p").eachText())
        assertTrue(doc.select("script").isEmpty())
        assertFalse(doc.text().contains("站点导航"))
    }
}
