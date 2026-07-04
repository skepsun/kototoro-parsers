package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

internal abstract class AcgxmhBase(
	context: ContentLoaderContext,
	source: ContentParserSource,
	pageSize: Int,
) : PagedContentParser(context, source, pageSize = pageSize) {

	override val configKeyDomain = ConfigKey.Domain("www.acgxmh.com")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$domain/")
		.build()

	protected fun imageUrl(raw: String): String? = raw.takeIf { it.isNotBlank() }?.toAbsoluteUrlOrNull(domain)

	override suspend fun getPageUrl(page: ContentPage): String = page.url
}

@ContentSourceParser("ACGXMH", "ACG漫画网", "zh", type = ContentType.HENTAI_MANGA)
internal class Acgxmh(context: ContentLoaderContext) :
	AcgxmhBase(context, ContentParserSource.ACGXMH, pageSize = 36) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = false)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_MANGA),
		availableTags = listOf(
			"full-color" to "全彩", "chinese" to "汉化中文", "japanese" to "日语", "english" to "英文",
			"naruto" to "火影忍者", "original" to "原创漫画", "blue-archive" to "蓝色档案",
			"zenless-zone-zero" to "绝区零", "honkai_to_-star-rail" to "崩坏星穹铁道",
		).mapTo(LinkedHashSet()) { ContentTag(it.second, it.first, source) },
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		return parseList(webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
		val title = doc.selectFirst("h1.title, h1")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
		val cover = doc.selectFirst(".content img, #content img")?.let { imageUrl(it.attr("src")) } ?: manga.coverUrl
		val tags = doc.select(".top-tags, a[href*=/tags/], a[href*=/anime/], a[href*=/language/]").mapNotNullTo(LinkedHashSet()) {
			val name = it.text().trim()
			if (name.isNotBlank()) ContentTag(name, it.attr("href").substringAfterLast('/').substringBefore('.'), source) else null
		}
		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = if (tags.isNotEmpty()) tags else manga.tags,
			contentRating = ContentRating.ADULT,
			chapters = listOf(ContentChapter(
				id = generateUid(manga.url),
				title = "阅读",
				number = 1f,
				volume = 0,
				url = manga.url,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)),
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val firstUrl = chapter.url.toAbsoluteUrl(domain)
		val firstDoc = webClient.httpGet(firstUrl, getRequestHeaders()).parseHtml()
		val pageUrls = LinkedHashSet<String>()
		pageUrls.add(firstUrl)
		firstDoc.select("#pages a[href], .page a[href]").forEach { a ->
			val href = a.attr("href")
			if (href.contains(".html")) pageUrls.add(href.toAbsoluteUrl(domain))
		}
		val images = LinkedHashSet<String>()
		for (url in pageUrls) {
			val doc = if (url == firstUrl) firstDoc else webClient.httpGet(url, getRequestHeaders()).parseHtml()
			doc.select(".content img[src], p img[src]").forEach { imageUrl(it.attr("src"))?.let(images::add) }
		}
		return images.map { url -> ContentPage(id = generateUid(url), url = url, preview = null, source = source) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		filter.query?.takeIf { it.isNotBlank() }?.let {
			return "https://$domain/?q=${it.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		filter.tags.firstOrNull()?.let {
			return when (it.key) {
				"chinese", "japanese", "english" -> "https://$domain/language/${it.key}.html"
				"full-color" -> "https://$domain/tags/full-color.html"
				else -> "https://$domain/anime/${it.key}.html"
			}
		}
		return when (order) {
			SortOrder.POPULARITY -> "https://$domain/hot/" + if (page > 1) "index-$page.html" else ""
			else -> if (page <= 1) "https://$domain/" else "https://$domain/index-$page.html"
		}
	}

	private fun parseList(doc: Document): List<Content> = doc.select("#list li").mapNotNull { item ->
		val a = item.selectFirst("a.thumb[href], a.title[href]") ?: return@mapNotNull null
		val href = a.attr("href").toAbsoluteUrl(domain)
		val img = item.selectFirst("img")
		val title = item.selectFirst("a.title")?.text()?.trim()
			?: img?.attr("alt")?.takeIf { it.isNotBlank() }
			?: return@mapNotNull null
		val tags = item.select(".top-tags, .lang").mapNotNullTo(LinkedHashSet()) {
			val text = it.text().trim()
			if (text.isNotBlank()) ContentTag(text, text, source) else null
		}
		Content(generateUid(href), title, emptySet(), href.removePrefix("https://$domain"), href, RATING_UNKNOWN, ContentRating.ADULT, img?.let { imageUrl(it.attr("src")) }, tags, null, emptySet(), source = source)
	}
}

