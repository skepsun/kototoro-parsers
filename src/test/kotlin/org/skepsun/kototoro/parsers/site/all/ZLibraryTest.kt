package org.skepsun.kototoro.parsers.site.all

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Locale

class ZLibraryTest {

	private val parser = ZLibrary(ContentLoaderContextMock)

	@Test
	fun `parse book list fixture`() {
		val result = parser.parseBookList(fixture("search.html"))

		assertEquals(3, result.size)
		val first = result[0]
		assertEquals("Lost Souls Take Dog City", first.title)
		assertEquals("/book/25126620/78b103/lost-souls-take-dog-city.html", first.url)
		assertEquals("https://z-library.sk/book/25126620/78b103/lost-souls-take-dog-city.html", first.publicUrl)
		assertEquals(setOf("Ellie R. Hunter"), first.authors)
		assertEquals("https://s3proxy.cdn-zlib.example/covers100/collections/userbooks/eb03f5ea.jpg", first.coverUrl)
		assertEquals(0.88f, first.rating, 0.001f)
		assertTrue(first.description!!.contains("Format: epub"))

		// rating="0.0" 视为未知
		assertEquals(RATING_UNKNOWN, result[1].rating)
		assertTrue(result[1].description!!.contains("Publisher: Oxford University Press"))

		// 占位封面过滤；作者字段中的推广/评论噪音被清洗；"5/5" 归一化为 1.0
		assertNull(result[2].coverUrl)
		assertEquals(1f, result[2].rating, 0.001f)
		assertFalse(result[2].authors.any { it.contains("comments", ignoreCase = true) })
		assertFalse(result[2].authors.any { it.contains("Barnes", ignoreCase = true) })
		assertFalse(result[2].authors.any { it.contains("@") })
		assertTrue(result[2].authors.any { it.contains("Wittgenstein") })
	}

	@Test
	fun `parse details fixture`() {
		val content = content()
		val result = parser.parseDetails(fixture("book.html"), content)

		assertEquals("Henry Ford - The People's Carmaker (What's Their Story)", result.title)
		assertEquals(setOf("Haydn Middleton"), result.authors)
		assertTrue(result.description!!.contains("Publisher: Oxford University Press"))
		assertTrue(result.description!!.contains("Year: 1998"))
		assertEquals(setOf("Biography & Autobiography", "Historical"), result.tags.map { it.title }.toSet())
		val chapter = result.chapters!!.single()
		assertEquals("/dl/1000073/ebe77d?extension=epub&signature=d3mo5i78c#ext=epub", chapter.url)
		assertEquals("Download (EPUB)", chapter.title)
	}

	@Test
	fun `build list urls`() {
		// 无关键词的 /s/?filters 已被站点 302 到首页，浏览列表落在首页推荐流
		assertEquals(
			"https://z-library.sk/",
			parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter()),
		)
		assertTrue(parser.isHomeFeed(ContentListFilter()))
		val search = ContentListFilter(query = "Kant Kritik")
		val url = parser.buildListUrl(2, SortOrder.POPULARITY, search)
		assertTrue(url.startsWith("https://z-library.sk/s/Kant+Kritik?"))
		assertTrue(url.contains("order=popular"))
		assertTrue(url.endsWith("page=2"))

