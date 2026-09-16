package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

class LKNovelTest {

	private val parser = LKNovelUs(ContentLoaderContextMock)

	@Test
	fun `parse taxonomy fixture`() {
		val options = parser.parseTaxonomy(fixture("taxonomy.json").getJSONObject("data"))

		val keys = options.availableTags.map { it.key }.toSet()
		assertTrue("tag:异世界" in keys)
		assertTrue("channel:fanfic" in keys)

		val groupTitles = options.tagGroups.map { it.title }
		assertEquals(listOf("热门题材", "主题", "角色", "情节", "其他", "频道"), groupTitles)
		val channelGroup = options.tagGroups.first { it.title == "频道" }
		assertTrue(channelGroup.isExclusive)
		assertFalse(options.tagGroups.first { it.title == "主题" }.isExclusive)

		assertEquals(setOf(ContentState.ONGOING, ContentState.FINISHED), options.availableStates)
	}

	@Test
	fun `unified search request params`() {
		val isekai = ContentTag("异世界", "tag:异世界", parser.source)
		val fanfic = ContentTag("同人", "channel:fanfic", parser.source)

		val searchData = parser.buildUnifiedSearchData(
			page = 2,
			order = SortOrder.RELEVANCE,
			filter = ContentListFilter(
				query = "刀剑",
				tags = setOf(isekai),
				states = setOf(ContentState.FINISHED),
			),
		)
		assertEquals("刀剑", searchData.getString("q"))
		assertEquals(2, searchData.getInt("page"))
		assertEquals("异世界", searchData.getString("primary_tag"))
		assertEquals("completed", searchData.getString("status_bucket"))
		assertEquals("relevance", searchData.getString("sort"))
		assertFalse(searchData.has("channel_code"))

		// 无关键词浏览：即使带了标签也必须绑定频道，否则站方返回空列表
		val browseData = parser.buildUnifiedSearchData(
			page = 1,
			order = SortOrder.UPDATED,
			filter = ContentListFilter(query = "  ", tags = setOf(isekai)),
		)
		assertEquals("lightnovel", browseData.getString("channel_code"))
		assertEquals("异世界", browseData.getString("primary_tag"))
		assertEquals("new", browseData.getString("sort"))
		assertFalse(browseData.has("status_bucket"))

		// 频道标签覆盖默认频道
		val channelData = parser.buildUnifiedSearchData(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(fanfic)))
		assertEquals("fanfic", channelData.getString("channel_code"))
	}

	@Test
	fun `parse unified search fixture`() {
		val result = parser.parseUnifiedSearchResult(fixture("unified_search.json"))

		assertEquals(2, result.size)
		val series = result[0]
		// source_series_id 优先：与旧版分类浏览生成同一实体
		assertEquals("/series/14121", series.url)
		assertEquals("Sword Art Online刀剑神域9 Alicization Beginning", series.title)
		assertEquals(setOf("川原礫"), series.authors)
		assertEquals(0.8f, series.rating, 0.001f)
		assertEquals(setOf("奇幻"), series.tags.map { it.title }.toSet())

		val bookOnly = result[1]
		assertEquals("/book/555001", bookOnly.url)
		assertEquals(-1f, bookOnly.rating, 0.001f)
		assertTrue(bookOnly.authors.isEmpty())
	}

	@Test
	fun `parse category fixture dedups placeholder titles`() {
		val result = parser.parseContentList(fixture("category.json"))

		// 缺 sid/aid 的条目被丢弃而不是退化成同一 URL
		assertEquals(3, result.size)

		// “未知合集”占位名退回文章标题，不同作品不再同名
		val placeholder = result[0]
		assertEquals("/series/27625", placeholder.url)
		assertEquals("某人汉化 第一卷", placeholder.title)

		val named = result[1]
		assertEquals("已知合集名", named.title)
		assertEquals("/series/123", named.url)

		val article = result[2]
		assertEquals("/article/777", article.url)
		assertEquals("单篇文章", article.title)
	}

	private fun fixture(name: String): JSONObject = JSONObject(
		javaClass.getResourceAsStream("/fixtures/lknovel/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
	)
}
