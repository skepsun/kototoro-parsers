package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class TencentComicsParserTest {

	private val parser = TencentComicsParser(
		OfflineContentLoaderContext(
			mapOf(
				"https://ac.qq.com/Comic/all/search/hot/page/1" to "tencentcomics/list.html",
				"https://ac.qq.com/Comic/all/theme/105/finish/1/search/time/page/2" to "tencentcomics/list.html",
				"https://ac.qq.com/Comic/searchList/search/%E4%B8%80%E4%BA%BA%E4%B9%8B%E4%B8%8B/page/1" to
					"tencentcomics/search.html",
				"https://ac.qq.com/Comic/comicInfo/id/531490" to "tencentcomics/detail.html",
				"https://ac.qq.com/ComicView/index/id/531490/cid/2" to "tencentcomics/reader.html",
				"https://ac.qq.com/ComicView/index/id/531490/cid/3" to "tencentcomics/reader_alt_nonce.html",
				"https://ac.qq.com/ComicView/index/id/531490/cid/13" to "tencentcomics/reader_mixed_nonce.html",
				"https://ac.qq.com/ComicView/index/id/531490/cid/155430" to "tencentcomics/reader_locked.html",
			),
		),
	)

	private fun sampleContent() = Content(
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

	@Test
	fun testNonceExpressionEvaluator() {
		val expression = "\"62264\" + (+eval(\"!!1+!1+!!2+!!2+1\")).toString() + \"7f\" + (+eval(\"6\")).toString() + " +
			"\"f5\" + (+eval(\"Math.pow(1,3)+1+1\")).toString() + \"81c8e\" + (+eval(\"Math.pow(1,3)+1+1\")).toString() + " +
			"\"baa079\" + (+eval(\"1/2 + 5/2\")).toString() + \"c091cc9\""
		assertEquals("6226447f6f5381c8e3baa0793c091cc9", TencentNonceEvaluator.evaluate(expression))
	}

	/**
	 * 站点每次请求都会随机换一种 nonce 写法，这里覆盖实测抓到的各种片段。
	 */
	@Test
	fun testNonceExpressionVariants() {
		fun evaluate(expression: String) = TencentNonceEvaluator.evaluate(expression)

		// 纯字面量
		assertEquals(
			"091cb0fc605cd542e0c6454a52a0d79c",
			evaluate("'' + '091cb0fc605cd542e0c6454a52a0d79c'"),
		)
		// 字符串 + 数字 + 字符串，数字由 double 引号里的算术得到
		assertEquals(
			"ab622e1f42e0827cc02eb81e9c1efec9",
			evaluate("\"ab622e1f42e0827cc0\" + (+eval(\"2\")).toString() + \"eb81e9c1efec9\""),
		)
		assertEquals(
			"cb9afcfc03fb9ad9e4d6ac060b6768cb",
			evaluate("\"cb9afcfc\" + (+eval(\"9 * 0\")).toString() + \"3fb9ad9e4d6ac060b6768cb\""),
		)
		// 但 eval 的实参本身可以是单引号 + 字符串方法，此时上面的正则写法会失效
		assertEquals("4", evaluate("(+eval('1'.charCodeAt() - 45)).toString()"))
		assertEquals("3", evaluate("(+eval('123'.substring(2))).toString()"))
		// 三元、位运算、双重取反、DOM 探测（一律按真值对象处理）
		assertEquals("2", evaluate("(+eval(\"0<=3?2:1\")).toString()"))
		assertEquals("5", evaluate("(+eval(\"5&5\")).toString()"))
		assertEquals("4", evaluate("(+eval(\"~~1.3 + 3\")).toString()"))
		assertEquals("0", evaluate("(+eval(\"1 - !!document.children\")).toString()"))
		assertEquals("1", evaluate("(+eval(\"1 * (!window.Array) + 1\")).toString()"))
		assertEquals("1", evaluate("(+eval(\"!!document.getElementsByTagName('html')\")).toString()"))
		assertEquals("6226447f", evaluate("\"62264\" + (+eval(\"4 + !!!1\")).toString() + \"7f\""))
		// parseInt/parseFloat（实测出现 parseInt(7/3)）
		assertEquals("2", evaluate("(+eval(\"parseInt(7/3)\")).toString()"))
		assertEquals("1.5", evaluate("(+eval(\"parseFloat('1.5')\")).toString()"))
		assertEquals("255", evaluate("(+eval(\"parseInt('ff', 16)\")).toString()"))
	}

	@Test
	fun testNonceExpressionRejectsGarbage() {
		assertThrows<IllegalArgumentException> { TencentNonceEvaluator.evaluate("") }
		assertThrows<IllegalArgumentException> { TencentNonceEvaluator.evaluate("\"abc") }
		assertThrows<IllegalArgumentException> { TencentNonceEvaluator.evaluate("\"1\" +") }
		assertThrows<IllegalArgumentException> { TencentNonceEvaluator.evaluate("\"1\" \"2\"") }
	}

	@Test
	fun testListPage() = runBlocking {
		val list = parser.getList(0, SortOrder.POPULARITY, ContentListFilter())
		assertEquals(12, list.size)
		val first = list.first()
		assertEquals("开局签到荒古圣体", first.title)
		assertEquals("654027", first.url)
		assertEquals("https://ac.qq.com/Comic/comicInfo/id/654027", first.publicUrl)
		assertEquals(
			"https://manhua.acimg.cn/vertical/0/10_10_24_490373e5a853d180b19f10b5ffdf8d54_1691634245056.jpg/420",
			first.coverUrl,
		)
		assertEquals("星空社", first.authors.single())
		assertEquals(setOf("重生", "系统"), first.tags.map { it.title }.toSet())
		assertEquals(RATING_UNKNOWN, first.rating)
		assertNotNull(first.description)
		// 同一 uid 不能重复出现
		assertEquals(list.size, list.map { it.id }.distinct().size)
	}

	@Test
	fun testListUrlWithFilters() {
		fun tag(title: String, key: String) = ContentTag(title, key, parser.source)
		val url = parser.buildListUrl(
			page = 2,
			order = SortOrder.UPDATED,
			filter = ContentListFilter(
				tags = setOf(tag("恋爱", "theme/105"), tag("连载中", "finish/1")),
			),
		)
		assertEquals("https://ac.qq.com/Comic/all/theme/105/finish/1/search/time/page/2", url)
		assertEquals(
			"https://ac.qq.com/Comic/all/search/hot/page/1",
			parser.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter()),
		)
		assertEquals(
			"https://ac.qq.com/Comic/searchList/search/%E4%B8%80%E4%BA%BA%E4%B9%8B%E4%B8%8B/page/1",
			parser.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter(query = "一人之下")),
		)
	}

	@Test
	fun testSearch() = runBlocking {
		val list = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "一人之下"))
		assertEquals(22, list.size)
		val first = list.first()
		assertEquals("一人之下", first.title)
		assertEquals("531490", first.url)
		assertNotNull(first.coverUrl)
	}

	@Test
	fun testDetails() = runBlocking {
		val details = parser.getDetails(sampleContent())
		assertEquals("一人之下", details.title)
		assertEquals("米橙子 x 米二", details.authors.single())
		assertEquals(ContentState.ONGOING, details.state)
		assertTrue(requireNotNull(details.description).startsWith("【一人之下6定档"))
		assertEquals(
			"https://manhua.acimg.cn/vertical/0/18_17_39_ea5671977fc690a42459210737b4c67a_1592473175770.jpg/420",
			details.coverUrl,
		)
		val chapters = requireNotNull(details.chapters)
		assertEquals(7, chapters.size)
		assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), chapters.map { it.number })
		assertEquals("/ComicView/index/id/531490/cid/2", chapters[1].url)
		// 站点按卷/回混合命名，标题原样保留；付费章加锁标记
		assertEquals("2.姐姐2", chapters[1].title)
		assertNull(chapters[1].branch)
		assertEquals("\uD83D\uDD12 781 同门", chapters[5].title)
		assertEquals("/ComicView/index/id/531490/cid/155430", chapters[5].url)
	}

	@Test
	fun testPages() = runBlocking {
		val details = parser.getDetails(sampleContent())
		val chapter = requireNotNull(details.chapters)[1]
		val pages = parser.getPages(chapter)
		assertEquals(17, pages.size)
		val first = pages.first()
		assertEquals(
			"https://manhua.acimg.cn/manhua_detail/0/09_19_26_5bfef9d1ebf9f23268ba9038ae36ae1f_1733743607854.jpg/0",
			first.url,
		)
		assertEquals("https://ac.qq.com/", first.headers?.get("Referer"))
		assertEquals(first.url, parser.getPageUrl(first))
		assertEquals(pages.size, pages.map { it.id }.distinct().size)
	}

	/**
	 * 阅读页里可能同时出现多个 `window[...] = ...`，最后一个可能是纯诱饵。候选必须从后往前逐个试，
	 * 直到解出合法 JSON；这份夹具的最后一个候选是假的，正确的那个用了单引号 + 字符串方法。
	 */
	@Test
	fun testReaderPayloadFallsBackToWorkingCandidate() {
		val html = requireNotNull(javaClass.classLoader?.getResource("fixtures/tencentcomics/reader_alt_nonce.html")) {
			"缺少夹具 reader_alt_nonce.html"
		}.readText()
		val payload = parser.decodeReaderPayload(html, "https://ac.qq.com/ComicView/index/id/531490/cid/3")
		assertEquals(17, payload.getJSONArray("picture").length())
		assertEquals("2.姐姐2", payload.getJSONObject("chapter").getString("cTitle"))
	}

	@Test
	fun testLockedChapterIsRejected() = runBlocking {
		val details = parser.getDetails(sampleContent())
		val locked = requireNotNull(details.chapters)[5]
		val error = assertThrows<ParseException> {
			runBlocking { parser.getPages(locked) }
		}
		assertTrue(requireNotNull(error.message).contains("付费"))
	}

	@Test
	fun testReaderPayloadWithoutNonceFails() {
		val error = assertThrows<ParseException> {
			parser.decodeReaderPayload("<html><body>no data</body></html>", "https://ac.qq.com/x")
		}
		assertTrue(requireNotNull(error.message).contains("章节数据"))
	}

	/**
	 * 真机抓到的表达式：`Math.round(.5)` 必须按 JS 语义得到 1（Kotlin 的 round 会得到 0）。
	 */
	@Test
	fun testNonceRoundFollowsJsSemantics() {
		assertEquals("1", TencentNonceEvaluator.evaluate("(+eval(\"Math.round(.5)\")).toString()"))
		assertEquals("2", TencentNonceEvaluator.evaluate("(+eval(\"Math.round(.5) + ~~1.7\")).toString()"))
		assertEquals("0", TencentNonceEvaluator.evaluate("(+eval(\"Math.round(-.5)\")).toString()"))
		assertEquals(
			"540baa9e96d0795b0602723eca784b45",
			TencentNonceEvaluator.evaluate(
				"\"5\" + (+eval(\"2 + 2\")).toString() + \"0baa9e96d\" + (+eval(\"9 * 0\")).toString() + " +
					"\"795b06027\" + (+eval(\"Math.round(.5) + ~~1.7\")).toString() + \"3eca784b4\" + " +
					"(+eval(\"5-1+1\")).toString() + \"\"",
			),
		)
	}

	/**
	 * 夹具来自一次失败的真机响应：非直观写法混用了 `~~`、`Math.round(.5)` 与空串拼接。
	 * 若 `Math.round` 语义或 token 剥离顺序错了，这里会解码失败。
	 */
	@Test
	fun testReaderPayloadWithMixedNonceExpression() = runBlocking {
		val chapter = org.skepsun.kototoro.parsers.model.ContentChapter(
			id = 1L,
			title = "13.凶暴",
			number = 13f,
			volume = 0,
			url = "/ComicView/index/id/531490/cid/13",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = parser.source,
		)
		val pages = parser.getPages(chapter)
		assertEquals(11, pages.size)
		assertEquals(
			"https://manhua.acimg.cn/manhua_detail/0/09_19_39_bc7c03732513b9fc1bab1ab3f3a1b615_1733744347335.jpg/0",
			pages.first().url,
		)
	}
}
