package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid

/**
 * 漫蛙（manwa2）：列表/搜索/详情全是 SSR，阅读页图片要从 AES-CBC 加密的 `var params` 里解出来。
 */
internal class Manwa2ParserTest {

	private val parser = Manwa2Parser(ContentLoaderContextMock)

	private fun html(name: String) = Jsoup.parse(fixtureText(name))

	private fun fixtureText(name: String): String =
		requireNotNull(javaClass.getResourceAsStream("/fixtures/manwa2/$name")) { "missing fixture $name" }
			.bufferedReader().use { it.readText() }

	private fun mirror(domain: String): Manwa2Parser {
		val instance = Manwa2Parser(ContentLoaderContextMock)
		(instance.config as SourceConfigMock).set(instance.configKeyDomain, domain)
		return instance
	}

	private fun tag(segment: String): ContentTag = kotlinx.coroutines.runBlocking { parser.getFilterOptions() }
		.availableTags.single { it.key == segment }

	private fun manga(href: String) = Content(
		id = parser.generateUid(href),
		title = "爱神不在排练场！",
		altTitles = emptySet(),
		url = href,
		publicUrl = "https://manwa2.com$href",
		rating = RATING_UNKNOWN,
		contentRating = ContentRating.ADULT,
		coverUrl = null,
		tags = emptySet(),
		authors = emptySet(),
		state = null,
		source = parser.source,
		chapters = emptyList(),
		description = "",
	)

	@Test
	fun `expose known domains and keep bare manwa2 com as default`() {
		assertEquals(listOf("manwa2.com", "www.manwa2.com"), parser.configKeyDomain.presetValues.toList())
		assertEquals("manwa2.com", parser.configKeyDomain.defaultValue)
	}

	@Test
	fun `declared as adult manga source`() {
		assertEquals(ContentType.HENTAI_MANGA, parser.source.contentType)
	}

	@Test
	fun `offer only the sort orders the site really supports`() {
		assertEquals(setOf(SortOrder.UPDATED, SortOrder.POPULARITY), parser.availableSortOrders)
		assertEquals(SortOrder.UPDATED, parser.defaultSortOrder)
	}

	@Test
	fun `route unfiltered first page to the site ranking pages`() {
		assertEquals(
			"/custom/update",
			parser.buildListPath(1, SortOrder.UPDATED, ContentListFilter.EMPTY),
		)
		assertEquals(
			"/custom/top",
			parser.buildListPath(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
		)
		// 排行/更新页没有分页控件，第二页必须收敛
		assertNull(parser.buildListPath(2, SortOrder.UPDATED, ContentListFilter.EMPTY))
		assertNull(parser.buildListPath(2, SortOrder.POPULARITY, ContentListFilter.EMPTY))
	}

	@Test
	fun `build category paths from single segment filters`() {
		assertEquals(
			"/category/list/3",
			parser.buildListPath(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(tag("list/3")))),
		)
		assertEquals(
			"/category/tags/889/page/3",
			parser.buildListPath(3, SortOrder.UPDATED, ContentListFilter(tags = setOf(tag("tags/889")))),
		)
	}

	@Test
	fun `combine one segment per dimension in a stable order`() {
		// 站点对路径段顺序不敏感（已核对 /category/list/3/finish/2 与 /category/finish/2/list/3 等价）
		assertEquals(
			"/category/list/3/finish/2",
			parser.buildListPath(
				1,
				SortOrder.UPDATED,
				ContentListFilter(tags = setOf(tag("finish/2"), tag("list/3"))),
			),
		)
		// 同一维度只取第一个，避免拼出不存在的路径
		assertEquals(
			"/category/list/1",
			parser.buildListPath(
				1,
				SortOrder.UPDATED,
				ContentListFilter(tags = setOf(tag("list/1"), tag("list/2"))),
			),
		)
	}

	@Test
	fun `expose filter groups without duplicates`() {
		val options = kotlinx.coroutines.runBlocking { parser.getFilterOptions() }
		assertTrue(options.tagGroups.size >= 6)
		assertEquals(options.tagGroups.flatMap { it.tags }.toSet(), options.availableTags)
		assertEquals(options.availableTags.size, options.tagGroups.sumOf { it.tags.size })
		assertTrue(options.availableTags.any { it.title == "韩国漫画" })
		assertTrue(options.availableTags.any { it.title == "韩漫" })
		assertEquals(setOf(ContentRating.ADULT), options.availableContentRating.toSet())
	}

	@Test
	fun `parse category cards and skip the lazy placeholder cover`() {
		val list = parser.parseList(html("list.html"))
		assertEquals(6, list.size)
		assertEquals(6, list.map(Content::id).distinct().size)
		val first = list.first()
		assertEquals("/comic/422658", first.url)
		assertEquals("https://manwa2.com/comic/422658", first.publicUrl)
		assertTrue(first.title.isNotEmpty())
		assertTrue(first.description!!.contains("温泉旅行"))
		// 未懒加载的卡片只有 packs/mccms/empty.png 占位图，不能当封面
		assertNull(first.coverUrl)
		assertEquals(5, list.count { it.coverUrl != null })
		assertTrue(list.drop(1).all { it.coverUrl?.startsWith("https://img1.baipiaoguai.org/") == true })
		assertEquals(RATING_UNKNOWN, first.rating)
		assertEquals(ContentRating.ADULT, first.contentRating)
	}

