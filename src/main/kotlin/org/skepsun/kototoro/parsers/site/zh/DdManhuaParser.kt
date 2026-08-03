@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import java.util.EnumSet

/**
 * 滴答漫画（ddmanhua.com）。
 *
 * 站点 HTTPS 会重定向至 HTTP。搜索入口当前始终返回空结果，因此仅提供分类浏览。
 */
@ContentSourceParser("DDMANHUA", "滴答漫画", "zh")
internal class DdManhuaParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.DDMANHUA, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("ddmanhua.com")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.RATING)

	private val regionTags by lazy {
		REGIONS.map { (title, key) -> ContentTag(title, key, source) }
	}

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isMultipleTagsSupported = false)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = regionTags.toSet(),
		tagGroups = listOf(ContentTagGroup("地区", regionTags.toSet())),
		availableContentRating = EnumSet.allOf(ContentRating::class.java),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "${baseUrl()}/")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val response = webClient.httpGet(baseUrl() + buildListPath(page, order, filter), getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parseList(response.parseHtml())
	}

	internal fun buildListPath(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
		append("/category")
		filter.tags.firstOrNull { it.key.startsWith("list/") }?.let { append('/').append(it.key) }
		append("/order/").append(
			when (order) {
				SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "addtime"
				SortOrder.RATING, SortOrder.RATING_ASC -> "score"
				else -> "hits"
			},
		)
		if (page > 1) append("/page/").append(page)
	}

	internal fun parseList(document: Document): List<Content> =
		document.select(".lists-content a.vodlist__thumb[href^=/book/]").mapNotNull { anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.attr("title").trim()
			if (!BOOK_PATH.matches(href) || title.isEmpty()) return@mapNotNull null
			val card = anchor.parent()
			val rating = card?.selectFirst("footer .rate")?.text()?.toFloatOrNull()
				?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
			Content(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = baseUrl() + href,
				rating = rating,
				contentRating = ContentRating.SAFE,
				coverUrl = anchor.attrAsAbsoluteUrlOrNull("data-original"),
				tags = emptySet(),
				state = parseState(anchor.selectFirst(".countrie span:last-child")?.text()),
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Content::id)

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + manga.url, getRequestHeaders())
		if (!response.isSuccessful) return manga
		return parseDetails(response.parseHtml(), manga)
	}

	internal fun parseDetails(document: Document, manga: Content): Content {
		val header = document.selectFirst(".product-header") ?: return manga
		val title = header.selectFirst(".product-title")?.ownText()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = header.selectFirst("img.thumb")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
		val metadata = header.select(".product-excerpt").associate { row ->
			val text = row.text().trim()
			text.substringBefore('：').trim() to text.substringAfter('：', "").trim()
		}
		val tags = header.select("a[href^=/category/tags/]").mapNotNullTo(linkedSetOf(), ::parseTag)
		val authors = metadata["作者"]?.split('、', ',', '，')
			?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
			?.toSet()
			.orEmpty()
		val rating = header.selectFirst(".rate")?.text()?.toFloatOrNull()
			?.div(10f)?.coerceIn(0f, 1f) ?: manga.rating

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			description = metadata["漫画简介"] ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = parseState(metadata["状态"]) ?: manga.state,
			chapters = parseChapters(document, manga),
			contentRating = inferContentRating(tags),
		)
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> =
		document.select(".playlist a[href^=/chapter/]").mapNotNull { anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.text().trim()
			if (!CHAPTER_PATH.matches(href) || title.isEmpty()) return@mapNotNull null
			ContentChapter(
				id = generateUid("${manga.id}:$href"),
				title = title,
				number = CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}.distinctBy(ContentChapter::id)

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val chapterUrl = baseUrl() + chapter.url
		val response = webClient.httpGet(chapterUrl, getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parsePages(response.parseHtml(), chapterUrl)
	}

	internal fun parsePages(document: Document, chapterUrl: String): List<ContentPage> {
		val encoded = document.select("script").firstNotNullOfOrNull { script ->
			PARAMS_PATTERN.find(script.data())?.groupValues?.get(2)
		} ?: return emptyList()
		val params = AesCbcDecoder.decodeJsonWithPrefixedIv(encoded, PARAMS_KEY) ?: return emptyList()
		if (params.optString("host").let { it.isNotEmpty() && it != domain }) return emptyList()
		val images = params.optJSONArray("images") ?: return emptyList()
		val encryptedSource = params.optString("source_id") == ENCRYPTED_SOURCE_ID
		val headers = mapOf("User-Agent" to UserAgents.CHROME_DESKTOP)
		return buildList(images.length()) {
			for (index in 0 until images.length()) {
				val value = images.optString(index).trim()
				if (value.isEmpty()) continue
				val imageUrl = when {
					value.startsWith("http://") || value.startsWith("https://") -> value
					value.startsWith("//") -> "https:$value"
					encryptedSource -> "$ENCRYPTED_IMAGE_HOST/${value.trimStart('/')}#$ENCRYPTED_IMAGE_FRAGMENT"
					else -> value
				}
				if (!imageUrl.startsWith("http")) continue
				add(
					ContentPage(
						id = generateUid("${chapterUrl.substringAfter(domain)}:$index"),
						url = imageUrl,
						preview = imageUrl,
						headers = headers,
						source = source,
					),
				)
			}
		}
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		if (response.request.url.fragment != ENCRYPTED_IMAGE_FRAGMENT || !response.isSuccessful) return response
		val encrypted = response.body.bytes()
		val decrypted = decryptImage(encrypted)
		if (decrypted == null) {
			return response.newBuilder().body(encrypted.toResponseBody(response.body.contentType())).build()
		}
		val mediaType = detectImageMediaType(decrypted).toMediaType()
		return response.newBuilder()
			.removeHeader("Content-Encoding")
			.removeHeader("Content-Length")
			.header("Content-Type", mediaType.toString())
			.body(decrypted.toResponseBody(mediaType))
			.build()
	}

	private fun parseTag(anchor: Element): ContentTag? {
		val title = anchor.text().trim().takeIf(String::isNotEmpty) ?: return null
		val key = anchor.attr("href").substringAfter("/category/tags/").trim('/').takeIf(String::isNotEmpty)
			?: return null
		return ContentTag(title, "tags/$key", source)
	}

	private fun parseState(value: String?): ContentState? = when {
		value?.contains("完结") == true -> ContentState.FINISHED
		value?.contains("连载") == true -> ContentState.ONGOING
		else -> null
	}

	private fun inferContentRating(tags: Set<ContentTag>): ContentRating = when {
		tags.any { it.title.contains("限制") || it.title.contains("绅士") } -> ContentRating.ADULT
		else -> ContentRating.SAFE
	}

	private fun baseUrl(): String = "http://$domain"

	internal companion object {
		private const val PARAMS_KEY = "9S8\$vJnU2ANeSRoF"
		private const val IMAGE_KEY = "my2ecret782ecret"
		private const val ENCRYPTED_SOURCE_ID = "12"
		private const val ENCRYPTED_IMAGE_HOST = "https://img1.baipiaoguai.org"
		private const val ENCRYPTED_IMAGE_FRAGMENT = "dd-aes"
		private val BOOK_PATH = Regex("""/book/\d+\.html""")
		private val CHAPTER_PATH = Regex("""/chapter/\d+-\d+\.html""")
		private val CHAPTER_NUMBER = Regex("""第(\d+(?:\.\d+)?)""")
		private val PARAMS_PATTERN = Regex(
			"""\bparams\s*=\s*(['"])(.*?)\1""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)

		private val REGIONS = listOf(
			"国产漫画" to "list/1",
			"日本漫画" to "list/2",
			"韩国漫画" to "list/3",
			"欧美漫画" to "list/4",
		)

		internal fun detectImageMediaType(data: ByteArray): String = when {
			data.size >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte() -> "image/jpeg"
			data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
			data.size >= 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WEBP" -> "image/webp"
			data.size >= 4 && String(data, 0, 4) == "GIF8" -> "image/gif"
			else -> "application/octet-stream"
		}

		internal fun decryptImage(data: ByteArray): ByteArray? {
			val key = IMAGE_KEY.toByteArray(Charsets.UTF_8)
			return AesCbcDecoder.decrypt(data, key, key)
		}

		private val PNG_SIGNATURE = byteArrayOf(
			0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
		)
	}
}
