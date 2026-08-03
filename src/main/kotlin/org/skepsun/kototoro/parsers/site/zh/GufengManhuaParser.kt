@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
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
 * 古风漫画（gfmh.app）。
 *
 * 站点搜索当前对已存在作品也返回空结果，因此仅开放服务端分类分页。阅读参数使用固定
 * AES-CBC 协议；图片二次解密复用 Baipiaoguai 后端解码器，不执行站点 JavaScript。
 */
@ContentSourceParser("GUFENGMANHUA", "古风漫画", "zh")
internal class GufengManhuaParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.GUFENGMANHUA, pageSize = 16) {

	override val configKeyDomain = ConfigKey.Domain("www.gfmh.app")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

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
		val response = webClient.httpGet(baseUrl() + buildListPath(page, filter), getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parseList(response.parseHtml())
	}

	internal fun buildListPath(page: Int, filter: ContentListFilter): String = buildString {
		append("/category")
		filter.tags.firstOrNull { tag -> REGIONS.any { it.second == tag.key } }?.let {
			append('/').append(it.key)
		}
		if (page > 1) append("/page/").append(page)
	}

	internal fun parseList(document: Document): List<Content> =
		document.select(".side_commend > ul.flex > li").mapNotNull { card ->
			val anchor = card.selectFirst(".img_span > a[href]") ?: return@mapNotNull null
			val href = anchor.attr("href").trim()
			val title = card.selectFirst("h2")?.text()?.trim().orEmpty()
			if (!DETAIL_PATH.matches(href) || title.isEmpty()) return@mapNotNull null
			Content(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = baseUrl() + href,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				coverUrl = anchor.selectFirst("img")?.attrAsAbsoluteUrlOrNull("data-original"),
				tags = emptySet(),
				state = parseState(anchor.selectFirst("span")?.text()),
				authors = card.selectFirst(".li_bottom i")?.text()?.trim()
					?.takeIf(String::isNotEmpty)?.let(::setOf).orEmpty(),
				description = card.selectFirst("p.indent")?.text()?.trim(),
				source = source,
			)
		}.distinctBy(Content::id)

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + manga.url, getRequestHeaders())
		if (!response.isSuccessful) return manga
		return parseDetails(response.parseHtml(), manga)
	}

	internal fun parseDetails(document: Document, manga: Content): Content {
		val info = document.selectFirst(".novel_info_main") ?: return manga
		val title = info.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = info.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
		val authors = info.selectFirst("i")?.text()?.substringAfter('：', "")?.trim()
			?.takeIf(String::isNotEmpty)?.let(::setOf).orEmpty()
		val state = parseState(info.select("p > span").firstOrNull { parseState(it.text()) != null }?.text())
		val tags = info.select("p > span").mapNotNullTo(linkedSetOf()) { element ->
			val value = element.text().trim()
			value.takeIf { it.isNotEmpty() && parseState(it) == null }?.let { ContentTag(it, it, source) }
		}

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = document.selectFirst("#info .intro")?.text()?.trim() ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = state ?: manga.state,
			chapters = parseChapters(document, manga),
			contentRating = inferContentRating(tags),
		)
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> =
		document.select("#ul_all_chapters a[href]").mapNotNull { anchor ->
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
					encryptedSource -> "${BaipiaoguaiImageDecoder.HOST}/${value.trimStart('/')}#$ENCRYPTED_IMAGE_FRAGMENT"
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

	override fun intercept(chain: Interceptor.Chain): Response =
		BaipiaoguaiImageDecoder.decodeResponse(chain.proceed(chain.request()), ENCRYPTED_IMAGE_FRAGMENT)

	private fun parseState(value: String?): ContentState? = when {
		value?.contains("完结") == true -> ContentState.FINISHED
		value?.contains("连载") == true -> ContentState.ONGOING
		else -> null
	}

	private fun inferContentRating(tags: Set<ContentTag>): ContentRating = when {
		tags.any { it.title.contains("限制") || it.title.contains("成人") } -> ContentRating.ADULT
		else -> ContentRating.SAFE
	}

	private fun baseUrl(): String = "https://$domain"

	internal companion object {
		private const val PARAMS_KEY = "9S8\$vJnU2ANeSRoF"
		private const val ENCRYPTED_SOURCE_ID = "12"
		private const val ENCRYPTED_IMAGE_FRAGMENT = "gufeng-aes"
		private val DETAIL_PATH = Regex("""/\d+\.html""")
		private val CHAPTER_PATH = Regex("""/\d+/\d+\.html""")
		private val CHAPTER_NUMBER = Regex("""(?:第|Act\.?\s*)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
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
	}
}