		val math = ContentTag("Mathematics", "cat:23", parser.source)
		val pdf = ContentTag("PDF", "fmt:PDF", parser.source)
		val zh = ContentTag("中文", "lang:chinese", parser.source)
		val catUrl = parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter(tags = setOf(math, pdf, zh)))
		assertTrue(catUrl.startsWith("https://z-library.sk/category/23/s/?"))
		assertTrue(catUrl.contains("extensions%5B%5D=PDF"))
		assertTrue(catUrl.contains("languages%5B%5D=chinese"))
		assertTrue(catUrl.contains("selected_content_types%5B%5D=book"))
	}

	@Test
	fun `search and category are not home feed`() {
		assertFalse(parser.isHomeFeed(ContentListFilter(query = "kant")))
		val math = ContentTag("Mathematics", "cat:23", parser.source)
		assertFalse(parser.isHomeFeed(ContentListFilter(tags = setOf(math))))
	}

	/** 首页形态：书名/作者/书号在 z-cover 属性上，链接只给 href（真机 2026-10 形态） */
	@Test
	fun `parse home feed fixture`() {
		val items = parser.parseHomeFeed(fixture("home.html"))
		// 第三个 z-cover 既无 title 也无链接文字，跳过
		assertEquals(2, items.size)
		assertEquals("The Love Hypothesis", items[0].title)
		assertEquals(setOf("Ali Hazelwood"), items[0].authors)
		assertEquals(
			"https://z-library.sk/book/5R0Am2Vrjp/the-love-hypothesis.html?dsource=mostpopular",
			items[0].publicUrl,
		)
		assertEquals("https://s3proxy.cdn-zlib.example/covers100/userbooks/love.jpg", items[0].coverUrl)
		assertEquals("Project Hail Mary", items[1].title)
		assertEquals(setOf("Andy Weir"), items[1].authors)
		assertNotEquals(items[0].id, items[1].id)
	}

	/** 首页是 SPA 外壳（无 z-bookcard）时按 /book/ 链接兜底，至少能出浏览列表 */
	@Test
	fun `home feed falls back to book links`() {
		val doc = Jsoup.parse(
			"""<html><head><title>Z-Library</title></head><body>
			<header><a href="/">Home</a><a href="/my">My Library</a></header>
			<main>
			<a href="/book/25126620/78b103/lost-souls.html"><img data-src="https://covers.example/a.jpg"></a>
			<a href="/book/25126621/1c2f0a/kant-kritik.html"><img alt="Kritik der reinen Vernunft" src="/c/2.jpg"></a>
			<a href="/book/25126621/9f9f9f/kant-kritik.html">Kritik der reinen Vernunft</a>
			<a href="/book/25126620/78b103/lost-souls.html">Lost Souls</a>
			</main></body></html>""".trimIndent(),
			"https://z-library.sk/",
		)
		val items = parser.parseHomeFeed(doc)
		assertEquals(2, items.size)
		// 封面链接先出现，标题后出现：标题要升级成真标题，顺序保持不变
		assertEquals("https://z-library.sk/book/25126620/78b103/lost-souls.html", items[0].publicUrl)
		assertEquals("Lost Souls", items[0].title)
		assertEquals("https://covers.example/a.jpg", items[0].coverUrl)
		assertEquals(
			"https://z-library.sk/book/25126621/1c2f0a/kant-kritik.html",
			items[1].publicUrl,
		)
		assertEquals("Kritik der reinen Vernunft", items[1].title)
		assertEquals("https://z-library.sk/c/2.jpg", items[1].coverUrl)
	}

	/** 推荐流语种靠 `siteLanguage` cookie：跟随系统取系统语言，指定语言取用户选择 */
	@Test
	fun `language cookie value`() {
		assertEquals("chinese", parser.siteLanguageForLocales(listOf(Locale.SIMPLIFIED_CHINESE)))
		assertEquals("russian", parser.siteLanguageForLocales(listOf(Locale("xx"), Locale("ru"))))
		assertEquals("", parser.siteLanguageForLocales(listOf(Locale("xx"))))
		// 默认跟随系统：mock 的系统语言是 en
		assertEquals("en", parser.acceptLanguageHeader())
		assertEquals("en", parser.languageCookieValue(null))
		assertEquals("en", parser.languageCookieValue("zh"))

		(parser.config as SourceConfigMock).set(parser.languageKey, "russian")
		// 站点现有值写的是 ISO 码，就继续写码
		assertEquals("ru", parser.languageCookieValue("zh"))
		// 站点现有值写的是语言名，就跟着写名
		assertEquals("russian", parser.languageCookieValue("chinese"))
		assertEquals("ru", parser.languageCookieValue(null))

		(parser.config as SourceConfigMock).set(parser.languageKey, "all")
		assertNull(parser.languageCookieValue("zh"))
	}

	@Test
	fun `language setting is registered`() {
		val keys = ArrayList<ConfigKey<*>>()
		parser.onCreateConfig(keys)
		assertTrue(keys.any { it is ConfigKey.PreferredLanguage && it.title == "推荐流语言" })
	}

	@Test
	fun `parse rating`() {
		assertEquals(0.88f, parser.parseRating("4.4"), 0.001f)
		assertEquals(1f, parser.parseRating("4.5/4.5"), 0.001f)
		assertEquals(RATING_UNKNOWN, parser.parseRating("0.0"))
		assertEquals(RATING_UNKNOWN, parser.parseRating(""))
		assertEquals(RATING_UNKNOWN, parser.parseRating(null))
		assertEquals(RATING_UNKNOWN, parser.parseRating("bad"))
	}

	/**
	 * 应用内浏览器拿本源 UA 去装载验证页，这里若返回伪造的桌面 client hints，
	 * WebView 会把它们原样带进顶层导航，与真实引擎指纹矛盾
	 */
	@Test
	fun `request headers stay browser consistent`() {
		val headers = parser.getRequestHeaders()

		assertEquals(ContentLoaderContextMock.getDefaultUserAgent(), headers["User-Agent"])
		assertNull(headers["sec-ch-ua"])
		assertNull(headers["sec-ch-ua-mobile"])
		assertNull(headers["sec-ch-ua-platform"])
		assertNull(headers["Sec-Fetch-Site"])
		assertNull(headers["Upgrade-Insecure-Requests"])
	}

	@Test
	fun `detect diamwall rejection statuses`() {
		assertTrue(parser.isDiamWallStatus(503))
		assertTrue(parser.isDiamWallStatus(513))
		assertTrue(parser.isDiamWallStatus(517))
		assertFalse(parser.isDiamWallStatus(200))
		assertFalse(parser.isDiamWallStatus(403))
		assertFalse(parser.isDiamWallStatus(404))
	}

	@Test
	fun `detect diamwall interstitial served with 200`() {
		assertTrue(parser.isDiamWallChallenge(fixture("diamwall-challenge.html")))
		assertFalse(parser.isDiamWallChallenge(fixture("search.html")))
		assertFalse(parser.isDiamWallChallenge(fixture("book.html")))
	}

	/** 正文里出现 DiamWall 字样（书名/简介）不算拦截 */
	@Test
	fun `content mentioning diamwall is not a challenge`() {
		val doc = Jsoup.parse(
			"""
			<html><head><title>Book</title></head><body>
			<z-bookcard id="1" href="/book/1/x.html"><div slot="title">DiamWall and me</div></z-bookcard>
			<p>c_token= and __diamwall are cookie names discussed in this book.</p>
			</body></html>
			""".trimIndent(),
			"https://z-library.sk/",
		)
		assertFalse(parser.isDiamWallChallenge(doc))
	}

	/** 注入脚本里的 URL 必须整体转义，否则带引号的地址会截断脚本 */
	@Test
	fun `browser fetch script escapes url`() {
		val script = parser.buildBrowserFetchScript(
			"https://z-library.sk/s/?q=a\"b\\c&page=2%20x",
		)
		assertFalse(script.contains("%URL%"))
		assertTrue(script.contains("""x.open('GET',"https://z-library.sk/s/?q=a\"b\\c&page=2%20x",false)"""))
		assertTrue(script.contains("x.send(null)"))
		assertTrue(script.contains("return [x.status,x.responseText]"))
		// 同步 XHR 设 timeout 会被浏览器抛 InvalidAccessError，脚本里绝不能出现
		assertFalse(script.contains("x.timeout"))
	}

	@Test
	fun `browser fetch script handles newlines`() {
		val script = parser.buildBrowserFetchScript("https://z-library.sk/a\nb")
		assertFalse(script.contains("a\nb"))
		assertTrue(script.contains("""a\nb"""))
	}

	@Test
	fun `explicit language detection`() {
		assertFalse(parser.isLanguageExplicitlySet())
		(parser.config as SourceConfigMock).set(parser.languageKey, "chinese")
		assertTrue(parser.isLanguageExplicitlySet())
		(parser.config as SourceConfigMock).set(parser.languageKey, "all")
		assertFalse(parser.isLanguageExplicitlySet())
	}

	/** 浏览器通道的 XHR 会带上语种 cookie，避免 WebView 与 jar 不一致 */
	@Test
	fun `browser fetch script sets language cookie`() {
		(parser.config as SourceConfigMock).set(parser.languageKey, "chinese")
		val script = parser.buildBrowserFetchScript("https://z-library.sk/")
		assertTrue(script.contains("""document.cookie="siteLanguage=zh;path=/;""""))

		(parser.config as SourceConfigMock).set(parser.languageKey, "all")
		assertFalse(parser.buildBrowserFetchScript("https://z-library.sk/").contains("document.cookie"))
	}

	/** 空列表诊断要能把"没有卡片"和"卡片结构变了"区分开 */
	@Test
	fun `describe empty list without cards`() {
		val doc = Jsoup.parse("<html><head><title>Z-Library</title></head><body>Home page</body></html>", "https://z-library.sk/")
		val message = parser.describeEmptyList(doc)
		assertTrue(message.contains("cards=0"))
		assertTrue(message.contains("mode=http"))
		assertTrue(message.contains("Z-Library"))
		assertTrue(message.contains("Home page"))
	}

	@Test
	fun `describe empty list with unparsable cards`() {
		val doc = Jsoup.parse(
			"""<html><head><title>Search</title></head><body>
			<z-bookcard id="1" href="/book/1/x.html"><span>title lives elsewhere now</span></z-bookcard>
			</body></html>""".trimIndent(),
			"https://z-library.sk/",
		)
		val message = parser.describeEmptyList(doc)
		assertTrue(message.contains("cards=1"))
		assertTrue(message.contains("first=<z-bookcard"))
	}

	private fun fixture(name: String): Document = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/zlibrary/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		"https://z-library.sk/",
	)

	private fun content() = Content(
		id = 1L,
		title = "Henry Ford - The People's Carmaker",
		altTitles = emptySet(),
		url = "/book/1000073/6755dd/henry-ford.html",
		publicUrl = "https://z-library.sk/book/1000073/6755dd/henry-ford.html",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = parser.source,
	)
}
