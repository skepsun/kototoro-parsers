package org.skepsun.kototoro.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class ProjectGutenbergTest {

	private val parser = ProjectGutenberg(ContentLoaderContextMock)

	@Test
	fun `parse list fixture skips nav entries and dedupes`() {
		val result = parser.parseList(fixture("search.opds.xml"))

		assertEquals(2, result.size)
		val first = result[0]
		assertEquals("Leviathan", first.title)
		assertEquals(setOf("Hobbes, Thomas"), first.authors)
		assertEquals("ebooks/3207.opds", first.url)
		assertEquals("https://www.gutenberg.org/ebooks/3207", first.publicUrl)
		assertEquals("https://www.gutenberg.org/cache/epub/3207/pg3207.cover.small.jpg", first.coverUrl)
		assertEquals("https://www.gutenberg.org/cache/epub/3207/pg3207.cover.medium.jpg", first.largeCoverUrl)
		assertEquals(RATING_UNKNOWN, first.rating)
		assertEquals("Robert Orange", result[1].title)
	}

	@Test
	fun `parse details fixture prefers epub3`() {
		val content = content()
		val result = parser.parseDetails(fixture("book.opds.xml"), content)

		assertEquals("Wanderungen durch die Mark Brandenburg, Dritter Teil", result.title)
		assertEquals(setOf("Fontane, Theodor"), result.authors)
		assertEquals(
			"https://www.gutenberg.org/cache/epub/47311/pg47311.cover.medium.jpg",
			result.coverUrl,
		)
		assertTrue(result.tags.any { it.title == "Brandenburg (Germany) -- Description and travel" })
		assertTrue(result.description!!.contains("Language: de"))
		val chapter = result.chapters!!.single()
		assertEquals("Download EPUB", chapter.title)
		assertEquals("https://www.gutenberg.org/ebooks/47311.epub3.images", chapter.url)
	}

	@Test
	fun `build list urls`() {
		assertEquals(
			"https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads",
			parser.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter()),
		)
		assertEquals(
			"https://www.gutenberg.org/ebooks/search.opds/?sort_order=release_date&start_index=26",
			parser.buildListUrl(2, SortOrder.NEWEST, ContentListFilter()),
		)
		val search = ContentListFilter(query = "leviathan")
		assertEquals(
			"https://www.gutenberg.org/ebooks/search.opds/?query=leviathan",
			parser.buildListUrl(1, SortOrder.RELEVANCE, search),
		)
		val titleSorted = parser.buildListUrl(1, SortOrder.ALPHABETICAL, search)
		assertTrue(titleSorted.contains("query=leviathan"))
		assertTrue(titleSorted.contains("sort_order=title"))
	}

	private fun fixture(name: String): Document = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/gutenberg/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		"https://www.gutenberg.org/",
		Parser.xmlParser(),
	)

	private fun content() = Content(
		id = 3L,
		title = "Wanderungen durch die Mark Brandenburg",
		altTitles = emptySet(),
		url = "ebooks/47311.opds",
		publicUrl = "https://www.gutenberg.org/ebooks/47311",
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