@ContentSourceParser("ACGXMH_VIDEO", "ACG漫画网动画", "zh", type = ContentType.HENTAI_VIDEO)
internal class AcgxmhVideo(context: ContentLoaderContext) :
	AcgxmhBase(context, ContentParserSource.ACGXMH_VIDEO, pageSize = 24) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isSearchWithFiltersSupported = true, isMultipleTagsSupported = false)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
		availableTags = linkedSetOf(
			ContentTag("H动画", "gif", source),
			ContentTag("里番剧", "hanime", source),
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		return parseList(webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
		val title = doc.selectFirst("h1.title, h1")?.text()?.trim()
			?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
			?: manga.title
		val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
			?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
			?: doc.selectFirst(".animation-description")?.text()?.trim()?.takeIf { it.isNotBlank() }
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
			?: doc.selectFirst("video[poster]")?.let { imageUrl(it.attr("poster")) }
			?: manga.coverUrl
		val tags = doc.select(".animation-description a[href], .top-tags, a[href*=/circle/]").mapNotNullTo(LinkedHashSet()) {
			val name = it.text().trim()
			val key = it.attr("href").substringAfterLast('/').substringBefore('.').takeIf { value -> value.isNotBlank() } ?: name
			if (name.isNotBlank()) ContentTag(name, key, source) else null
		}
		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = if (tags.isNotEmpty()) tags else manga.tags,
			contentRating = ContentRating.ADULT,
			chapters = listOf(ContentChapter(
				id = generateUid(manga.url),
				title = "播放",
				number = 1f,
				volume = 0,
				url = manga.url,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)),
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		val urls = LinkedHashSet<String>()
		doc.select("video[src], video source[src], iframe[src]").forEach {
			it.attr("src").toAbsoluteUrlOrNull(domain)?.let(urls::add)
		}
		VIDEO_URL_REGEX.findAll(doc.outerHtml()).forEach {
			urls.add(it.value.replace("\\/", "/"))
		}
		return urls.map { url -> ContentPage(id = generateUid(url), url = url, preview = null, source = source) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		filter.query?.takeIf { it.isNotBlank() }?.let {
			return "https://$domain/?q=${it.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		val section = filter.tags.firstOrNull()?.key?.takeIf { it == "gif" || it == "hanime" }
			?: if (order == SortOrder.POPULARITY) "hanime" else "gif"
		return if (page <= 1) {
			"https://$domain/$section/"
		} else {
			"https://$domain/$section/index-$page.html"
		}
	}

	private fun parseList(doc: Document): List<Content> = doc.select(".grid-item").mapNotNull { item ->
		val a = item.selectFirst("a[href*=/gif/], a[href*=/hanime/]") ?: return@mapNotNull null
		val href = a.attr("href").toAbsoluteUrl(domain)
		val img = item.selectFirst("img")
		val title = item.selectFirst(".title a")?.text()?.trim()
			?: a.attr("title").takeIf { it.isNotBlank() }
			?: img?.attr("alt")?.takeIf { it.isNotBlank() }
			?: return@mapNotNull null
		val tags = item.select(".media, .corner").mapNotNullTo(LinkedHashSet()) {
			val text = it.text().trim()
			if (text.isNotBlank()) ContentTag(text, text, source) else null
		}
		Content(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href.removePrefix("https://$domain"),
			publicUrl = href,
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = img?.let { imageUrl(it.attr("src")) },
			tags = tags,
			state = null,
			authors = emptySet(),
			source = source,
		)
	}.distinctBy { it.id }

	private companion object {
		private val VIDEO_URL_REGEX = Regex("https?://[^\"'\\s<>]+\\.(?:m3u8|mp4|webm)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
	}
}
