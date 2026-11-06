package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid

// 明日方舟漫画（terra-historicus.hypergryph.com）离线夹具测试：
// - 站点只有 JSON 接口：列表 `/api/comic?topicKey=`、更新 `/api/recentUpdate?topicKey=`、
//   详情 `/api/comic/{cid}`、阅读 `/api/comic/{cid}/episode/{eid}`、取图 `.../page?pageNum=N`；
// - 夹具都是从真实响应裁下来的前几条（列表 22→3、塔罗斯II 6→2、更新 4→3、61 页→4 页）；
// - 重点覆盖三件容易写错的事：作品 id 与话 id 共用 `cid` 字段、接口给的是新话在前、图片地址只能现取。
class TerraHistoricusTest {

	private fun context(): OfflineContentLoaderContext = contextWith()

	private fun contextWith(
		vararg extra: Pair<String, String>,
	): OfflineContentLoaderContext {
		val base = "https://terra-historicus.hypergryph.com"
		val fixtures = mutableMapOf(
			"$base/api/comic?topicKey=terra-historicus" to "terra_historicus_list.json",
			"$base/api/comic?topicKey=talos-ii-historicus" to "terra_historicus_talos.json",
			"$base/api/recentUpdate?topicKey=terra-historicus" to "terra_historicus_recent.json",
			"$base/api/comic/3114" to "terra_historicus_detail.json",
			"$base/api/comic/3114/episode/6303" to "terra_historicus_episode.json",
			"$base/api/comic/3114/episode/6303/page?pageNum=1" to "terra_historicus_page.json",
		)
		extra.forEach { (url, fixture) -> fixtures[url] = fixture }
		return OfflineContentLoaderContext(
			fixtures = fixtures,
			responseTypes = fixtures.keys.associateWith { "application/json; charset=utf-8" },
		)
	}

	private fun parser(context: OfflineContentLoaderContext = context()): TerraHistoricus =
		TerraHistoricus(context)

	@Test
	fun `sort orders, capabilities and page size are advertised`() {
		val parser = parser()
		assertEquals(setOf(SortOrder.POPULARITY, SortOrder.UPDATED), parser.availableSortOrders)
		assertTrue(parser.filterCapabilities.isSearchSupported)
		assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
		assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
		assertFalse(parser.filterCapabilities.isSearchWithFiltersSupported)
		assertEquals("terra-historicus.hypergryph.com", parser.domain)
		// 两个频道各自一次给全（22 / 6 部），页容量只是为了不让宿主误判还有下一页
		assertEquals(30, parser.pageSize)
	}

	@Test
	fun `filter options expose the two real channels`() = kotlinx.coroutines.runBlocking {
		val options = parser().getFilterOptions()
		val group = options.tagGroups.single()
		assertEquals("频道", group.title)
		assertEquals(
			listOf("terra-historicus" to "泰拉记事", "talos-ii-historicus" to "塔罗斯II"),
			group.tags.map { it.key to it.title },
		)
		assertEquals(group.tags, options.availableTags)
		// 站点没有连载状态维度，不能凭空暴露
		assertTrue(options.availableStates.isEmpty())
	}

	@Test
	fun `first page lists the terra channel`() = kotlinx.coroutines.runBlocking {
		val context = context()
		val parser = parser(context)
		val items = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter.EMPTY)

		assertEquals(3, items.size)
		val first = items.first()
		assertEquals("序言组曲：火与路", first.title)
		assertEquals("/terra-historicus/comic/3114", first.url)
		assertEquals(
			"https://terra-historicus.hypergryph.com/terra-historicus/comic/3114",
			first.publicUrl,
		)
		// id 与域名无关：同一路径任何域名下拼出来的 uid 都一致
		assertEquals(parser.generateUid("/terra-historicus/comic/3114"), first.id)
		assertEquals(RATING_UNKNOWN, first.rating)
		assertNull(first.contentRating)
		assertNull(first.state)
		assertEquals(setOf("鹰角网络"), first.authors)
		assertEquals("https://web.hycdn.cn/comic/pic/20261013/fd4f3196b2320db30a11ecd053551fef.jpg", first.coverUrl)
		// subtitle 为空串时要落成 null，不要留个空描述
		assertNull(first.description)

