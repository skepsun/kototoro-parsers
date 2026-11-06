package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
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

@EnabledIfEnvironmentVariable(named = "TENCENT_COMICS_INTEGRATION_TEST", matches = "1")
class TencentComicsIntegrationTest {

	private val parser = TencentComicsParser(ContentLoaderContextMock)

	@Test
	fun `complete online browse and reading flow`() = runBlocking {
		val list = parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY)
		assertEquals(12, list.size)
		assertTrue(list.all { it.url.all(Char::isDigit) })

		val updated = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
		assertTrue(updated.isNotEmpty())
		assertTrue(updated.map { it.id }.intersect(list.map { it.id }.toSet()).size < list.size)

		val seed = Content(
			id = 1L,
			title = "一人之下",
			altTitles = emptySet(),
			url = "531490",
			publicUrl = "https://ac.qq.com/Comic/comicInfo/id/531490",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = parser.source,
		)
		val details = parser.getDetails(seed)
		assertEquals("一人之下", details.title)
		assertTrue(details.chapters.orEmpty().size > 100)

		val chapter = details.chapters?.firstOrNull() ?: error("章节列表为空")
		val pages = parser.getPages(chapter)
		assertTrue(pages.size > 5)

		listOf(pages.first(), pages.last()).forEach { page ->
			val request = Request.Builder()
				.url(page.url)
				.tag(ContentSource::class.java, parser.source)
				.apply { page.headers.orEmpty().forEach { (name, value) -> header(name, value) } }
				.build()
			ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
				assertTrue(response.isSuccessful, "图片不可访问: ${page.url}")
				val contentType = response.header("Content-Type").orEmpty()
				assertTrue(contentType.startsWith("image/"), "不是图片: $contentType ${page.url}")
			}
		}
	}
}
