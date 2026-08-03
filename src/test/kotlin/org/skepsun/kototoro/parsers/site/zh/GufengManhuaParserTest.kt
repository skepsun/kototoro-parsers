package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class GufengManhuaParserTest {

	private val parser = GufengManhuaParser(ContentLoaderContextMock)

	@Test
	fun `expose browse filters but keep broken search disabled`() {
		assertFalse(parser.filterCapabilities.isSearchSupported)
		assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
	}

	@Test
	fun `build region and page paths`() {
		val filter = ContentListFilter(tags = setOf(ContentTag("国产漫画", "list/1", parser.source)))

		assertEquals("/category/list/1/page/2", parser.buildListPath(2, filter))
		assertEquals("/category", parser.buildListPath(1, ContentListFilter.EMPTY))
	}

	@Test
	fun `parse list fixture`() {
		val result = parser.parseList(fixture("list.html", "https://www.gfmh.app/category")).single()

		assertEquals("说谎的小狗会被吃掉的", result.title)
		assertEquals("https://www.gfmh.app/563672.html", result.publicUrl)
		assertEquals("https://cover.example/563672.webp", result.coverUrl)
		assertEquals(setOf("作者甲"), result.authors)
		assertEquals(ContentState.ONGOING, result.state)
	}

	@Test
	fun `parse detail and chapter fixture`() {
		val result = parser.parseDetails(
			fixture("detail.html", "https://www.gfmh.app/616485.html"),
			content(),
		)

		assertEquals("测试漫画", result.title)
		assertEquals("作品简介", result.description)
		assertEquals(setOf("作者甲"), result.authors)
		assertEquals(setOf("恋爱"), result.tags.mapTo(linkedSetOf()) { it.title })
		assertEquals(ContentState.FINISHED, result.state)
		assertEquals(ContentRating.SAFE, result.contentRating)
		assertEquals(listOf(1f, 2f, 0f), result.chapters?.map { it.number })
	}

	@Test
	fun `decode chapter fixture without executing javascript`() {
		val pages = parser.parsePages(
			fixture("chapter.html", "https://www.gfmh.app/616485/216802.html"),
			"https://www.gfmh.app/616485/216802.html",
		)

		assertEquals(listOf("https://img.example/1.jpg", "https://img.example/2.png"), pages.map { it.url })
	}

	private fun fixture(name: String, baseUri: String) = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/gufeng/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		baseUri,
	)

	private fun content() = Content(
		id = 616485,
		title = "测试漫画",
		altTitles = emptySet(),
		url = "/616485.html",
		publicUrl = "https://www.gfmh.app/616485.html",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = parser.source,
	)
}