		assertEquals(
			listOf(
				"https://terra-historicus.hypergryph.com/api/comic?topicKey=terra-historicus",
			),
			context.requests,
		)
	}

	@Test
	fun `second page lists the talos channel`() = kotlinx.coroutines.runBlocking {
		val items = parser().getListPage(2, SortOrder.POPULARITY, ContentListFilter.EMPTY)
		assertEquals(2, items.size)
		assertEquals("溯始先声：缘起", items.first().title)
		assertEquals("/talos-ii-historicus/comic/6251", items.first().url)
		assertTrue(items.all { it.url.startsWith("/talos-ii-historicus/comic/") })
	}

	@Test
	fun `pages beyond the two channels are empty and skip the network`() = kotlinx.coroutines.runBlocking {
		val context = context()
		val parser = parser(context)
		assertTrue(parser.getListPage(3, SortOrder.POPULARITY, ContentListFilter.EMPTY).isEmpty())
		assertTrue(parser.getListPage(3, SortOrder.UPDATED, ContentListFilter.EMPTY).isEmpty())
		assertTrue(context.requests.isEmpty())
	}

	@Test
	fun `a selected channel tag overrides the page position`() = kotlinx.coroutines.runBlocking {
		val parser = parser()
		val filter = ContentListFilter(
			tags = setOf(ContentTag("塔罗斯II", "talos-ii-historicus", parser.source)),
		)
		val items = parser.getListPage(1, SortOrder.POPULARITY, filter)
		assertEquals("溯始先声：缘起", items.first().title)
		assertTrue(items.all { it.url.startsWith("/talos-ii-historicus/") })
	}

	@Test
	fun `updated order reads the recentUpdate endpoint`() = kotlinx.coroutines.runBlocking {
		val items = parser().getListPage(1, SortOrder.UPDATED, ContentListFilter.EMPTY)
		assertEquals(3, items.size)
		val first = items.first()
		// 更新接口用的是 comicCid / coverUrl，字段名和作品列表不同
		assertEquals("123罗德岛！？", first.title)
		assertEquals("/terra-historicus/comic/6253", first.url)
		assertEquals("https://web.hycdn.cn/comic/pic/20261102/b7cab3cb872bdeebcd68b5ed79543363.jpg", first.coverUrl)
		assertEquals("你可能不知道的罗德岛小剧场！", first.description)
	}

	@Test
	fun `search filters the channel locally`() = kotlinx.coroutines.runBlocking {
		val parser = parser()
		val items = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = "循途"))
		assertEquals(listOf("循途漫录：大将军，出击！"), items.map { it.title })
		assertTrue(
			parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = "不存在的作品")).isEmpty(),
		)
	}

	@Test
	fun `details turn newest-first episodes into an ascending chapter list`() = kotlinx.coroutines.runBlocking {
		val manga = Content(
			id = 1L,
			title = "序言组曲：火与路",
			altTitles = emptySet(),
			url = "/terra-historicus/comic/3114",
			publicUrl = "https://terra-historicus.hypergryph.com/terra-historicus/comic/3114",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = parser().source,
		)
		val details = parser().getDetails(manga)

		assertEquals("浴火前行", details.description)
		assertEquals(setOf("鹰角网络"), details.authors)
		assertEquals(setOf("塔露拉", "序言组曲"), details.tags.map { it.title }.toSet())

		val chapters = details.chapters.orEmpty().sortedBy { it.number }
		assertEquals(2, chapters.size)
		val first = chapters.first()
		// 接口给的是 02 下篇 在前，这里必须翻成 01 上篇 在前
		assertEquals("01 上篇", first.title)
		assertEquals(1f, first.number)
		assertEquals("/terra-historicus/comic/3114/1565", first.url)
		assertEquals(1793289600000L, first.uploadDate)
		assertEquals("02 下篇", chapters.last().title)
		assertEquals(2f, chapters.last().number)
		assertEquals("/terra-historicus/comic/3114/6303", chapters.last().url)
		assertNull(first.scanlator)
		assertEquals(0, first.volume)
	}

	@Test
	fun `pages map pageInfos to per-page image endpoints`() = kotlinx.coroutines.runBlocking {
		val chapter = ContentChapter(
			id = 2L,
			title = "02 下篇",
			number = 2f,
			volume = 0,
			url = "/terra-historicus/comic/3114/6303",
			scanlator = null,
			uploadDate = 1793376000000L,
			branch = null,
			source = parser().source,
		)
		val pages = parser().getPages(chapter)
		assertEquals(4, pages.size)
		assertEquals(listOf(1L, 2L, 3L, 4L), pages.map { it.id })
		assertEquals(
			"https://terra-historicus.hypergryph.com/api/comic/3114/episode/6303/page?pageNum=4",
			pages.last().url,
		)
		assertTrue(pages.all { it.preview == null })
	}

	@Test
	fun `image url is resolved from the page endpoint`() = kotlinx.coroutines.runBlocking {
		val page = ContentPage(
			id = 1L,
			url = "https://terra-historicus.hypergryph.com/api/comic/3114/episode/6303/page?pageNum=1",
			preview = null,
			source = parser().source,
		)
		assertEquals(
			"https://res01.hycdn.cn/6006834dd907aace507228f3e59ae76b/6AED519F/comic/pic/20261028/" +
				"d3c332858ba16e20cd491492f94fb28e.jpg",
			parser().getPageUrl(page),
		)
	}

	@Test
	fun `chapter without page data fails with site context`() {
		val context = OfflineContentLoaderContext(
			fixtures = mapOf(
				"https://terra-historicus.hypergryph.com/api/comic/3114/episode/9" to "terra_historicus_empty_episode.json",
			),
			responseTypes = mapOf(
				"https://terra-historicus.hypergryph.com/api/comic/3114/episode/9" to "application/json",
			),
		)
		val chapter = ContentChapter(
			id = 3L,
			title = "预告",
			number = 3f,
			volume = 0,
			url = "/terra-historicus/comic/3114/9",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = parser(context).source,
		)
		val error = assertThrows(ParseException::class.java) {
			kotlinx.coroutines.runBlocking { parser(context).getPages(chapter) }
		}
		assertTrue(error.message.orEmpty().contains("明日方舟漫画"))
	}

	@Test
	fun `non-zero api code is reported as a parse failure`() {
		val parser = parser()
		val error = assertThrows(ParseException::class.java) {
			parser.parseComicListJson("""{"code":400,"msg":"bad topic"}""", "terra-historicus", "https://example.test")
		}
		assertTrue(error.message.orEmpty().contains("400"))
	}

	@Test
	fun `foreign urls are rejected with a readable error`() {
		val parser = parser()
		val manga = Content(
			id = 1L,
			title = "t",
			altTitles = emptySet(),
			url = "/somewhere/else",
			publicUrl = "https://terra-historicus.hypergryph.com/somewhere/else",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = parser.source,
		)
		val error = assertThrows(ParseException::class.java) { parser.parseComicUrl(manga.url, manga) }
		assertTrue(error.message.orEmpty().contains("作品 id"))
	}
}
