@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
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
import java.util.EnumSet

/**
 * 图库漫画（tuku.cc）。
 *
 * 阅读页在服务端 HTML 中直接输出全部图片，图片 CDN 要求站内 Referer。
 */
@ContentSourceParser("TUKU", "图库漫画", "zh")
internal class TukuParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.TUKU, pageSize = 24, searchPageSize = 21) {

	override val configKeyDomain = ConfigKey.Domain("www.tuku.cc")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.NEWEST)

	private val genreTags: List<ContentTag> by lazy {
		GENRES.map { (title, key) -> ContentTag(title, key, source) }
	}

	private val regionTags: List<ContentTag> by lazy {
		REGIONS.map { (title, key) -> ContentTag(title, key, source) }
	}

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = (genreTags + regionTags).toSet(),
		tagGroups = listOf(
			ContentTagGroup("题材", genreTags.toSet()),
			ContentTagGroup("地区", regionTags.toSet()),
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
				append(baseUrl()).append("/search?title=").append(filter.query.urlEncoded())
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
			filter.tags.firstOrNull { it.key.startsWith(REGION_PREFIX) }?.let { add(it.key) }
			filter.tags.firstOrNull { it.key.startsWith(GENRE_PREFIX) }?.let { add(it.key) }
			when {
				ContentState.ONGOING in filter.states -> add("status1")
				ContentState.FINISHED in filter.states -> add("status2")
			}
			when (order) {
				SortOrder.UPDATED, SortOrder.UPDATED_ASC -> add("order2")
				SortOrder.NEWEST, SortOrder.NEWEST_ASC, SortOrder.ADDED, SortOrder.ADDED_ASC -> add("order18")
				else -> Unit
			}
			if (page > 1) add("p$page")
		}
		return buildString {
			append("/comics")
			segments.forEach { append('-').append(it) }
			append('/')
		}
	}

	internal fun parseList(document: Document): List<Content> {
		return document.select("a[href^=/manga-][title]")
			.groupBy { it.attr("href") }
			.mapNotNull { (href, anchors) ->
				val title = anchors.firstNotNullOfOrNull { it.attr("title").trim().takeIf(String::isNotEmpty) }
					?: return@mapNotNull null
				val card = anchors.firstNotNullOfOrNull { anchor ->
					anchor.parents().firstOrNull {
						it.hasClass("swiper-card-item") || it.hasClass("top-card")
					}
				}
				val cover = anchors.firstNotNullOfOrNull { anchor ->
					anchor.selectFirst("img")?.imageUrl()
				}
				val tags = card?.select("a[href^=/comics-tag]")
					?.mapNotNull(::parseTag)
					?.toSet()
					.orEmpty()
				val authors = card?.select("p:contains(作者) a")
					?.mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
					?.toSet()
					.orEmpty()
				val state = parseState(card?.selectFirst(".card-item-state, .new-tip")?.text())
				val description = card?.selectFirst("p.multi-ellipsis")?.text()?.trim()
				Content(
					id = generateUid(href),
					url = href,
					publicUrl = baseUrl() + href,
					coverUrl = cover,
					title = title,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					tags = tags,
					authors = authors,
					state = state,
					source = source,
					contentRating = inferContentRating(tags),
					description = description,
				)
			}
	}

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + manga.url, getRequestHeaders())
		if (!response.isSuccessful) return manga
		return parseDetails(response.parseHtml(), manga)
	}

	internal fun parseDetails(document: Document, manga: Content): Content {
		val info = document.selectFirst(".manga-info-card") ?: return manga.copy(
			chapters = parseChapters(document, manga),
		)
		val title = info.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = info.selectFirst(".manga-cover img")?.imageUrl() ?: manga.coverUrl
		val authors = info.select("p:has(span:contains(作者)) a")
			.mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
			.toSet()
		val stateText = info.selectFirst("p:has(span:contains(状态))")?.text()?.substringAfter("状态：")?.trim()
		val tags = info.select("a[href^=/comics-tag]").mapNotNullTo(linkedSetOf(), ::parseTag)
		val description = info.selectFirst("p.multi-ellipsis")?.text()?.trim()
		val altTitles = document.selectFirst("meta[name=keywords]")
			?.attr("content")
			?.split(',', '，')
			?.getOrNull(1)
			?.trim()
			?.takeIf { it.isNotEmpty() && it != title }
			?.let(::setOf)
			.orEmpty()

		return manga.copy(
			title = title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description ?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = parseState(stateText) ?: manga.state,
			chapters = parseChapters(document, manga),
			contentRating = inferContentRating(tags),
		)
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> {
		return document.select(".manga-chapter-wrap a[href^=/chapter]").mapNotNull { anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.text().trim()
			if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
			ContentChapter(
				id = generateUid("${manga.id}:$href"),
				url = href,
				title = title,
				number = parseChapterNumber(title),
				volume = parseVolumeNumber(title),
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}.distinctBy(ContentChapter::id)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val chapterUrl = baseUrl() + chapter.url
		val headers = getRequestHeaders().newBuilder().set("Referer", chapterUrl).build()
		val response = webClient.httpGet(chapterUrl, headers)
		if (!response.isSuccessful) return emptyList()
		val pages = parsePages(response.parseHtml(), chapterUrl)
		val expected = parseExpectedPageCount(chapter.title)
		if (expected != null && expected != pages.size) {
			println("[TukuParser] Page count mismatch for ${chapter.url}: expected=$expected, parsed=${pages.size}")
		}
		return pages
	}

	internal fun parsePages(document: Document, chapterUrl: String): List<ContentPage> {
		val imageHeaders = mapOf(
			"Referer" to chapterUrl,
			"User-Agent" to UserAgents.CHROME_DESKTOP,
		)
		val chapterPath = chapterUrl.substringAfter("://").substringAfter('/')
		return document.select(".read-doc-center img[data-original]").mapIndexedNotNull { index, image ->
			val url = image.attrAsAbsoluteUrlOrNull("data-original") ?: return@mapIndexedNotNull null
			ContentPage(
				id = generateUid("$chapterPath:$index"),
				url = url,
				preview = url,
				headers = imageHeaders,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun Element.imageUrl(): String? {
		return attrAsAbsoluteUrlOrNull("data-original")
			?: attrAsAbsoluteUrlOrNull("data-src")
			?: attrAsAbsoluteUrlOrNull("src")
	}

	private fun parseTag(anchor: Element): ContentTag? {
		val title = anchor.text().trim().takeIf(String::isNotEmpty) ?: return null
		val key = anchor.attr("href").substringAfter("/comics-").trim('/').takeIf(String::isNotEmpty)
			?: return null
		return ContentTag(title, key, source)
	}

	private fun baseUrl(): String = "https://$domain"

	internal companion object {
		private const val GENRE_PREFIX = "tag"
		private const val REGION_PREFIX = "region"

		private val GENRES = listOf(
			"热血" to "tag1",
			"恋爱" to "tag2",
			"百合" to "tag4",
			"彩虹" to "tag5",
			"冒险" to "tag6",
			"后宫" to "tag9",
			"治愈" to "tag10",
			"悬疑" to "tag16",
			"搞笑" to "tag18",
			"奇幻" to "tag19",
			"历史" to "tag24",
			"古风" to "tag31",
			"都市" to "tag33",
		)

		private val REGIONS = listOf(
			"港台" to "region1",
			"日本" to "region2",
			"韩国" to "region3",
			"大陆" to "region4",
			"欧美" to "region5",
		)

		private val CHAPTER_NUMBER_REGEX = Regex("第\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:话|回|章)")
		private val VOLUME_NUMBER_REGEX = Regex("第\\s*([0-9]+)\\s*卷")
		private val PAGE_COUNT_REGEX = Regex("[（(]\\s*([0-9]+)\\s*[pP]\\s*[）)]")

		fun parseChapterNumber(title: String): Float =
			CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

		fun parseVolumeNumber(title: String): Int =
			VOLUME_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

		fun parseExpectedPageCount(title: String?): Int? = title?.let {
			PAGE_COUNT_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull()
		}

		private fun parseState(value: String?): ContentState? = when {
			value == null -> null
			value.contains("完结") -> ContentState.FINISHED
			value.contains("连载") || value.contains("最新") -> ContentState.ONGOING
			value.contains("暂停") -> ContentState.PAUSED
			else -> null
		}

		private fun inferContentRating(tags: Set<ContentTag>): ContentRating {
			return if (tags.any { it.title == "限制级" || it.title == "绅士" }) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			}
		}
	}
}
