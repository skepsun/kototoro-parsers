package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock

/**
 * 假搜索回归测试：站点对无匹配关键字仍返回 200 + 推荐卡片时，
 * 解析器必须识别空态并返回空列表，而不是把推荐内容当成搜索结果。
 */
class FakeSearchGuardTest {

	private val context = ContentLoaderContextMock

	private fun fixture(name: String): String {
		return requireNotNull(javaClass.getResourceAsStream("/fixtures/search/$name")) {
			"Missing fixture: $name"
		}.bufferedReader().use { it.readText() }
	}

	@Test
	fun godaEmptySearchStateIsDetected() {
		val doc = Jsoup.parse(fixture("goda-search-empty.html"))
		assertTrue(GodaParser(context).isEmptySearchPage(doc))
	}

	@Test
	fun godaSearchResultsAreNotTreatedAsEmpty() {
		val doc = Jsoup.parse(fixture("goda-search-results.html"))
		assertFalse(GodaParser(context).isEmptySearchPage(doc))
	}

	@Test
	fun mh1234EmptySearchDropsRecommendations() {
		val doc = Jsoup.parse(fixture("mh1234-search-empty.html"))
		assertTrue(Mh1234Parser(context).parseSearchList(doc).isEmpty())
	}

	@Test
	fun mh1234SearchResultsKeepOnlyRealMatches() {
		val parser = Mh1234Parser(context)
		val titles = parser.parseSearchList(Jsoup.parse(fixture("mh1234-search-results.html")))
			.map { it.title }
		assertEquals(listOf("进击的巨人", "巨人族的新娘"), titles)
	}

	@Test
	fun lightNovelWikiDoesNotAdvertiseSearch() {
		// 站点忽略查询参数，只能如实声明不支持搜索，避免返回固定的无关目录
		assertFalse(LightNovelWiki(context).filterCapabilities.isSearchSupported)
	}
}