	@Test
	fun `parse ranking cards whose cover sits in src`() {
		val list = parser.parseList(html("ranking.html"))
		assertEquals(4, list.size)
		assertTrue(list.all { it.coverUrl?.startsWith("https://img1.baipiaoguai.org/") == true })
		assertTrue(list.all { it.url.startsWith("/comic/") })
	}

	@Test
	fun `parse search results with author and state`() {
		val list = parser.parseSearchResults(html("search.html"))
		assertEquals(3, list.size)
		val first = list.first()
		assertEquals("/comic/602669", first.url)
		assertEquals("致命恋爱体验", first.title)
		assertEquals(setOf("dnehd_k"), first.authors)
		assertEquals(ContentState.ONGOING, first.state)
		assertTrue(first.coverUrl!!.startsWith("https://img1.baipiaoguai.org/"))
		assertTrue(first.description!!.contains("恋爱模拟游戏"))
	}

	@Test
	fun `parse detail metadata tags and state`() {
		val source = manga("/comic/620095")
		val detail = parser.parseDetails(html("detail.html"), source)
		assertEquals("爱神不在排练场！", detail.title)
		assertEquals(
			"https://newmwtuyuan7.cc/static/upload3/book/content/20261011/c_28e92c4980202e27ee32614683b645214.jpg",
			detail.coverUrl,
		)
		assertEquals(detail.coverUrl, detail.largeCoverUrl)
		assertEquals(setOf("猫橘工作室"), detail.authors)
		assertEquals(ContentState.ONGOING, detail.state)
		assertTrue(detail.description!!.contains("爱神"))
		// 站点没有分类标签锚点，标签由「地区」+ 空格分隔的「类别」推导
		val tagTitles = detail.tags.map(ContentTag::title).toSet()
		assertTrue(tagTitles.contains("国产漫画"), "tags=$tagTitles")
		assertTrue(tagTitles.contains("连载中"), "tags=$tagTitles")
		assertTrue(tagTitles.contains("国漫"), "tags=$tagTitles")
		assertFalse(tagTitles.any { it.isBlank() })
	}

	@Test
	fun `reverse the newest first chapter list into reading order`() {
		val source = manga("/comic/620095")
		val chapters = parser.parseDetails(html("detail.html"), source).chapters.orEmpty()
		assertEquals(9, chapters.size)
		assertEquals("预告 电波爱神x面瘫演员", chapters.first().title)
		assertEquals("Stage 07 不速之客", chapters.last().title)
		assertEquals("/chapter/620095/221881", chapters.first().url)
		assertEquals("/chapter/620095/222133", chapters.last().url)
		assertEquals(0f, chapters.first().number)
		assertEquals(1f, chapters[1].number)
		assertEquals(7f, chapters.last().number)
		assertEquals(9, chapters.map { it.id }.distinct().size)
	}

	@Test
	fun `keep long chapter lists complete and ordered`() {
		val chapters = parser.parseDetails(html("detail_many.html"), manga("/comic/418962")).chapters.orEmpty()
		assertEquals(8, chapters.size)
		assertEquals("第892话 广邀天下炼丹师", chapters.first().title)
		assertEquals("第932话 暗夜突袭开始", chapters.last().title)
		assertEquals(892f, chapters.first().number)
		assertEquals(932f, chapters.last().number)
		assertTrue(chapters.map { it.number }.zipWithNext().all { (a, b) -> a <= b })
	}

	@Test
	fun `decrypt reader params into page images with referer`() {
		val pages = parser.parsePages(html("chapter.html"), "/chapter/555129/205162")
		assertEquals(22, pages.size)
		assertEquals(22, pages.map { it.id }.distinct().size)
		val first = pages.first()
		assertTrue(first.url.startsWith("https://img1.baipiaoguai.org/"), "url=${first.url}")
		assertTrue(first.url.endsWith(".webp") || first.url.endsWith(".jpg"), "url=${first.url}")
		assertEquals(first.url, first.preview)
		// 图床按 Referer 防盗链，不带会 403
		assertEquals("https://manwa2.com/", first.headers?.get("Referer"))
		assertTrue(pages.all { it.headers?.get("Referer") == "https://manwa2.com/" })
	}

	@Test
	fun `return no pages when the encrypted params are missing`() {
		assertTrue(parser.parsePages(html("list.html"), "/chapter/1/2").isEmpty())
	}

	@Test
	fun `use the configured mirror for urls`() {
		val instance = mirror("www.manwa2.com")
		assertEquals(
			"/category/list/3",
			instance.buildListPath(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(tag("list/3")))),
		)
		val list = instance.parseList(html("ranking.html"))
		assertEquals("https://www.manwa2.com/comic/${list.first().url.substringAfterLast('/')}", list.first().publicUrl)
		val pages = instance.parsePages(html("chapter.html"), "/chapter/555129/205162")
		assertEquals("https://www.manwa2.com/", pages.first().headers?.get("Referer"))
	}
}
