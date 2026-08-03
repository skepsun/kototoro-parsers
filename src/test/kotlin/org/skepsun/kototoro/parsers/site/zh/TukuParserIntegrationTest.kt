package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

@EnabledIfEnvironmentVariable(named = "TUKU_INTEGRATION_TEST", matches = "1")
class TukuParserIntegrationTest {

	private val parser = TukuParser(ContentLoaderContextMock)

	@Test
	fun `complete online reading flow`() = runBlocking {
		val options = parser.getFilterOptions()
		val filtered = parser.getList(
			offset = 0,
			order = SortOrder.UPDATED,
			filter = ContentListFilter(
				tags = setOf(
					options.availableTags.single { it.title == "热血" },
					options.availableTags.single { it.title == "日本" },
				),
				states = setOf(ContentState.ONGOING),
			),
		)
		assertTrue(filtered.any { it.title == "海贼王" })

		val results = parser.getList(
			offset = 0,
			order = SortOrder.UPDATED,
			filter = ContentListFilter(query = "海贼王"),
		)
		val manga = results.firstOrNull { it.title == "海贼王" }
			?: error("搜索结果中没有海贼王")
		assertTrue(manga.publicUrl.startsWith("https://www.tuku.cc/manga-"))

		val details = parser.getDetails(manga)
		assertTrue(details.authors.isNotEmpty())
		assertTrue(details.description?.isNotBlank() == true)
		val chapter = details.chapters?.firstOrNull() ?: error("章节列表为空")
		val pages = parser.getPages(chapter)
		assertTrue(pages.isNotEmpty())

		listOf(pages.first(), pages.last()).forEach { page ->
			val request = Request.Builder()
				.url(page.url)
				.tag(ContentSource::class.java, parser.source)
				.apply { page.headers.orEmpty().forEach { (name, value) -> header(name, value) } }
				.build()
			ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
				assertEquals(200, response.code)
				assertTrue(response.body.contentType()?.type == "image")
				assertTrue(response.body.contentLength() > 1_024)
			}
		}
	}
}
