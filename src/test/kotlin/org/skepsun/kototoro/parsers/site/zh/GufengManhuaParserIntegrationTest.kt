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

@EnabledIfEnvironmentVariable(named = "GUFENGMANHUA_INTEGRATION_TEST", matches = "1")
class GufengManhuaParserIntegrationTest {

	private val parser = GufengManhuaParser(ContentLoaderContextMock)

	@Test
	fun `complete online browse and encrypted reading flow`() = runBlocking {
		val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
		assertEquals(16, list.size)
		assertTrue(list.all { it.url.matches(Regex("/\\d+\\.html")) })

		val seed = Content(
			id = 616485,
			title = "逆后宫游戏中的假圣女",
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
		val details = parser.getDetails(seed)
		assertEquals("逆后宫游戏中的假圣女", details.title)
		assertTrue(details.chapters.orEmpty().size >= 23)

		val chapter = details.chapters?.firstOrNull() ?: error("章节列表为空")
		val pages = parser.getPages(chapter)
		assertTrue(pages.size > 5)
		assertFalse(pages.any { it.url.contains("#gufeng-aes") })

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
