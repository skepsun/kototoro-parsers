package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

@EnabledIfEnvironmentVariable(named = "DDMANHUA_INTEGRATION_TEST", matches = "1")
class DdManhuaParserIntegrationTest {

	private val parser = DdManhuaParser(ContentLoaderContextMock)

	@Test
	fun `complete online browse and reading flow`() = runBlocking {
		val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
		assertEquals(30, list.size)
		assertTrue(list.all { it.url.startsWith("/book/") })

		val seed = Content(
			id = 618076,
			title = "神奇蜘蛛侠v1",
			altTitles = emptySet(),
			url = "/book/618076.html",
			publicUrl = "http://ddmanhua.com/book/618076.html",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = parser.source,
		)
		val details = parser.getDetails(seed)
		assertEquals("神奇蜘蛛侠v1", details.title)
		assertEquals(setOf("Marvel Comics"), details.authors)
		assertTrue(details.chapters.orEmpty().size > 100)

		val chapter = details.chapters?.firstOrNull() ?: error("章节列表为空")
		val pages = parser.getPages(chapter)
		assertEquals(22, pages.size)
		assertFalse(pages.first().url.contains("#dd-aes"))

		listOf(pages.first(), pages.last()).forEach { page ->
			val request = Request.Builder()
				.url(page.url)
				.tag(ContentSource::class.java, parser.source)
				.apply { page.headers.orEmpty().forEach { (name, value) -> header(name, value) } }
				.build()
			ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
				assertEquals(200, response.code)
				assertEquals("image", response.body.contentType()?.type)
				assertTrue(response.body.bytes().size > 1_024)
			}
		}
	}
}
