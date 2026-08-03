@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
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
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 飞飞漫画（feifeimh.cc）。
 *
 * 详情页目录使用前端 RC4 混淆，但“开始阅读”地址和阅读页完整侧栏均为服务端明文。
 * 解析器通过该明文链路读取目录，不执行或移植站点的混淆 JavaScript。
 */
@ContentSourceParser("FEIFEIMANHUA", "飞飞漫画", "zh")
internal class FeifeiManhuaParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.FEIFEIMANHUA, pageSize = 28, searchPageSize = 28) {

	override val configKeyDomain = ConfigKey.Domain("www.feifeimh.cc")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	private val areaTags by lazy {
		AREAS.map { (title, key) -> ContentTag(title, key, source) }
	}

	private val themeTags by lazy {
		THEMES.map { title -> ContentTag(title, "cate=$title", source) }
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
		val path = if (!filter.query.isNullOrBlank()) {
			buildSearchPath(page, filter.query)
		} else {
			buildListPath(page, filter)
		}
		val response = webClient.httpGet(baseUrl() + path, getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parseList(response.parseHtml())
	}

	internal fun buildListPath(page: Int, filter: ContentListFilter): String {
		val parameters = buildList {
			filter.tags.firstOrNull { it.key.startsWith("cate=") }?.let { add(it.key) }
			filter.tags.firstOrNull { it.key.startsWith("area=") }?.let { add(it.key) }
			when {
				ContentState.ONGOING in filter.states -> add("end=2")
				ContentState.FINISHED in filter.states -> add("end=1")
			}
			if (page > 1) add("page=$page")
		}
		return "/booklist" + parameters.joinToString("&", prefix = if (parameters.isEmpty()) "" else "?")
	}

	internal fun buildSearchPath(page: Int, query: String): String = buildString {
		append("/2cb?keyword=").append(query.urlEncoded()).append("&sn=pp")
		if (page > 1) append("&page=").append(page)
	}

	internal fun parseList(document: Document): List<Content> =
		document.select(".mh-list .mh-item").mapNotNull { card ->
			val anchor = card.selectFirst("a[href^=/book/][title]") ?: return@mapNotNull null
			val href = anchor.attr("href").trim()
			val title = anchor.attr("title").trim()
			if (!DETAIL_PATH.matches(href) || title.isEmpty()) return@mapNotNull null
			val cover = card.selectFirst(".mh-cover")?.attr("style")
				?.let { STYLE_URL.find(it)?.groupValues?.get(1)?.trim('"', '\'') }
			Content(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = baseUrl() + href,
				rating = parseRating(card.selectFirst(".mh-star-line")?.classNames().orEmpty()),
				contentRating = ContentRating.SAFE,
				coverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = card.selectFirst("p.chapter")?.text()?.trim(),
				source = source,
			)
		}.distinctBy(Content::id)

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + manga.url, getRequestHeaders())
		if (!response.isSuccessful) return manga
		val document = response.parseHtml()
		val firstChapterPath = document.selectFirst(".banner_detail_form a.btn-2[href^=/chapter/]")
			?.attr("href")?.takeIf(CHAPTER_PATH::matches)
		val chaptersDocument = firstChapterPath?.let { path ->
			webClient.httpGet(baseUrl() + path, getRequestHeaders()).takeIf { it.isSuccessful }?.parseHtml()
		}
		return parseDetails(document, chaptersDocument, manga)
	}

	internal fun parseDetails(document: Document, chaptersDocument: Document?, manga: Content): Content {
		val info = document.selectFirst(".banner_detail_form .info") ?: return manga
		val title = info.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = document.selectFirst(".banner_detail_form > .cover > img")
			?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
		val subtitles = info.select("p.subtitle").associate { row ->
			row.text().substringBefore('：').trim() to row.text().substringAfter('：', "").trim()
		}
		val altTitles = splitValues(subtitles["别名"]).filterTo(linkedSetOf()) { it != title }
		val authors = splitValues(subtitles["作者"]).toSet()
		val tags = info.select("a[href*=/booklist/][href*=tag]").mapNotNullTo(linkedSetOf()) { anchor ->
			val value = anchor.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullTo null
			ContentTag(value, "cate=$value", source)
		}
		val stateText = info.select(".tip .block").firstOrNull { it.text().startsWith("状态") }?.text()

		return manga.copy(
			title = title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			coverUrl = cover,
			largeCoverUrl = cover,
			description = info.selectFirst("p.content")?.text()?.trim() ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = parseState(stateText) ?: manga.state,
			chapters = chaptersDocument?.let { parseChapters(it, manga) }.orEmpty(),
			contentRating = inferContentRating(tags),
		)
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> =
		document.select(".sidebar-content a[href^=/chapter/]").mapNotNull { anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.text().trim()
			if (!CHAPTER_PATH.matches(href) || title.isEmpty()) return@mapNotNull null
			ContentChapter(
				id = generateUid("${manga.id}:$href"),
				title = title,
				number = parseChapterNumber(title),
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

	internal fun parsePages(document: Document, chapterUrl: String): List<ContentPage> =
		document.select(".comiclist .imgpic img").mapIndexedNotNull { index, image ->
			val src = image.attrAsAbsoluteUrlOrNull("src")
			val url = if (src != null && !src.contains("/static/images/loadimg")) {
				src
			} else {
				image.attrAsAbsoluteUrlOrNull("data-original")
			} ?: return@mapIndexedNotNull null
			ContentPage(
				id = generateUid("${chapterUrl.substringAfter(domain)}:$index"),
				url = url,
				preview = url,
				headers = mapOf("User-Agent" to UserAgents.CHROME_DESKTOP),
				source = source,
			)
		}.distinctBy(ContentPage::url)

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun parseState(value: String?): ContentState? = when {
		value?.contains("完结") == true -> ContentState.FINISHED
		value?.contains("连载") == true -> ContentState.ONGOING
		else -> null
	}

	private fun inferContentRating(tags: Set<ContentTag>): ContentRating = when {
		tags.any { it.title.contains("成人") || it.title.contains("限制") || it.title.contains("18禁") } -> ContentRating.ADULT
		else -> ContentRating.SAFE
	}

	private fun baseUrl(): String = "https://$domain"

	internal companion object {
		private val DETAIL_PATH = Regex("""/book/\d+""")
		private val CHAPTER_PATH = Regex("""/chapter/\d+""")
		private val CHAPTER_NUMBER = Regex("""第(\d+(?:\.\d+)?)话""")
		private val PLAIN_CHAPTER_NUMBER = Regex("""^(\d+(?:\.\d+)?)$""")
		private val STYLE_URL = Regex("""background-image\s*:\s*url\(([^)]+)\)""", RegexOption.IGNORE_CASE)
		private val STAR_CLASS = Regex("""star-(\d+)""")

		private val AREAS = listOf(
			"港台" to "area=5",
			"欧美" to "area=4",
			"国漫" to "area=3",
			"日本" to "area=2",
			"韩国" to "area=1",
		)

		private val THEMES = listOf(
			"神幻", "女主", "韩漫", "百合", "修真", "恐怖", "耽美", "穿越", "悬疑", "真人",
			"古风", "科幻", "热血", "霸总", "恋爱", "生活", "后宫", "搞笑", "校园",
		)

		private fun parseRating(classes: Set<String>): Float = classes.firstNotNullOfOrNull { className ->
			STAR_CLASS.matchEntire(className)?.groupValues?.get(1)?.toFloatOrNull()?.div(5f)
		}?.coerceIn(0f, 1f) ?: RATING_UNKNOWN

		private fun parseChapterNumber(title: String): Float =
			CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull()
				?: PLAIN_CHAPTER_NUMBER.matchEntire(title)?.groupValues?.get(1)?.toFloatOrNull()
				?: 0f

		private fun splitValues(value: String?): List<String> = value.orEmpty().split(',', '，', '、')
			.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
	}
}
