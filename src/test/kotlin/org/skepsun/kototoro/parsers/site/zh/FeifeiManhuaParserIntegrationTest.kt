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

@EnabledIfEnvironmentVariable(named = "FEIFEIMANHUA_INTEGRATION_TEST", matches = "1")
class FeifeiManhuaParserIntegrationTest {

	private val parser = FeifeiManhuaParser(ContentLoaderContextMock)

	@Test
	fun `complete online search detail sidebar and reading flow`() = runBlocking {
		val list = parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY)
		assertEquals(28, list.size)

		val search = parser.getList(
			0,
			SortOrder.POPULARITY,
			ContentListFilter(query = "斗破苍穹"),
		)
		val seed = search.firstOrNull { it.url == "/book/12992" } ?: error("搜索结果缺少斗破苍穹")
		val details = parser.getDetails(seed)
		assertEquals("斗破苍穹", details.title)
		assertTrue(details.chapters.orEmpty().size >= 600)

		val chapter = details.chapters?.firstOrNull() ?: error("章节列表为空")
		val pages = parser.getPages(chapter)
		assertEquals(36, pages.size)

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
