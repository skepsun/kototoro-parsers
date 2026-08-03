@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
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
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.Base64
import java.util.EnumSet

/**
 * 漫画100（manhua100.com）。
 *
 * 阅读参数由服务端输出，并以“16 字节 IV + AES-CBC 密文”的形式编码；这里直接实现固定算法，
 * 不执行站点 JavaScript。图片代理要求阅读页 Referer。
 */
@ContentSourceParser("MANHUA100", "漫画100", "zh")
internal class Manhua100Parser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.MANHUA100, pageSize = 35, searchPageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("www.manhua100.com")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

	private val areaTags by lazy {
		AREAS.map { (title, key) -> ContentTag(title, key, source) }
	}

	private val themeTags by lazy {
		THEMES.map { (title, key) -> ContentTag(title, key, source) }
	}

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = (areaTags + themeTags).toSet(),
		tagGroups = listOf(
			ContentTagGroup("地区", areaTags.toSet()),
			ContentTagGroup("题材", themeTags.toSet()),
		),
		availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
		availableContentRating = EnumSet.allOf(ContentRating::class.java),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "${baseUrl()}/")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val url = if (!filter.query.isNullOrBlank()) {
			buildString {
				append(baseUrl()).append("/search?q=").append(filter.query.urlEncoded())
				if (page > 1) append("&page=").append(page)
			}
		} else {
			baseUrl() + buildListPath(page, order, filter)
		}
		val response = webClient.httpGet(url, getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parseList(response.parseHtml())
	}

	internal fun buildListPath(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val segments = buildList {
			filter.tags.firstOrNull { it.key.startsWith(AREA_PREFIX) }?.let { add(it.key) }
			filter.tags.firstOrNull { it.key.startsWith(THEME_PREFIX) }?.let { add(it.key) }
			when {
				ContentState.ONGOING in filter.states -> add("state/lianzai")
				ContentState.FINISHED in filter.states -> add("state/wanjie")
			}
			add(if (order == SortOrder.UPDATED) "order/update" else "order/views")
			if (page > 1) add("page/$page")
		}
		return "/category/" + segments.joinToString("/")
	}

	internal fun parseList(document: Document): List<Content> =
		document.select("a.lazy[href][data-original]")
			.mapNotNull { anchor ->
				val href = anchor.attr("href").trim()
				if (!DETAIL_PATH.matches(href)) return@mapNotNull null
				val title = anchor.selectFirst(".tit, .dm-bn")?.text()?.trim()
					?: anchor.attr("title").trim().removeSuffix("漫画").trim()
				if (title.isEmpty()) return@mapNotNull null
				Content(
					id = generateUid(href),
					title = title,
					altTitles = emptySet(),
					url = href,
					publicUrl = baseUrl() + href,
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.SAFE,
					coverUrl = anchor.attrAsAbsoluteUrlOrNull("data-original"),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					description = anchor.parent()?.selectFirst(".info")?.text()?.trim(),
					source = source,
				)
			}
			.distinctBy(Content::id)

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + manga.url, getRequestHeaders())
		if (!response.isSuccessful) return manga
		return parseDetails(response.parseHtml(), manga)
	}

	internal fun parseDetails(document: Document, manga: Content): Content {
		val info = document.selectFirst(".comic-detail")
		val title = info?.selectFirst(".comic-name")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = info?.selectFirst(".comic-thumb")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
		val metadata = info?.select(".comic-info")?.associate { row ->
			row.selectFirst(".info-attr")?.text()?.trim().orEmpty() to
				row.selectFirst(".info-text")?.text()?.trim().orEmpty()
		}.orEmpty()
		val authors = metadata["作者"]?.split('、', ',', '，')
			?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
			?.toSet()
			.orEmpty()
		val tags = info?.select(".comic-info a[href^=/category/theme/]")
			?.mapNotNullTo(linkedSetOf(), ::parseTag)
			.orEmpty()
		val description = info?.selectFirst(".comic-desc .info-text")?.text()?.trim()
		val keywords = document.selectFirst("meta[name=keywords]")?.attr("content")
		val altTitles = keywords?.split(',', '，')
			?.map { it.trim().removeSuffix("漫画").removeSuffix("全集").removeSuffix("最新章节") }
			?.filter { it.isNotEmpty() && it != title }
			?.toSet()
			.orEmpty()

		return manga.copy(
			title = title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = parseState(metadata["状态"]) ?: manga.state,
			chapters = parseChapters(document, manga),
			contentRating = inferContentRating(tags),
		)
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> =
		document.select(".comic-chapter a[href]").mapIndexedNotNull { index, anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.text().trim()
			if (!CHAPTER_PATH.matches(href) || title.isEmpty()) return@mapIndexedNotNull null
			ContentChapter(
				id = generateUid("${manga.id}:$href"),
				title = title,
				number = LEADING_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat(),
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
		val headers = getRequestHeaders().newBuilder().set("Referer", chapterUrl).build()
		val response = webClient.httpGet(chapterUrl, headers)
		if (!response.isSuccessful) return emptyList()
		return parsePages(response.parseHtml(), chapterUrl)
	}

	internal fun parsePages(document: Document, chapterUrl: String): List<ContentPage> {
		val encoded = document.select("script").firstNotNullOfOrNull { script ->
			PARAMS_PATTERN.find(script.data())?.groupValues?.get(2)
		} ?: return emptyList()
		val params = Manhua100ImageDecoder.decode(encoded) ?: return emptyList()
		if (params.optString("host").let { it.isNotEmpty() && it != domain }) return emptyList()
		val images = params.optJSONArray("chapter_images") ?: return emptyList()
		val imageDomain = params.optString("images_domain")
		val encodePaths = params.optBoolean("images_base64")
		val imageHeaders = mapOf(
			"Referer" to chapterUrl,
			"User-Agent" to UserAgents.CHROME_DESKTOP,
		)
		return buildList(images.length()) {
			for (index in 0 until images.length()) {
				val original = images.optString(index).trim()
				if (original.isEmpty()) continue
				val imageUrl = when {
					imageDomain.isEmpty() -> original
					encodePaths -> imageDomain + Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
					original.startsWith("http://") || original.startsWith("https://") || original.startsWith("//") -> original
					else -> imageDomain + original
				}
				add(
					ContentPage(
						id = generateUid("${chapterUrl.substringAfter(domain)}:$index"),
						url = imageUrl,
						preview = imageUrl,
						headers = imageHeaders,
						source = source,
					),
				)
			}
		}
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun parseTag(anchor: Element): ContentTag? {
		val title = anchor.text().trim().takeIf(String::isNotEmpty) ?: return null
		val key = anchor.attr("href").trim('/').substringAfter("category/").takeIf(String::isNotEmpty)
			?: return null
		return ContentTag(title, key, source)
	}

	private fun parseState(value: String?): ContentState? = when {
		value?.contains("完结") == true -> ContentState.FINISHED
		value?.contains("连载") == true -> ContentState.ONGOING
		else -> null
	}

	private fun inferContentRating(tags: Set<ContentTag>): ContentRating = when {
		tags.any { it.key.endsWith("/xianzhiji") || it.key.endsWith("/shenshi") } -> ContentRating.ADULT
		else -> ContentRating.SAFE
	}

	private fun baseUrl(): String = "https://$domain"

	internal companion object {
		private const val AREA_PREFIX = "area/"
		private const val THEME_PREFIX = "theme/"
		private val DETAIL_PATH = Regex("""/\d+/?""")
		private val CHAPTER_PATH = Regex("""/\d+/\d+\.html""")
		private val LEADING_NUMBER = Regex("""^(\d+(?:\.\d+)?)""")
		private val PARAMS_PATTERN = Regex(
			"""\bparams\s*=\s*(['"])(.*?)\1""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)

		private val AREAS = listOf(
			"国内" to "area/guonei",
			"日本" to "area/riben",
			"韩国" to "area/hanguo",
			"欧美" to "area/oumei",
		)

		private val THEMES = listOf(
			"热血" to "theme/rexue",
			"仙侠" to "theme/xianxia",
			"玄幻" to "theme/xuanhuan",
			"都市" to "theme/dushi",
			"冒险" to "theme/maoxian",
			"武侠" to "theme/wuxia",
			"格斗" to "theme/gedou",
			"科幻" to "theme/kehuan",
			"异能" to "theme/yineng",
			"重生" to "theme/chongsheng",
			"推理" to "theme/tuili",
			"悬疑" to "theme/xuanyi",
			"竞技" to "theme/jingji",
			"搞笑" to "theme/gaoxiao",
			"恐怖" to "theme/kongbu",
			"生活" to "theme/shenghuo",
			"校园" to "theme/xiaoyuan",
			"恋爱" to "theme/lianai",
			"百合" to "theme/baihe",
			"耽美" to "theme/danmei",
			"历史" to "theme/lishi",
			"战争" to "theme/zhanzheng",
			"剧情" to "theme/juqing",
			"穿越" to "theme/chuanyue",
			"复仇" to "theme/fuchou",
			"奇幻" to "theme/qihuan",
			"战斗" to "theme/zhandou",
			"灵异" to "theme/lingyi",
			"治愈" to "theme/zhiyu",
			"猎奇" to "theme/lieqi",
			"末日" to "theme/mori",
			"后宫" to "theme/hougong",
			"游戏" to "theme/youxi",
			"限制级" to "theme/xianzhiji",
			"绅士" to "theme/shenshi",
		)
	}
}

internal object Manhua100ImageDecoder {
	private const val KEY = "5V&RoR%Jf@pJPydF"

	fun decode(encoded: String): JSONObject? = AesCbcDecoder.decodeJsonWithPrefixedIv(encoded, KEY)
}
