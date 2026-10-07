package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Request
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Base64

class Manhua100ParserTest {

	private val parser = Manhua100Parser(OfflineContentLoaderContext())

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

	@ParameterizedTest
	@ValueSource(ints = [200, 403, 404])
	fun `fall back to origin only when the image proxy returns 404`(status: Int) {
		val origin = "https://origin.example/1.jpg"
		val proxyUrl = "https://two.mhpic.net/" + Base64.getEncoder().encodeToString(origin.toByteArray())
		val ctx = OfflineContentLoaderContext(
			fixtures = mapOf(proxyUrl to "manhua100/proxy-error.txt", origin to "manhua100/image-body.txt"),
			responseCodes = mapOf(proxyUrl to status),
			responseTypes = mapOf(origin to "image/webp"),
		) { request, body ->
			assertEquals(null, request.header("X-Kototoro-Manhua100-Origin"))
			body
		}
		val subject = Manhua100Parser(ctx)
		val document = Jsoup.parse("""<script>var params = '$ENCRYPTED_PARAMS';</script>""")
		val page = Manhua100Parser(ctx).parsePages(document, "https://www.manhua100.com/123/1.html").first()
		var proxyBodyClosed = false
		val client = ctx.httpClient.newBuilder().apply {
			interceptors().add(0, subject)
			interceptors().add(1) { chain ->
				if (chain.request().url.toString() == origin) assertTrue(proxyBodyClosed)
				val response = chain.proceed(chain.request())
				if (chain.request().url.toString() != proxyUrl) return@add response
				val body = response.body
				response.newBuilder().body(object : ResponseBody() {
					private val trackedSource = object : ForwardingSource(body.source()) {
						override fun close() {
							proxyBodyClosed = true
							super.close()
						}
					}.buffer()
					override fun contentType() = body.contentType()
					override fun contentLength() = body.contentLength()
					override fun source() = trackedSource
				}).build()
			}
		}.build()
		val request = Request.Builder().url(proxyUrl).apply {
			page.headers.orEmpty().forEach { (key, value) -> header(key, value) }
		}.build()
		client.newCall(request).execute().use { response ->
			assertEquals(if (status == 404) origin else proxyUrl, response.request.url.toString())
			assertEquals(if (status == 404) 200 else status, response.code)
		}
		assertEquals(if (status == 404) listOf(proxyUrl, origin) else listOf(proxyUrl), ctx.requests)
	}

	@ParameterizedTest
	@ValueSource(ints = [200, 404])
	fun `preserve proxy error when the original response is not an image`(status: Int) {
		val origin = "https://origin.example/1.jpg"
		val proxyUrl = "https://two.mhpic.net/" + Base64.getEncoder().encodeToString(origin.toByteArray())
		val ctx = OfflineContentLoaderContext(
			fixtures = mapOf(proxyUrl to "manhua100/proxy-error.txt", origin to "manhua100/proxy-error.txt"),
			responseCodes = mapOf(proxyUrl to 404, origin to status),
		)
		val subject = Manhua100Parser(ctx)
		val document = Jsoup.parse("""<script>var params = '$ENCRYPTED_PARAMS';</script>""")
		val page = Manhua100Parser(ctx).parsePages(document, "https://www.manhua100.com/123/1.html").first()
		val client = ctx.httpClient.newBuilder().apply { interceptors().add(0, subject) }.build()
		val request = Request.Builder().url(proxyUrl).apply {
			page.headers.orEmpty().forEach { (key, value) -> header(key, value) }
		}.build()
		client.newCall(request).execute().use { response ->
			assertEquals(404, response.code)
			assertEquals("error: 404", response.body.string().trim())
		}
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
