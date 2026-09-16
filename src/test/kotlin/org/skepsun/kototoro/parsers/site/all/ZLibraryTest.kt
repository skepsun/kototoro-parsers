package org.skepsun.kototoro.parsers.site.all

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
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class ZLibraryTest {

	private val parser = ZLibrary(ContentLoaderContextMock)

	@Test
	fun `parse book list fixture`() {
		val result = parser.parseBookList(fixture("search.html"))

		assertEquals(3, result.size)
		val first = result[0]
		assertEquals("Lost Souls Take Dog City", first.title)
		assertEquals("/book/25126620/78b103/lost-souls-take-dog-city.html", first.url)
		assertEquals("https://z-library.sk/book/25126620/78b103/lost-souls-take-dog-city.html", first.publicUrl)
		assertEquals(setOf("Ellie R. Hunter"), first.authors)
		assertEquals("https://s3proxy.cdn-zlib.example/covers100/collections/userbooks/eb03f5ea.jpg", first.coverUrl)
		assertEquals(0.88f, first.rating, 0.001f)
		assertTrue(first.description!!.contains("Format: epub"))

		// rating="0.0" 视为未知
		assertEquals(RATING_UNKNOWN, result[1].rating)
		assertTrue(result[1].description!!.contains("Publisher: Oxford University Press"))

		// 占位封面过滤；作者字段中的推广/评论噪音被清洗；"5/5" 归一化为 1.0
		assertNull(result[2].coverUrl)
		assertEquals(1f, result[2].rating, 0.001f)
		assertFalse(result[2].authors.any { it.contains("comments", ignoreCase = true) })
		assertFalse(result[2].authors.any { it.contains("Barnes", ignoreCase = true) })
		assertFalse(result[2].authors.any { it.contains("@") })
		assertTrue(result[2].authors.any { it.contains("Wittgenstein") })
	}

	@Test
	fun `parse details fixture`() {
		val content = content()
		val result = parser.parseDetails(fixture("book.html"), content)

		assertEquals("Henry Ford - The People's Carmaker (What's Their Story)", result.title)
		assertEquals(setOf("Haydn Middleton"), result.authors)
		assertTrue(result.description!!.contains("Publisher: Oxford University Press"))
		assertTrue(result.description!!.contains("Year: 1998"))
		assertEquals(setOf("Biography & Autobiography", "Historical"), result.tags.map { it.title }.toSet())
		val chapter = result.chapters!!.single()
		assertEquals("/dl/1000073/ebe77d?extension=epub&signature=d3mo5i78c#ext=epub", chapter.url)
		assertEquals("Download (EPUB)", chapter.title)
	}

	@Test
	fun `build list urls`() {
		assertEquals(
			"https://z-library.sk/s/?selected_content_types%5B%5D=book",
			parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter()),
		)
		val search = ContentListFilter(query = "Kant Kritik")
		val url = parser.buildListUrl(2, SortOrder.POPULARITY, search)
		assertTrue(url.startsWith("https://z-library.sk/s/Kant+Kritik?"))
		assertTrue(url.contains("order=popular"))
		assertTrue(url.endsWith("page=2"))

		val math = ContentTag("Mathematics", "cat:23", parser.source)
		val pdf = ContentTag("PDF", "fmt:PDF", parser.source)
		val zh = ContentTag("中文", "lang:chinese", parser.source)
		val catUrl = parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter(tags = setOf(math, pdf, zh)))
		assertTrue(catUrl.startsWith("https://z-library.sk/category/23/s/?"))
		assertTrue(catUrl.contains("extensions%5B%5D=PDF"))
		assertTrue(catUrl.contains("languages%5B%5D=chinese"))
		assertTrue(catUrl.contains("selected_content_types%5B%5D=book"))
	}

	@Test
	fun `parse rating`() {
		assertEquals(0.88f, parser.parseRating("4.4"), 0.001f)
		assertEquals(1f, parser.parseRating("4.5/4.5"), 0.001f)
		assertEquals(RATING_UNKNOWN, parser.parseRating("0.0"))
		assertEquals(RATING_UNKNOWN, parser.parseRating(""))
		assertEquals(RATING_UNKNOWN, parser.parseRating(null))
		assertEquals(RATING_UNKNOWN, parser.parseRating("bad"))
	}

	private fun fixture(name: String): Document = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/zlibrary/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		"https://z-library.sk/",
	)

	private fun content() = Content(
		id = 1L,
		title = "Henry Ford - The People's Carmaker",
		altTitles = emptySet(),
		url = "/book/1000073/6755dd/henry-ford.html",
		publicUrl = "https://z-library.sk/book/1000073/6755dd/henry-ford.html",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = parser.source,
	)
}
