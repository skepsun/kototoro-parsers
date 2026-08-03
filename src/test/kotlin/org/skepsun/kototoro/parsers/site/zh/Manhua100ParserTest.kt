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
import java.util.Base64

class Manhua100ParserTest {

	private val parser = Manhua100Parser(ContentLoaderContextMock)

	@Test
	fun `build combined category paths in server order`() {
		val filter = ContentListFilter(
			tags = setOf(
				ContentTag("日本", "area/riben", parser.source),
				ContentTag("热血", "theme/rexue", parser.source),
			),
			states = setOf(ContentState.ONGOING),
		)

		assertEquals(
			"/category/area/riben/theme/rexue/state/lianzai/order/update/page/2",
			parser.buildListPath(2, SortOrder.UPDATED, filter),
		)
		assertEquals(
			"/category/order/views",
			parser.buildListPath(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
		)
	}

	@Test
	fun `parse list cards and discard navigation covers`() {
		val document = Jsoup.parse(
			"""
			<a class="lazy" href="/26459" title="一人之下漫画"
			   data-original="//cover.manhua100.com/cover/26459.webp"><span class="tit">一人之下</span></a>
			<a class="lazy" href="/static/app" title="应用" data-original="/app.webp"></a>
			""".trimIndent(),
			"https://www.manhua100.com/search?q=test",
		)

		val result = parser.parseList(document)

		assertEquals(1, result.size)
		assertEquals("一人之下", result.single().title)
		assertEquals("/26459", result.single().url)
		assertEquals("https://cover.manhua100.com/cover/26459.webp", result.single().coverUrl)
	}

	@Test
	fun `parse details metadata and preserve chapter order`() {
		val document = Jsoup.parse(
			"""
			<meta name="keywords" content="测试作品,测试作品漫画,测试作品全集">
			<div class="wrapper comic-detail">
			  <img class="comic-thumb" src="/cover.webp">
			  <h2 class="comic-name">测试作品</h2>
			  <div class="comic-info"><span class="info-attr">作者</span><p class="info-text">作者甲、作者乙</p></div>
			  <div class="comic-info"><span class="info-attr">状态</span><p class="info-text">连载中</p></div>
			  <div class="comic-info"><span class="info-attr">题材</span><p class="info-text">
			    <a href="/category/theme/rexue">热血</a><a href="/category/theme/xianzhiji">限制级</a>
			  </p></div>
			  <div class="comic-desc"><div class="info-text">作品简介</div></div>
			  <div class="comic-chapter"><a href="/123/1.html">1.开始</a><a href="/123/2.html">番外</a></div>
			</div>
			""".trimIndent(),
			"https://www.manhua100.com/123",
		)

		val result = parser.parseDetails(document, content())

		assertEquals(setOf("作者甲", "作者乙"), result.authors)
		assertEquals(ContentState.ONGOING, result.state)
		assertEquals(ContentRating.ADULT, result.contentRating)
		assertEquals("作品简介", result.description)
		assertEquals("https://www.manhua100.com/cover.webp", result.coverUrl)
		assertEquals(listOf("/123/1.html", "/123/2.html"), result.chapters?.map { it.url })
		assertEquals(listOf(1f, 2f), result.chapters?.map { it.number })
	}

	@Test
	fun `decrypt params and construct proxy images with referer`() {
		val document = Jsoup.parse(
			"""<script>var config = {}, params = '$ENCRYPTED_PARAMS';</script>""",
			"https://www.manhua100.com/123/1.html",
		)

		val pages = parser.parsePages(document, "https://www.manhua100.com/123/1.html")

		assertEquals(2, pages.size)
		val original = "https://origin.example/1.jpg"
		assertEquals(
			"https://two.mhpic.net/" + Base64.getEncoder().encodeToString(original.toByteArray()),
			pages.first().url,
		)
		assertTrue(pages.all { it.headers?.get("Referer") == "https://www.manhua100.com/123/1.html" })
	}

	@Test
	fun `reject invalid reader params`() {
		assertEquals(null, Manhua100ImageDecoder.decode("not-base64"))
	}

	private fun content() = Content(
		id = 1,
		title = "测试作品",
		altTitles = emptySet(),
		url = "/123",
		publicUrl = "https://www.manhua100.com/123",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = parser.source,
	)

	private companion object {
		private const val ENCRYPTED_PARAMS =
			"AAECAwQFBgcICQoLDA0ODwuZbjwJjf9Rn7nFjeq/u+o+jZJamNPFYCtPgAsvhywajuA+VtHhbtYmz2ogDMZGmQvnNgkk3QZ1nhusJedHBEH9aB3Yfd5kyfK7hgmPU0C1VmMNrPHbr094GIbuzIG02edp44xK4zLkLhEcaAtU2y6wnEL1ljOO2o/WoQytkA6PI91LH6N4mNgpsQuYY/hTqXVwJwW7DdNZVTbiPYBUZyA="
	}
}
