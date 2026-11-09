package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ContentUnavailableException
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

// ACG漫画网（www.acgxmh.com）离线夹具测试，重点覆盖搜索路由：
// - 站内搜索是路径式 /q/<关键词>-<页>.html，`/?q=` 会被服务端忽略（永远返回推荐流）；
// - 命中标签/作品名时站点 302 到 www 标签页，列表结构 #list li + a.thumb + span.title；
// - 站点限流时返回「提示信息」页（搜索过于频繁），需要转成可读错误而不是空列表。
class AcgxmhTest {

	private val parser = Acgxmh(ContentLoaderContextMock)
	private val videoParser = AcgxmhVideo(ContentLoaderContextMock)

	private fun fixture(name: String): Document = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/acgxmh/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		"https://www.acgxmh.com/",
	)

	@Test
	fun `search target path is only taken from the site itself`() {
		assertEquals("/characters/hero.html", parser.sitePathOf("https://www.acgxmh.com/characters/hero.html"))
		assertEquals("/anime/douluodalu.html", parser.sitePathOf("https://www.acgxmh.com/anime/douluodalu.html"))
		// 仍是搜索路由（没被重定向）时不能当翻页目标
		assertNull(parser.sitePathOf("https://www.acgxmh.com/q/hero-1.html"))
		// 落到 Cloudflare 保护的搜索主机时不能当翻页目标
		assertNull(parser.sitePathOf("https://search.acgxmh.com/q/hero-1-abc.html"))
		assertNull(parser.sitePathOf("not a url"))
	}

	@Test
	fun `search uses the site path route instead of the ignored query parameter`() {
		val filter = ContentListFilter(query = "原神")
		assertEquals(
			"https://www.acgxmh.com/q/%E5%8E%9F%E7%A5%9E-1.html",
			parser.buildListUrl(1, SortOrder.UPDATED, filter),
		)
		assertEquals(
			"https://www.acgxmh.com/q/%E5%8E%9F%E7%A5%9E-3.html",
			parser.buildListUrl(3, SortOrder.UPDATED, filter),
		)
		assertEquals(
			"https://www.acgxmh.com/q/%E5%8E%9F%E7%A5%9E-1.html",
			videoParser.buildListUrl(1, SortOrder.UPDATED, filter),
		)
	}

	@Test
	fun `search keyword keeps spaces as percent encoded inside the path`() {
		assertEquals(
			"https://www.acgxmh.com/q/big%20breasts-2.html",
			parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter(query = "big breasts")),
		)
	}

	@Test
	fun `browse and tag urls stay unchanged`() {
		assertEquals(
			"https://www.acgxmh.com/h/",
			parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter()),
		)
		assertEquals(
			"https://www.acgxmh.com/h/index-4.html",
			parser.buildListUrl(4, SortOrder.UPDATED, ContentListFilter()),
		)
		assertEquals(
			"https://www.acgxmh.com/hot/index-2.html",
			parser.buildListUrl(2, SortOrder.POPULARITY, ContentListFilter()),
		)
		val tag = ContentTag("巨乳", "path:/tags/big-breasts.html", parser.source)
		assertEquals(
			"https://www.acgxmh.com/tags/big-breasts.html",
			parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(tag))),
		)
		assertEquals(
			"https://www.acgxmh.com/gif/",
			videoParser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter()),
		)
	}

	@Test
	fun `tag page reached from a search redirect parses manga cards`() {
		val items = parser.parseList(fixture("search-tag.html"))

		assertEquals(listOf("测试漫画一", "测试图集二", "测试网漫四", "测试西漫五"), items.map { it.title })
		assertEquals(
			listOf("/h/913633.html", "/hentai/913531.html", "/webtoon/913440.html", "/western/913430.html"),
			items.map { it.url },
		)
		assertEquals("https://www.acgxmh.com/h/913633.html", items[0].publicUrl)
		assertEquals(
			"https://file3.example-cdn.com/re/nh2/2026110708/thumb_500_425_4301895_1.webp",
			items[0].coverUrl,
		)
		assertNull(items[0].state)
		assertEquals(parser.source, items[0].source)
	}

	@Test
	fun `video parser only keeps video paths from the same tag page`() {
		val items = videoParser.parseList(fixture("search-tag.html"))

		assertEquals(listOf("测试动画三"), items.map { it.title })
		assertEquals(listOf("/gif/913460.html"), items.map { it.url })
	}

	@Test
	fun `video search explains when every hit is manga or gallery`() {
		val mangaOnly = with(videoParser) { fixture("tag-page-manga-only.html").describeNonVideoMatches() }
		assertTrue(mangaOnly.orEmpty().contains("3 条结果落在漫画/图集分类"), mangaOnly)
		assertTrue(videoParser.parseList(fixture("tag-page-manga-only.html")).isEmpty())

		// 只有动画条目时不该提示
		val videoOnly = Jsoup.parse(
			"""<ul id="list"><li><a class="thumb" href="/gif/913460.html" title="只有动画">
				<img src="https://file3.example-cdn.com/gif/a.webp" alt="只有动画" /></a></li></ul>""",
			"https://www.acgxmh.com/",
		)
		assertNull(with(videoParser) { videoOnly.describeNonVideoMatches() })
	}

	@Test
	fun `rate limit message page raises a readable error`() {
		val e = assertThrows(ContentUnavailableException::class.java) {
			parser.run { fixture("rate-limit.html").raiseIfSiteMessage() }
		}
		assertTrue(e.message.orEmpty().contains("搜索过于频繁"), e.message)
	}

	@Test
	fun `regular list page has no site message`() {
		parser.run { fixture("search-tag.html").raiseIfSiteMessage() }
	}

	@Test
	fun `work index page yields doujin tags`() {
		val tags = parser.parseIndexTags(fixture("anime-index.html"), AcgxmhBase.WORK_ITEM_SELECTOR)

		assertEquals(listOf("斗罗大陆", "原神"), tags.map { it.title })
		assertEquals(
			listOf("path:/anime/douluodalu.html", "path:/anime/genshin.html"),
			tags.map { it.key },
		)
		assertEquals(parser.source, tags[0].source)
	}

	@Test
	fun `doujin tags can be used as list filters`() {
		val genshin = parser.parseIndexTags(fixture("anime-index.html"), AcgxmhBase.WORK_ITEM_SELECTOR)[1]

		assertEquals(
			"https://www.acgxmh.com/anime/genshin.html",
			parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(genshin))),
		)
	}

	@Test
	fun `specials index pages keep using the default selector`() {
		val tags = parser.parseIndexTags(fixture("tags-index.html"), AcgxmhBase.SPECIALS_ITEM_SELECTOR)

		assertEquals(listOf("全彩", "巨乳", "黑丝"), tags.map { it.title })
		assertEquals(
			listOf("path:/tags/full-color.html", "path:/tags/huge-breasts.html", "path:/tags/stockings.html"),
			tags.map { it.key },
		)
		// 默认选择器在作品索引页上抓不到东西，这正是「戏仿」标签组曾经为空的原因
		assertTrue(
			parser.parseIndexTags(fixture("anime-index.html"), AcgxmhBase.SPECIALS_ITEM_SELECTOR).isEmpty(),
		)
	}
}
