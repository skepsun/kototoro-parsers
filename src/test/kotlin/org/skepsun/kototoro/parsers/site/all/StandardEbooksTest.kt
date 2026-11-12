package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.EbookFormat
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class StandardEbooksTest {

	private val parser = StandardEbooks(ContentLoaderContextMock)

	@Test
	fun `parse list fixture`() {
		val items = parser.parseList(fixture("list.html"))

		assertEquals(2, items.size)
		val first = items[0]
		assertEquals("Sir Nigel", first.title)
		assertEquals("ebooks/arthur-conan-doyle/sir-nigel", first.url)
		assertEquals("https://standardebooks.org/ebooks/arthur-conan-doyle/sir-nigel", first.publicUrl)
		assertEquals(
			"https://standardebooks.org/images/covers/arthur-conan-doyle_sir-nigel/a5c06a007d62adcb8649910b33159e83377ccf62/cover@2x.jpg",
			first.coverUrl,
		)
		assertEquals(setOf("Arthur Conan Doyle"), first.authors)

		// 有译者的书是三段路径，不能被截断
		assertEquals("ebooks/thomas-mann/death-in-venice/kenneth-burke", items[1].url)
		assertEquals("Death in Venice", items[1].title)
		assertEquals(setOf("Thomas Mann"), items[1].authors)
	}

	@Test
	fun `parse empty list`() {
		val doc = Jsoup.parse(
			"<html><body><ol class=\"ebooks-list\"></ol><p>No ebooks matched your filters.</p></body></html>",
			"https://standardebooks.org/",
		)
		assertTrue(parser.parseList(doc).isEmpty())
	}

	@Test
	fun `parse details fixture`() {
		val result = parser.parseDetails(fixture("detail.html"), content())

		assertEquals("Sir Nigel", result.title)
		assertEquals(setOf("Arthur Conan Doyle"), result.authors)
		assertEquals(setOf("Fiction", "Adventure"), result.tags.map { it.title }.toSet())
		assertTrue(result.description!!.contains("Nigel Loring, the last offspring"))
		assertTrue(result.description!!.contains("Words: 130533"))
		assertTrue(result.description!!.contains("reading ease of 77.37"))
		assertTrue(result.description!!.contains("Series: Sir Nigel Loring"))
		// 列表没给封面时用详情页书影兜底
		assertEquals(
			"https://standardebooks.org/images/covers/arthur-conan-doyle_sir-nigel/a5c06a007d62adcb8649910b33159e83377ccf62/cover.jpg",
			result.coverUrl,
		)
	}

	@Test
	fun `parse downloads per format`() {
		val chapters = parser.parseDetails(fixture("detail.html"), content()).chapters!!

		assertEquals(listOf("EPUB", "AZW3", "KEPUB", "EPUB (Advanced)"), chapters.map { it.title })
		assertEquals(
			listOf(1f, 2f, 3f, 4f),
			chapters.map { it.number },
		)
		assertEquals(
			"https://standardebooks.org/ebooks/arthur-conan-doyle/sir-nigel/downloads/arthur-conan-doyle_sir-nigel.epub",
			chapters[0].url,
		)
		assertEquals(
			"https://standardebooks.org/ebooks/arthur-conan-doyle/sir-nigel/downloads/arthur-conan-doyle_sir-nigel_advanced.epub",
			chapters[3].url,
		)
		// 章节日期取该书最近一次发布
		assertTrue(chapters.all { it.uploadDate == 1794284174000L })
		// 在线阅读链接不是下载载体
		assertTrue(chapters.none { it.url.contains("/text") })
	}

	@Test
	fun `chapter page carries ebook format marker`() {
		val chapters = parser.parseDetails(fixture("detail.html"), content()).chapters!!
		val pages = chapters.map { runBlocking { parser.getPages(it) }.single() }

		assertEquals(
			listOf(EbookFormat.EPUB, EbookFormat.AZW3, EbookFormat.EPUB, EbookFormat.EPUB),
			pages.map { EbookFormat.fromMarker(it.preview) },
		)
		assertEquals(pages[0].url, runBlocking { parser.getPageUrl(pages[0]) })
	}

	@Test
	fun `details without article fails`() {
		val doc = Jsoup.parse("<html><body><main>404</main></body></html>", "https://standardebooks.org/")
		assertThrows(ParseException::class.java) {
			parser.parseDetails(doc, content())
		}
	}

	@Test
	fun `build list urls`() {
		assertEquals(
			"https://standardebooks.org/ebooks?page=1&per-page=24",
			parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter()),
		)
		assertEquals(
			"https://standardebooks.org/ebooks?page=2&per-page=24&query=sir+nigel&sort=popularity",
			parser.buildListUrl(2, SortOrder.POPULARITY, ContentListFilter(query = "sir nigel")),
		)
		assertTrue(parser.buildListUrl(1, SortOrder.NEWEST, ContentListFilter()).endsWith("&sort=default"))
		assertTrue(parser.buildListUrl(1, SortOrder.ALPHABETICAL, ContentListFilter()).endsWith("&sort=author-alpha"))

		val philosophy = ContentTag("哲学", "tag:philosophy", parser.source)
		val poetry = ContentTag("诗歌", "tag:poetry", parser.source)
		val url = parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter(tags = setOf(philosophy, poetry)))
		assertTrue(url.contains("&tags%5B%5D=philosophy"))
		assertTrue(url.contains("&tags%5B%5D=poetry"))
	}

	private fun fixture(name: String): Document = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/standardebooks/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		"https://standardebooks.org/ebooks/arthur-conan-doyle/sir-nigel",
	)

	private fun content() = Content(
		id = 1L,
		title = "Sir Nigel",
		altTitles = emptySet(),
		url = "ebooks/arthur-conan-doyle/sir-nigel",
		publicUrl = "https://standardebooks.org/ebooks/arthur-conan-doyle/sir-nigel",
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
