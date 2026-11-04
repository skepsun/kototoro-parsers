@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
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
 * 滴答漫画（ddmanhua.com）与其同库镜像六漫画（www.liumanhua.com）。
 *
 * 两站共用同一作品/章节 ID 空间和阅读参数协议（前 16 字节 IV + AES-CBC，`source_id=6` 直接给图床地址），
 * 差别只在路由和前端：滴答是 `/book/{id}.html` + `/chapter/{bookId}-{chapterId}.html`，六漫画是
 * `/{id}` + `/{bookId}/{chapterId}.html`，列表/详情 DOM 也不同。因此合并为单一源，用域名设置切换镜像。
 *
 * 作品与章节 URL 统一规范成 `/book/{id}` 和 `/book/{bookId}/{chapterId}`（`detailPath` / `chapterPath`
 * 再按当前域名还原），这样切换镜像后已收藏的条目仍然可读。两站搜索接口都返回空结果，只开放分类浏览。
 */
@ContentSourceParser("DDMANHUA", "滴答漫画", "zh")
internal class DdManhuaParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.DDMANHUA, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("ddmanhua.com", "www.liumanhua.com")

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
		if (isLiuManHua) parseLiuManHuaList(document) else parseDdList(document)

	private fun parseDdList(document: Document): List<Content> =
		document.select(".lists-content a.vodlist__thumb[href^=/book/]").mapNotNull { anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.attr("title").trim()
			val bookId = bookIdOf(href) ?: return@mapNotNull null
			if (title.isEmpty()) return@mapNotNull null
			val card = anchor.parent()
			val rating = card?.selectFirst("footer .rate")?.text()?.toFloatOrNull()
				?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
			Content(
				id = generateUid(bookPathOf(bookId)),
				title = title,
				altTitles = emptySet(),
				url = bookPathOf(bookId),
				publicUrl = baseUrl() + detailPath(bookPathOf(bookId)),
				rating = rating,
				contentRating = ContentRating.SAFE,
				coverUrl = anchor.attrAsAbsoluteUrlOrNull("data-original"),
				tags = emptySet(),
				state = parseState(anchor.selectFirst(".countrie span:last-child")?.text()),
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Content::id)

	private fun parseLiuManHuaList(document: Document): List<Content> =
		document.select("div.cy_list_mh > ul").mapNotNull { card ->
			val titleAnchor = card.selectFirst("li.title > a[href]") ?: return@mapNotNull null
			val bookId = bookIdOf(titleAnchor.attr("href")) ?: return@mapNotNull null
			val title = titleAnchor.text().trim()
			if (title.isEmpty()) return@mapNotNull null
			val tags = card.select("li.biaoqian a[href*=/tags/]").mapNotNullTo(linkedSetOf(), ::parseTag)
			Content(
				id = generateUid(bookPathOf(bookId)),
				title = title,
				altTitles = emptySet(),
				url = bookPathOf(bookId),
				publicUrl = baseUrl() + detailPath(bookPathOf(bookId)),
				rating = RATING_UNKNOWN,
				contentRating = inferContentRating(tags),
				coverUrl = card.selectFirst("li > a.pic img[src]")?.attrAsAbsoluteUrlOrNull("src"),
				tags = tags,
				state = parseState(card.selectFirst("li.zuozhe")?.text()),
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Content::id)

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + detailPath(manga.url), getRequestHeaders())
		if (!response.isSuccessful) return manga
		return parseDetails(response.parseHtml(), manga)
	}

	internal fun parseDetails(document: Document, manga: Content): Content =
		if (isLiuManHua) parseLiuManHuaDetails(document, manga) else parseDdDetails(document, manga)

	private fun parseDdDetails(document: Document, manga: Content): Content {
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

	private fun parseLiuManHuaDetails(document: Document, manga: Content): Content {
		val info = document.selectFirst(".cy_info") ?: return manga
		val title = info.selectFirst(".cy_title h1")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = info.selectFirst(".cy_info_cover img[src]")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
		val tags = info.select("a[href*=/tags/]").mapNotNullTo(linkedSetOf(), ::parseTag)
		val authors = info.select("a[href^=/search]")
			.mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
			.toSet()
		val infoText = info.select(".cy_xinxi").text()
		val rating = RATING_PATTERN.find(infoText)?.groupValues?.get(1)?.toFloatOrNull()
			?.div(10f)?.coerceIn(0f, 1f) ?: manga.rating

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			description = document.selectFirst("#comic-description")?.text()?.trim() ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = parseState(infoText) ?: manga.state,
			chapters = parseChapters(document, manga),
			contentRating = inferContentRating(tags),
		)
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> =
		if (isLiuManHua) {
			document.select(".chapter__item a[href]").mapNotNull { anchor ->
				parseChapter(manga, anchor.attr("href").trim(), anchor.text().trim())
			}
		} else {
			document.select(".playlist a[href^=/chapter/]").mapNotNull { anchor ->
				parseChapter(manga, anchor.attr("href").trim(), anchor.text().trim())
			}
		}

	private fun parseChapter(manga: Content, href: String, title: String): ContentChapter? {
		if (title.isEmpty()) return null
		val (bookId, chapterId) = chapterIdsOf(href) ?: return null
		return ContentChapter(
			id = generateUid("${manga.id}:${chapterPathOf(bookId, chapterId)}"),
			title = title,
			number = CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
			volume = 0,
			url = chapterPathOf(bookId, chapterId),
			scanlator = null,
			uploadDate = 0,
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val chapterUrl = baseUrl() + chapterPath(chapter.url)
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
		val pageKey = chapterIdsOf(chapterUrl)?.let { (bookId, chapterId) -> chapterPathOf(bookId, chapterId) }
			?: chapterUrl
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
						id = generateUid("$pageKey:$index"),
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
		return BaipiaoguaiImageDecoder.decodeResponse(chain.proceed(chain.request()), ENCRYPTED_IMAGE_FRAGMENT)
	}

	/**
	 * 把域名相关的详情地址还原成当前镜像可用的路径，同时兼容历史保存的原始地址
	 * （`/book/1.html`、`/1`、`/book/1`）。
	 */
	internal fun detailPath(url: String): String {
		val bookId = bookIdOf(url) ?: return url
		return if (isLiuManHua) "/$bookId" else "/book/$bookId.html"
	}

	/**
	 * 同上，把规范化章节路径 `/book/{bookId}/{chapterId}` 还原成当前镜像的章节地址，
	 * 并兼容滴答旧的 `/chapter/{bookId}-{chapterId}.html` 与六漫画的 `/{bookId}/{chapterId}.html`。
	 */
	internal fun chapterPath(url: String): String {
		val (bookId, chapterId) = chapterIdsOf(url) ?: return url
		return if (isLiuManHua) "/$bookId/$chapterId.html" else "/chapter/$bookId-$chapterId.html"
	}

	private fun parseTag(anchor: Element): ContentTag? {
		val title = anchor.text().trim().takeIf(String::isNotEmpty) ?: return null
		// 滴答用 /category/tags/{id}，六漫画列表用 /category/list/{n}/tags/{id}，都取最后一段数字。
		val key = anchor.attr("href").substringAfterLast("/tags/").substringBefore('/').trim()
			.takeIf(String::isNotEmpty) ?: return null
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

	private fun bookIdOf(url: String): String? = BOOK_ID.find(url)?.groupValues?.get(1)

	private fun chapterIdsOf(url: String): Pair<String, String>? = CHAPTER_IDS.find(url)?.let { match ->
		match.groupValues[1] to match.groupValues[2]
	}

	private fun bookPathOf(bookId: String): String = "/book/$bookId"

	private fun chapterPathOf(bookId: String, chapterId: String): String = "/book/$bookId/$chapterId"

	private val isLiuManHua: Boolean
		get() = domain.contains(LIU_MAN_HUA_DOMAIN_MARKER)

	private fun baseUrl(): String = if (isLiuManHua) "https://$domain" else "http://$domain"

	internal companion object {
		private const val PARAMS_KEY = "9S8\$vJnU2ANeSRoF"
		private const val ENCRYPTED_SOURCE_ID = "12"
		private const val ENCRYPTED_IMAGE_FRAGMENT = "dd-aes"
		private const val LIU_MAN_HUA_DOMAIN_MARKER = "liumanhua"
		private val BOOK_ID = Regex("""(?:/book/|/)(\d+)(?:\.html)?$""")
		private val CHAPTER_IDS = Regex("""(\d+)[-/](\d+)(?:\.html)?$""")
		private val CHAPTER_NUMBER = Regex("""(?:第|Act\.?\s*)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		private val RATING_PATTERN = Regex("""评分[：:]\s*(\d+(?:\.\d+)?)""")
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

		internal fun detectImageMediaType(data: ByteArray): String = BaipiaoguaiImageDecoder.detectMediaType(data)

		internal fun decryptImage(data: ByteArray): ByteArray? = BaipiaoguaiImageDecoder.decrypt(data)
	}
}
