package org.skepsun.kototoro.parsers.site.pt

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

@ContentSourceParser("ANIMESHENTAI", "AnimesHentai", "pt", type = ContentType.HENTAI_VIDEO)
internal class AnimesHentai(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.ANIMESHENTAI, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("animeshentai.biz")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = false)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$domain/")
		.add("Cookie", "ageVerified=true")
		.build()

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
		availableTags = listOf("romance", "masturbacao", "peitoes", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j").mapTo(LinkedHashSet()) {
			ContentTag(it.replace('-', ' ').replaceFirstChar(Char::titlecase), it, source)
		},
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		return parseList(webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
		val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Todos")
			?: doc.selectFirst("h1")?.text()?.trim()
			?: manga.title
		val description = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: doc.selectFirst(".wp-content")?.text()?.trim()
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
			?: doc.selectFirst(".poster img")?.let { imageUrl(it.attr("src")) }
			?: manga.coverUrl
		val tags = doc.select(".sgeneros a[href], a[href*=/genero/]").mapNotNullTo(LinkedHashSet()) { a ->
			val name = a.text().trim()
			val key = a.attr("href").substringAfter("/genero/", "").trim('/')
			if (name.isNotBlank() && key.isNotBlank()) ContentTag(name, key, source) else null
		}
		val chapters = doc.select(".episodios a[href*=/episodio/], a[href*=/episodio/]").mapIndexedNotNull { index, a ->
			val href = a.attr("href").toAbsoluteUrl(domain)
			val name = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
			if (name.isBlank()) null else ContentChapter(
				id = generateUid(href),
				title = name,
				number = index + 1f,
				volume = 0,
				url = href.removePrefix("https://$domain"),
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy { it.url }
		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = if (tags.isNotEmpty()) tags else manga.tags,
			contentRating = ContentRating.ADULT,
			chapters = chapters.ifEmpty {
				listOf(ContentChapter(
					id = generateUid(manga.url),
					title = "Watch",
					number = 1f,
					volume = 0,
					url = manga.url,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				))
			},
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		val urls = LinkedHashSet<String>()
		doc.select("iframe[src], video[src], video source[src]").forEach { it.attr("src").toAbsoluteUrlOrNull(domain)?.let(urls::add) }
		Regex("https?://[^\"'\\s<>]+\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
			.findAll(doc.outerHtml()).mapTo(urls) { it.value }
		return urls.mapIndexed { index, url -> ContentPage(id = generateUid("#"), url = url, preview = null, source = source) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		filter.query?.takeIf { it.isNotBlank() }?.let {
			return "https://$domain/?s=${it.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		filter.tags.firstOrNull()?.let {
			return "https://$domain/genero/${it.key}/" + if (page > 1) "page/$page/" else ""
		}
		return when (order) {
			SortOrder.POPULARITY -> "https://$domain/mais-assistidos/" + if (page > 1) "page/$page/" else ""
			SortOrder.ALPHABETICAL -> "https://$domain/hentai/" + if (page > 1) "page/$page/" else ""
			else -> "https://$domain/episodio/" + if (page > 1) "page/$page/" else ""
		}
	}

	private fun parseList(doc: Document): List<Content> {
		val seen = LinkedHashSet<String>()
		return doc.select("article.item").mapNotNull { item ->
			val a = item.selectFirst("a[href]") ?: return@mapNotNull null
			val href = a.attr("href").toAbsoluteUrl(domain)
			if (!seen.add(href)) return@mapNotNull null
			val img = item.selectFirst("img")
			val title = item.selectFirst("h3 a")?.text()?.trim()
				?: img?.attr("alt")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			Content(generateUid(href), title, emptySet(), href.removePrefix("https://$domain"), href, RATING_UNKNOWN, ContentRating.ADULT, img?.let { imageUrl(it.attr("src")) }, emptySet(), null, emptySet(), source = source)
		}
	}

	private fun imageUrl(raw: String): String? = raw.takeIf { it.isNotBlank() }?.toAbsoluteUrlOrNull(domain)
}
