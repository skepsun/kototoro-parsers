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
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

@EnabledIfEnvironmentVariable(named = "MANHUA100_INTEGRATION_TEST", matches = "1")
class Manhua100ParserIntegrationTest {

	private val parser = Manhua100Parser(ContentLoaderContextMock)

	@Test
	fun `complete online reading flow`() = runBlocking {
		val results = parser.getList(
			offset = 0,
			order = SortOrder.UPDATED,
			filter = ContentListFilter(query = "一人之下"),
		)
		val manga = results.firstOrNull { it.title == "一人之下" }
			?: error("搜索结果中没有一人之下")

		val details = parser.getDetails(manga)
		assertEquals(setOf("动漫堂"), details.authors)
		assertTrue(details.description?.isNotBlank() == true)
		assertTrue(details.chapters.orEmpty().size > 700)

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
				assertEquals("image", response.body.contentType()?.type)
				assertTrue(response.body.bytes().size > 1_024)
			}
		}
	}
}
