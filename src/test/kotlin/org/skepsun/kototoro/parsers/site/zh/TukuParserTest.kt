package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
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

class TukuParserTest {

	private val parser = TukuParser(ContentLoaderContextMock)

	@Test
	fun `parse search cards and normalize image URLs`() {
		val document = Jsoup.parse(
			"""
			<div class="swiper-card-item">
			  <a href="/manga-70923/" title="海贼王">
			    <img data-original="//cover5.tuku.cc/cover.jpg">
			  </a>
			  <a href="/manga-70923/" title="海贼王">海贼王</a>
			  <p class="card-item-state">最新</p>
			  <a href="/comics-tag1/"><p>热血</p></a>
			</div>
			""".trimIndent(),
			"https://www.tuku.cc/search?title=%E6%B5%B7%E8%B4%BC%E7%8E%8B",
		)

		val result = parser.parseList(document)

		assertEquals(1, result.size)
		assertEquals("海贼王", result.single().title)
		assertEquals("/manga-70923/", result.single().url)
		assertEquals("https://cover5.tuku.cc/cover.jpg", result.single().coverUrl)
		assertEquals(ContentState.ONGOING, result.single().state)
		assertEquals(setOf("tag1"), result.single().tags.mapTo(mutableSetOf()) { it.key })
	}

	@Test
	fun `build combined filter and sort paths in server order`() {
		val filter = ContentListFilter(
			tags = setOf(
				ContentTag("热血", "tag1", parser.source),
				ContentTag("日本", "region2", parser.source),
			),
			states = setOf(ContentState.ONGOING),
		)

		assertEquals(
			"/comics-region2-tag1-status1-order2-p2/",
			parser.buildListPath(2, SortOrder.UPDATED, filter),
		)
		assertEquals("/comics/", parser.buildListPath(1, SortOrder.POPULARITY, ContentListFilter.EMPTY))
		assertEquals("/comics-order18/", parser.buildListPath(1, SortOrder.NEWEST, ContentListFilter.EMPTY))
	}

	@Test
	fun `parse detail metadata and adult rating`() {
		val document = Jsoup.parse(
			"""
			<meta name="keywords" content="测试漫画,Test Manga,测试漫画全集">
			<div class="manga-info-card">
			  <a class="manga-cover"><img src="/cover.jpg"></a>
			  <h1>测试漫画</h1>
			  <p><span>作者：</span><a>作者甲</a><a>作者乙</a></p>
			  <p><span>状态：</span>连载中</p>
			  <a href="/comics-tag35/">限制级</a>
			  <p class="multi-ellipsis">作品简介</p>
			</div>
			<div class="manga-chapter-wrap"><a href="/chapter1/">第1话 (12p)</a></div>
			""".trimIndent(),
			"https://www.tuku.cc/manga-1/",
		)

		val details = parser.parseDetails(document, content())

		assertEquals(setOf("Test Manga"), details.altTitles)
		assertEquals(setOf("作者甲", "作者乙"), details.authors)
		assertEquals(ContentState.ONGOING, details.state)
		assertEquals(ContentRating.ADULT, details.contentRating)
		assertEquals("作品简介", details.description)
		assertEquals("https://www.tuku.cc/cover.jpg", details.coverUrl)
		assertEquals(1, details.chapters?.size)
	}

	@Test
	fun `preserve chapter order and parse only explicit numbers`() {
		val manga = content()
		val document = Jsoup.parse(
			"""
			<div class="manga-chapter-wrap">
			  <a href="/chapter1/">第1话 开始 (36p)</a>
			  <a href="/chapter2/">第1.5话 中场</a>
			  <a href="/chapter3/">第01卷</a>
			  <a href="/chapter4/">番外</a>
			  <a href="/chapter5/">序章</a>
			  <a href="/chapter6/">上篇</a>
			  <a href="/chapter7/">下篇</a>
			  <a href="/chapter8/">人物介绍</a>
			</div>
			""".trimIndent(),
			"https://www.tuku.cc/manga-1/",
		)

		val chapters = parser.parseChapters(document, manga)

		assertEquals((1..8).map { "/chapter$it/" }, chapters.map { it.url })
		assertEquals(listOf(1f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f), chapters.map { it.number })
		assertEquals(1, chapters[2].volume)
		assertEquals(36, TukuParser.parseExpectedPageCount(chapters.first().title))
	}

	@Test
	fun `parse reader images with chapter referer`() {
		val document = Jsoup.parse(
			"""
			<div class="read-doc-center">
			  <img data-original="https://image1.tuku.cc/1.jpg?key=abc">
			  <img data-original="/images/2.png">
			</div>
			""".trimIndent(),
			"https://www.tuku.cc/chapter1/",
		)

		val pages = parser.parsePages(document, "https://www.tuku.cc/chapter1/")

		assertEquals(2, pages.size)
		assertEquals("https://www.tuku.cc/images/2.png", pages.last().url)
		assertTrue(pages.all { it.headers?.get("Referer") == "https://www.tuku.cc/chapter1/" })
	}

	private fun content() = Content(
		id = 1,
		title = "测试漫画",
		altTitles = emptySet(),
		url = "/manga-1/",
		publicUrl = "https://www.tuku.cc/manga-1/",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = parser.source,
	)
}
