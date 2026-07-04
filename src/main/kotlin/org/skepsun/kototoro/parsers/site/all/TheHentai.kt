package org.skepsun.kototoro.parsers.site.all

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
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

@ContentSourceParser("THEHENTAI", "TheHentai", type = ContentType.HENTAI_MANGA)
internal class TheHentai(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.THEHENTAI, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("thehentai.net", "en.thehentai.net")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = false)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_MANGA),
		availableTags = setOf(
			"anal", "incesto", "milf", "femboy", "ahegao", "boquete", "creampie", "peitoes",
			"bunda-grande", "3d", "hentai", "comics-hq", "exclusivo", "imagens",
		).mapTo(LinkedHashSet()) { ContentTag(it.replace('-', ' ').replaceFirstChar(Char::titlecase), it, source) },
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val url = buildListUrl(page, order, filter)
		return parseList(webClient.httpGet(url, getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
		val title = doc.selectFirst("h1.title_post, article h1, h1")?.text()?.trim()
			?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
			?: manga.title
		val description = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
			?.takeIf { it.isNotBlank() }
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
			?: doc.selectFirst("#img_cover, article img, .post_imgs img")?.let { imageUrl(it.attr("data-src").ifBlank { it.attr("src") }) }
			?: manga.coverUrl
		val tags = doc.select("a[href*=/tag/], a[href*=/category/]").mapNotNullTo(LinkedHashSet()) { a ->
			val href = a.attr("href")
			val key = href.substringAfter("/tag/", "").substringAfter("/category/", "").trim('/')
			val name = a.text().trim().removePrefix("#")
			if (key.isNotBlank() && name.isNotBlank()) ContentTag(name, key, source) else null
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
				title = title,
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
		doc.select(".post_imgs img, article img, #content img").forEach { img ->
			imageUrl(img.attr("data-src").ifBlank { img.attr("src") })?.let(urls::add)
		}
		if (urls.isEmpty()) {
			Regex("https?://[^\"'\\s<>]+\\.(?:jpe?g|png|webp)", RegexOption.IGNORE_CASE)
				.findAll(doc.outerHtml())
				.mapTo(urls) { it.value }
		}
		return urls.filterNot { it.contains("icon-th") || it.contains("mascot") || it.contains("ads") }
			.mapIndexed { index, url -> ContentPage(id = generateUid("#"), url = url, preview = null, source = source) }
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		filter.query?.takeIf { it.isNotBlank() }?.let {
			val suffix = if (page > 1) "&paged=$page" else ""
			return "https://$domain/?s=${it.urlEncoded()}$suffix"
		}
		filter.tags.firstOrNull()?.let {
			val prefix = if (it.key in setOf("hentai", "comics-hq", "exclusivo", "imagens", "3d")) "category" else "tag"
			return "https://$domain/$prefix/${it.key}/" + if (page > 1) "page/$page/" else ""
		}
		return when (order) {
			SortOrder.POPULARITY -> "https://$domain/top/" + if (page > 1) "page/$page/" else ""
			else -> "https://$domain/" + if (page > 1) "page/$page/" else ""
		}
	}

	private fun parseList(doc: Document): List<Content> {
		val seen = LinkedHashSet<String>()
		return doc.select(".gridPosts, article, .posts .post").mapNotNull { item ->
			val a = item.selectFirst("a[href]") ?: return@mapNotNull null
			val href = a.attr("href").toAbsoluteUrl(domain).substringBefore("?")
			if (!href.contains(domain) || !seen.add(href)) return@mapNotNull null
			val img = item.selectFirst("img")
			val title = item.selectFirst("h3 a, h2 a")?.text()?.trim()
				?: a.attr("title").takeIf { it.isNotBlank() }
				?: img?.attr("alt")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			Content(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href.removePrefix("https://$domain").removePrefix("http://$domain"),
				publicUrl = href,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = img?.let { imageUrl(it.attr("data-src").ifBlank { it.attr("src") }) },
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun imageUrl(raw: String): String? = raw.takeIf { it.isNotBlank() }?.toAbsoluteUrlOrNull(domain)
}
