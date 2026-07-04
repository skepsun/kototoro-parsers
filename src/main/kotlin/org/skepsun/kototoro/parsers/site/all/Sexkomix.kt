package org.skepsun.kototoro.parsers.site.all

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

@ContentSourceParser("SEXKOMIX", "Sexkomix", type = ContentType.HENTAI_MANGA)
internal class Sexkomix(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.SEXKOMIX, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("sexkomix2.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.RATING)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isSearchWithFiltersSupported = true, isMultipleTagsSupported = false)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$domain/home/?lang=pt")
		.add("Cookie", "confirm=true; lang=pt")
		.build()

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_MANGA),
		tagGroups = listOf(
			ContentTagGroup("Idioma", listOf("pt", "en", "es", "de", "ru").map { ContentTag(it.uppercase(), "lang:$it", source) }.toSet(), true),
			ContentTagGroup("Tags", listOf(
				"3d", "ai generated", "anal", "anime", "aventuras", "bdsm", "boquete", "bunda grande",
				"grandes paus", "hentai", "incesto", "lésbicas", "milf", "morena", "peitos grandes", "simpsons",
			).mapTo(LinkedHashSet()) { ContentTag(it.replaceFirstChar(Char::titlecase), it, source) }),
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		return parseList(webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
		val title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: doc.selectFirst("#comix_description h1, h1")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst("meta[property=og:description], meta[name=Description]")?.attr("content")?.takeIf { it.isNotBlank() }
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
			?: doc.selectFirst("#comix_cover_img")?.let { imageUrl(it.attr("data-src").ifBlank { it.attr("src") }) }
			?: manga.coverUrl
		val tags = doc.select(".tags_ul a[href]").mapNotNullTo(LinkedHashSet()) { a ->
			val key = a.attr("href").substringAfter("t=", "").trim()
			val text = a.text().trim()
			if (key.isNotBlank() && text.isNotBlank()) ContentTag(text, key, source) else null
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
		doc.select("#comix_pages_ul a.fancybox[href], #comix_pages_ul img[data-src], #comix_pages_ul img[src]").forEach {
			imageUrl(it.attr("href").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } })?.let(urls::add)
		}
		return urls.mapIndexed { index, url -> ContentPage(id = generateUid("#"), url = url, preview = null, source = source) }
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val language = filter.tags.firstOrNull { it.key.startsWith("lang:") }?.key?.substringAfter(':') ?: "pt"
		filter.query?.takeIf { it.isNotBlank() }?.let {
			return "https://$domain/search/?lang=$language&q=${it.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		filter.tags.firstOrNull { !it.key.startsWith("lang:") }?.let {
			return "https://$domain/tag_pagex/?lang=$language&t=${it.key.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		val sort = when (order) {
			SortOrder.POPULARITY -> "prosmotr"
			SortOrder.RATING -> "like"
			else -> "date"
		}
		return "https://$domain/home/?lang=$language&sort=$sort" + if (page > 1) "&page=$page" else ""
	}

	private fun parseList(doc: Document): List<Content> = doc.select("li.comix").mapNotNull { item ->
		val a = item.selectFirst("a[href*=comicsx_]") ?: return@mapNotNull null
		val href = a.attr("href").toAbsoluteUrl(domain)
		val img = item.selectFirst(".comix_img")
		val title = item.selectFirst(".comix_title")?.text()?.trim()
			?: img?.attr("alt")?.takeIf { it.isNotBlank() }
			?: return@mapNotNull null
		val tags = item.select(".tags_ul a[href]").mapNotNullTo(LinkedHashSet()) { tag ->
			val key = tag.attr("href").substringAfter("t=", "").trim()
			val text = tag.text().trim()
			if (key.isNotBlank() && text.isNotBlank()) ContentTag(text, key, source) else null
		}
		Content(
			generateUid(href), title, emptySet(), href.removePrefix("https://$domain").removePrefix("http://$domain"),
			href, RATING_UNKNOWN, ContentRating.ADULT, img?.let { imageUrl(it.attr("data-src").ifBlank { it.attr("src") }) },
			tags, null, emptySet(), source = source,
		)
	}

	private fun imageUrl(raw: String): String? = raw.takeIf { it.isNotBlank() }?.toAbsoluteUrlOrNull(domain)
}
