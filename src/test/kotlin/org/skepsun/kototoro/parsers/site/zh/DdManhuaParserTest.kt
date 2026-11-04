package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DdManhuaParserTest {

	private val parser = DdManhuaParser(ContentLoaderContextMock)

	private fun liuManHuaParser(): DdManhuaParser {
		val mirror = DdManhuaParser(ContentLoaderContextMock)
		(mirror.config as SourceConfigMock).set(mirror.configKeyDomain, "www.liumanhua.com")
		return mirror
	}

	@Test
	fun `expose browse filters but keep broken search disabled`() {
		assertFalse(parser.filterCapabilities.isSearchSupported)
		assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
	}

	@Test
	fun `offer both mirror domains`() {
		val presets = parser.configKeyDomain.presetValues
		assertEquals(listOf("ddmanhua.com", "www.liumanhua.com"), presets.toList())
	}

	@Test
	fun `build region sort and page paths`() {
		val filter = ContentListFilter(
			tags = setOf(ContentTag("国产漫画", "list/1", parser.source)),
		)

		assertEquals(
			"/category/list/1/order/addtime/page/2",
			parser.buildListPath(2, SortOrder.UPDATED, filter),
		)
		assertEquals(
			"/category/order/hits",
			parser.buildListPath(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
		)
		assertEquals(
			"/category/order/score",
			parser.buildListPath(1, SortOrder.RATING, ContentListFilter.EMPTY),
		)
		// 六漫画与滴答共用同一套分类/排序/分页路由
		assertEquals(
			"/category/list/1/order/addtime/page/2",
			liuManHuaParser().buildListPath(2, SortOrder.UPDATED, filter),
		)
	}

	@Test
	fun `resolve detail and chapter paths per mirror`() {
		assertEquals("/book/618076.html", parser.detailPath("/book/618076"))
		assertEquals("/618076", liuManHuaParser().detailPath("/book/618076"))
		// 兼容历史保存的地址
		assertEquals("/book/618076.html", parser.detailPath("/book/618076.html"))
		assertEquals("/618076", liuManHuaParser().detailPath("/618076"))

		assertEquals("/chapter/618076-224176.html", parser.chapterPath("/book/618076/224176"))
		assertEquals("/618076/224176.html", liuManHuaParser().chapterPath("/book/618076/224176"))
		// 兼容两站原始章节地址
		assertEquals("/618076/224176.html", liuManHuaParser().chapterPath("/chapter/618076-224176.html"))
		assertEquals("/chapter/618076-224176.html", parser.chapterPath("/618076/224176.html"))
	}

	@Test
	fun `parse list cards with state rating and cover`() {
		val document = Jsoup.parse(
			"""
			<div class="lists-content"><ul><li>
			  <a class="vodlist__thumb" href="/book/260264.html" title="一人之下"
			     data-original="//cover.example/1.jpg">
			    <div class="note">新766 到访</div>
			    <div class="countrie"><span>状态:</span><span>连载中</span></div>
			  </a>
			  <footer><span class="rate">9.5</span></footer>
			</li></ul></div>
			""".trimIndent(),
			"http://ddmanhua.com/category",
		)

		val result = parser.parseList(document).single()

		assertEquals("一人之下", result.title)
		assertEquals("/book/260264", result.url)
		assertEquals("http://ddmanhua.com/book/260264.html", result.publicUrl)
		assertEquals("http://cover.example/1.jpg", result.coverUrl)
		assertEquals(0.95f, result.rating)
		assertEquals(ContentState.ONGOING, result.state)
	}

	@Test
	fun `parse liumanhua list cards into the same canonical ids`() {
		val document = Jsoup.parse(
			"""
			<div class="cy_list_mh"><ul>
			  <li><a href="/233095" class="pic"><img src="https://static-tw.example/cover/1.jpg"></a></li>
			  <li class="title"><a href="/233095">不健全关系</a></li>
			  <li class="zuozhe">状态：连载中</li>
			  <li class="updata">最新：<a href="/233095/52219.html"><span>天启篇·11</span></a></li>
			  <li class="biaoqian">标签：<a href="/category/list/1/tags/2634">剧情</a>
			    <a href="/category/list/1/tags/2600">悬疑</a></li>
			  <li class="info">简介：脾气火爆的刑侦队长钱错。</li>
			</ul></div>
			""".trimIndent(),
			"https://www.liumanhua.com/category/list/1",
		)

		val result = liuManHuaParser().parseList(document).single()

		assertEquals("不健全关系", result.title)
		assertEquals("/book/233095", result.url)
		assertEquals("https://www.liumanhua.com/233095", result.publicUrl)
		assertEquals("https://static-tw.example/cover/1.jpg", result.coverUrl)
		assertEquals(ContentState.ONGOING, result.state)
		assertEquals(setOf("2634", "2600"), result.tags.map { it.key.substringAfter('/') }.toSet())
		assertEquals(ContentRating.SAFE, result.contentRating)
	}

	@Test
	fun `parse detail fields and preserve irregular chapter order`() {
		val document = Jsoup.parse(
			"""
			<header class="product-header">
			  <img class="thumb" src="/cover.jpg">
			  <h1 class="product-title">测试漫画 <span class="rate">9.9</span></h1>
			  <div class="product-excerpt">作者：<span>作者甲、作者乙</span></div>
			  <div class="product-excerpt">状态：<span>已完结</span></div>
			  <div class="product-excerpt">类型：<span><a href="/category/tags/1">科幻</a></span></div>
			  <div class="product-excerpt">漫画简介：<span>作品简介</span></div>
			</header>
			<div class="playlist"><a href="/chapter/1-10.html">第76话</a><a href="/chapter/1-11.html">第600话</a><a href="/chapter/1-12.html">番外</a></div>
			""".trimIndent(),
			"http://ddmanhua.com/book/1.html",
		)

		val result = parser.parseDetails(document, content())

		assertEquals("测试漫画", result.title)
		assertEquals(setOf("作者甲", "作者乙"), result.authors)
		assertEquals(ContentState.FINISHED, result.state)
		assertEquals("作品简介", result.description)
		assertEquals(ContentRating.SAFE, result.contentRating)
		assertEquals(listOf(76f, 600f, 0f), result.chapters?.map { it.number })
		assertEquals(
			listOf("/book/1/10", "/book/1/11", "/book/1/12"),
			result.chapters?.map { it.url },
		)
	}

	@Test
	fun `parse liumanhua details and chapters`() {
		val document = Jsoup.parse(
			"""
			<div class="cy_info">
			  <div class="cy_info_cover"><a href="/618076"><img class="pic" src="https://static-tw.example/cover/2.jpg"></a></div>
			  <div class="cy_title"><h1>神奇蜘蛛侠v1</h1></div>
			  <div class="cy_xinxi">
			    <span>作者：<a href="/search?key=Marvel+Comics">Marvel Comics</a></span>
			    <span>状态：<font>连载中</font></span>
			  </div>
			  <div class="cy_xinxi">
			    <span>类别：<a href="/category/tags/2589">科幻</a></span>
			    <span>评分：<font>9.9</font></span>
			  </div>
			  <div class="cy_xinxi cy_desc"><p id="comic-description">漫画神奇蜘蛛侠v1。</p></div>
			</div>
			<div class="cy_kan"><a href="/618076/224176.html">在线阅读</a></div>
			<ul class="chapter__list">
			  <li class="chapter__item"><a href="/618076/225137.html"><p>第507话</p></a></li>
			  <li class="chapter__item"><a href="/618076/225136.html"><p>年刊2000</p></a></li>
			</ul>
			""".trimIndent(),
			"https://www.liumanhua.com/618076",
		)

		val result = liuManHuaParser().parseDetails(document, content("/book/618076"))

		assertEquals("神奇蜘蛛侠v1", result.title)
		assertEquals("https://static-tw.example/cover/2.jpg", result.coverUrl)
		assertEquals(setOf("Marvel Comics"), result.authors)
		assertEquals(ContentState.ONGOING, result.state)
		assertEquals("漫画神奇蜘蛛侠v1。", result.description)
		assertEquals(0.99f, result.rating, 0.001f)
		assertEquals(listOf(507f, 0f), result.chapters?.map { it.number })
		assertEquals(listOf("/book/618076/225137", "/book/618076/225136"), result.chapters?.map { it.url })
	}

	@Test
	fun `decode direct and encrypted image page variants`() {
		val direct = Jsoup.parse(
			"""<script>params = '$DIRECT_PARAMS';</script>""",
			"http://ddmanhua.com/chapter/1-1.html",
		)
		val encrypted = Jsoup.parse(
			"""<script>params = '$ENCRYPTED_PARAMS';</script>""",
			"http://ddmanhua.com/chapter/1-2.html",
		)

		val directPages = parser.parsePages(direct, "http://ddmanhua.com/chapter/1-1.html")
		val encryptedPages = parser.parsePages(encrypted, "http://ddmanhua.com/chapter/1-2.html")

		assertEquals(listOf("https://img.example/1.jpg", "https://img.example/2.png"), directPages.map { it.url })
		assertEquals("https://img1.baipiaoguai.org/comic/1.bin#dd-aes", encryptedPages.single().url)
	}

	@Test
	fun `decode liumanhua chapter payload through the same protocol`() {
		val mirror = liuManHuaParser()
		val payload = encryptParams(
			"""{"host":"www.liumanhua.com","source_id":"6","comic_id":"618076","chapter_id":"224176",""" +
				""""images":["https://s2.bzcdn.net/scomic/1.jpg","//s2.bzcdn.net/scomic/2.jpg"]}""",
		)
		val document = Jsoup.parse(
			"""<script>params = '$payload';</script>""",
			"https://www.liumanhua.com/618076/224176.html",
		)

		val pages = mirror.parsePages(document, "https://www.liumanhua.com/618076/224176.html")

		assertEquals(
			listOf("https://s2.bzcdn.net/scomic/1.jpg", "https://s2.bzcdn.net/scomic/2.jpg"),
			pages.map { it.url },
		)
	}

	@Test
	fun `reject page payload that belongs to another mirror`() {
		val payload = encryptParams(
			"""{"host":"ddmanhua.com","source_id":"6","images":["https://img.example/1.jpg"]}""",
		)
		val document = Jsoup.parse(
			"""<script>params = '$payload';</script>""",
			"https://www.liumanhua.com/618076/224176.html",
		)

		assertTrue(liuManHuaParser().parsePages(document, "https://www.liumanhua.com/618076/224176.html").isEmpty())
	}

	@Test
	fun `decrypt source 12 image bytes and identify image type`() {
		val encrypted = Base64.getDecoder().decode(ENCRYPTED_IMAGE)
		val decrypted = DdManhuaParser.decryptImage(encrypted) ?: error("image decryption failed")

		assertTrue(decrypted.take(3).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
		assertEquals("image/jpeg", DdManhuaParser.detectImageMediaType(decrypted))
	}

	private fun content(url: String = "/book/1") = Content(
		id = 1,
		title = "测试漫画",
		altTitles = emptySet(),
		url = url,
		publicUrl = "http://ddmanhua.com/book/1.html",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = parser.source,
	)

	/** 用与站点相同的协议生成测试载荷，避免把线上抓包内容写进仓库。 */
	private fun encryptParams(json: String): String {
		val key = "9S8\$vJnU2ANeSRoF".toByteArray(Charsets.UTF_8)
		val iv = ByteArray(16) { it.toByte() }
		val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
		cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
		return Base64.getEncoder().encodeToString(iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
	}

	private companion object {
		private const val DIRECT_PARAMS =
			"AAECAwQFBgcICQoLDA0OD8uFHQYx9qfPe3XPvYxwmgHHpR+Cuz5vFsFM/ChO/Klq3t3y2kpwvj16+WmlOSV8YB+IQRaha0ax63t6bqNa5rB3RUgo8qTHPucw4Ub1jch+V+akFs2tefvlNcwNOA1j8STSYT1ZDjeByySa67Mu4+U="
		private const val ENCRYPTED_PARAMS =
			"AAECAwQFBgcICQoLDA0OD8uFHQYx9qfPe3XPvYxwmgHHpR+Cuz5vFsFM/ChO/KlqZSLqtwTKjXs82C0ZBlo3dTLw5hV1X87nXFxlhET/ZU9QXDX+t0BLktwei22gSuR9"
		private const val ENCRYPTED_IMAGE = "dlIVRPYYg9TBwNLRonqtbg=="
	}
}
