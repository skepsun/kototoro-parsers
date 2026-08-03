package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Base64

class DdManhuaParserTest {

	private val parser = DdManhuaParser(ContentLoaderContextMock)

	@Test
	fun `expose browse filters but keep broken search disabled`() {
		assertFalse(parser.filterCapabilities.isSearchSupported)
		assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
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
		assertEquals("http://ddmanhua.com/book/260264.html", result.publicUrl)
		assertEquals("http://cover.example/1.jpg", result.coverUrl)
		assertEquals(0.95f, result.rating)
		assertEquals(ContentState.ONGOING, result.state)
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
			listOf("/chapter/1-10.html", "/chapter/1-11.html", "/chapter/1-12.html"),
			result.chapters?.map { it.url },
		)
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
	fun `decrypt source 12 image bytes and identify image type`() {
		val encrypted = Base64.getDecoder().decode(ENCRYPTED_IMAGE)
		val decrypted = DdManhuaParser.decryptImage(encrypted) ?: error("image decryption failed")

		assertTrue(decrypted.take(3).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
		assertEquals("image/jpeg", DdManhuaParser.detectImageMediaType(decrypted))
	}

	private fun content() = Content(
		id = 1,
		title = "测试漫画",
		altTitles = emptySet(),
		url = "/book/1.html",
		publicUrl = "http://ddmanhua.com/book/1.html",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = parser.source,
	)

	private companion object {
		private const val DIRECT_PARAMS =
			"AAECAwQFBgcICQoLDA0OD8uFHQYx9qfPe3XPvYxwmgHHpR+Cuz5vFsFM/ChO/Klq3t3y2kpwvj16+WmlOSV8YB+IQRaha0ax63t6bqNa5rB3RUgo8qTHPucw4Ub1jch+V+akFs2tefvlNcwNOA1j8STSYT1ZDjeByySa67Mu4+U="
		private const val ENCRYPTED_PARAMS =
			"AAECAwQFBgcICQoLDA0OD8uFHQYx9qfPe3XPvYxwmgHHpR+Cuz5vFsFM/ChO/KlqZSLqtwTKjXs82C0ZBlo3dTLw5hV1X87nXFxlhET/ZU9QXDX+t0BLktwei22gSuR9"
		private const val ENCRYPTED_IMAGE = "dlIVRPYYg9TBwNLRonqtbg=="
	}
}
