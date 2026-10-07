package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Base64

class NovelHiTest {
    private fun context(contentFixture: String = "novelhi/content.json") = OfflineContentLoaderContext(mapOf(
        "https://novelhi.com/book/searchBookListWithShelfState?curr=1&limit=50&keyword=" to "novelhi/list.json",
        "https://novelhi.com/book/searchBookListWithShelfState?curr=2&limit=50&keyword=dragon%20%26%20magic"
            to "novelhi/list.json",
        "https://novelhi.com/novel/fantasy/test-novel" to "novelhi/detail.html",
        "https://novelhi.com/novel/fantasy/test-novel/chapters" to "novelhi/chapters.html",
        "https://novelhi.com/novel/fantasy/test-novel/1" to "novelhi/chapter.html",
        "https://novelhi.com/novel/fantasy/test-novel/1/content?token=fixture-token" to contentFixture,
    ))

    @Test
    fun `load novels chapters and lazy content without navigation or advertisements`() = runBlocking {
        val ctx = context()
        val parser = NovelHi(ctx)
        val content = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter.EMPTY).single()
        assertEquals("/novel/fantasy/test-novel", content.url)
        assertEquals("Test Novel", content.title)
        assertEquals(setOf("Test Author"), content.authors)
        assertEquals("https://images.example/cover.webp", content.coverUrl)
        val details = parser.getDetails(content)
        val chapters = requireNotNull(details.chapters)
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f), chapters.map { it.number })
        assertEquals("/novel/fantasy/test-novel/1", chapters.first().url)
        val page = parser.getPages(chapters.first()).single()
        assertEquals(page.url, parser.getPageUrl(page))
        val html = String(Base64.getDecoder().decode(page.url.substringAfter("base64,")), Charsets.UTF_8)
        val doc = Jsoup.parse(html)
        assertEquals(listOf("First paragraph & example.", "Second paragraph."), doc.select("p").eachText())
        assertFalse(doc.text().contains("Library navigation"))
        assertFalse(doc.text().contains("Advertisement"))
        assertFalse(doc.text().contains("Loading chapter"))
        assertTrue(doc.select("script, ins").isEmpty())
        assertEquals(1, doc.select(".novelhi-chapter-obf").size)
        assertTrue(doc.selectFirst("style")!!.html().contains("font-family: serif"))
        assertEquals("https://novelhi.com/novel/fantasy/test-novel/1", doc.selectFirst("base")?.attr("href"))
    }

    @Test
    fun `search preserves query and sends the requested page`() = runBlocking {
        val ctx = context()
        val parser = NovelHi(ctx)
        parser.getListPage(2, SortOrder.POPULARITY, ContentListFilter(query = "dragon & magic"))
        assertEquals(1, ctx.requests.size)
        assertTrue(ctx.requests.single().contains("curr=2&limit=50&keyword=dragon%20%26%20magic"))
    }

    @Test
    fun `plain chapter response remains readable without a custom font`() = runBlocking {
        val parser = NovelHi(context("novelhi/content-plain.json"))
        val content = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter.EMPTY).single()
        val chapter = requireNotNull(parser.getDetails(content).chapters).first()
        val page = parser.getPages(chapter).single()
        val html = String(Base64.getDecoder().decode(page.url.substringAfter("base64,")), Charsets.UTF_8)
        val doc = Jsoup.parse(html)
        assertEquals("Plain chapter text.", doc.select("p").text())
        assertTrue(doc.select("style").isEmpty())
    }

    @Test
    fun `empty content is reported instead of producing a blank page`() {
        val parser = NovelHi(context("novelhi/content-empty.json"))
        val error = assertThrows(ParseException::class.java) {
            runBlocking {
                val content = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter.EMPTY).single()
                val chapter = requireNotNull(parser.getDetails(content).chapters).first()
                parser.getPages(chapter)
            }
        }
        assertTrue(error.message.orEmpty().contains("empty chapter content"))
    }

    @Test
    fun `denied chapter content fails without attempting an unlock`() {
        val ctx = context("novelhi/content-denied.json")
        val parser = NovelHi(ctx)
        assertThrows(ParseException::class.java) {
            runBlocking {
                val content = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter.EMPTY).single()
                val chapter = requireNotNull(parser.getDetails(content).chapters).first()
                parser.getPages(chapter)
            }
        }
        assertEquals(5, ctx.requests.size)
        assertFalse(ctx.requests.any { it.contains("unlock", ignoreCase = true) })
    }
}
