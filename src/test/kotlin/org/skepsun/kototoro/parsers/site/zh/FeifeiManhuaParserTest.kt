package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class FeifeiManhuaParserTest {

	private val parser = FeifeiManhuaParser(ContentLoaderContextMock)

	@Test
	fun `expose search and combinable filters`() {
		assertTrue(parser.filterCapabilities.isSearchSupported)
		assertTrue(parser.filterCapabilities.isMultipleTagsSupported)
		assertFalse(parser.filterCapabilities.isSearchWithFiltersSupported)
	}

	@Test
	fun `build list filter and search paths`() {
		val filter = ContentListFilter(
			tags = setOf(
				ContentTag("国漫", "area=3", parser.source),
				ContentTag("古风", "cate=古风", parser.source),
			),
			states = setOf(ContentState.ONGOING),
		)

		val path = parser.buildListPath(2, filter)
		assertTrue(path.startsWith("/booklist?"))
		assertTrue(path.contains("cate=古风"))
		assertTrue(path.contains("area=3"))
		assertTrue(path.contains("end=2"))
		assertTrue(path.endsWith("page=2"))
		assertEquals("/2cb?keyword=%E6%96%97%E7%A0%B4&sn=pp&page=2", parser.buildSearchPath(2, "斗破"))
	}

	@Test
	fun `parse list fixture`() {
		val result = parser.parseList(fixture("list.html", "https://www.feifeimh.cc/booklist")).single()

		assertEquals("斗破苍穹", result.title)
		assertEquals("https://cover.example/12992.webp", result.coverUrl)
		assertEquals(0.8f, result.rating)
		assertEquals("作品简介", result.description)
	}

	@Test
	fun `parse details and clear chapter sidebar without rc4`() {
		val chapterDocument = fixture("chapter.html", "https://www.feifeimh.cc/chapter/745212")
		val result = parser.parseDetails(
			fixture("detail.html", "https://www.feifeimh.cc/book/12992"),
			chapterDocument,
			content(),
		)

		assertEquals("测试漫画", result.title)
		assertEquals(setOf("测试别名", "測試別名"), result.altTitles)
		assertEquals(setOf("作者甲", "作者乙"), result.authors)
		assertEquals(setOf("古风"), result.tags.mapTo(linkedSetOf()) { it.title })
		assertEquals(ContentState.FINISHED, result.state)
		assertEquals(listOf(1f, 2f), result.chapters?.map { it.number })
	}

	@Test
	fun `prefer loaded src and fall back to lazy data original`() {
		val pages = parser.parsePages(
			fixture("chapter.html", "https://www.feifeimh.cc/chapter/745212"),
			"https://www.feifeimh.cc/chapter/745212",
		)

		assertEquals(listOf("https://img.example/0.webp", "https://img.example/1.webp"), pages.map { it.url })
	}

	private fun fixture(name: String, baseUri: String) = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/feifei/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		baseUri,
	)

	private fun content() = Content(
		id = 12992,
		title = "测试漫画",
		altTitles = emptySet(),
		url = "/book/12992",
		publicUrl = "https://www.feifeimh.cc/book/12992",
		rating = RATING_UNKNOWN,
		contentRating = ContentRating.SAFE,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = parser.source,
	)
}
