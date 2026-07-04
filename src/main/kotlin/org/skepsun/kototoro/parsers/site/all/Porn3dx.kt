package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
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

@ContentSourceParser("PORN3DX", "Porn3dx", "en", type = ContentType.HENTAI_VIDEO)
internal class Porn3dx(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.PORN3DX, pageSize = 38) {

	override val configKeyDomain = ConfigKey.Domain("porn3dx.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.RATING)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isSearchWithFiltersSupported = true, isMultipleTagsSupported = false)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO, ContentType.HENTAI_MANGA),
		tagGroups = listOf(
			ContentTagGroup("Media", setOf(ContentTag("All", "media:all", source), ContentTag("Photos", "media:image", source), ContentTag("Videos", "media:video", source)), true),
			ContentTagGroup("Tags", listOf("3d-porn", "blender", "3d", "sexy", "big-tits", "boobs", "ass", "big-boobs", "picture", "big-ass").mapTo(LinkedHashSet()) {
				ContentTag(it.replace('-', ' ').replaceFirstChar(Char::titlecase), it, source)
			}),
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val html = webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders()).body.string()
		return parseCards(html)
	}

	override suspend fun getDetails(manga: Content): Content {
		val html = webClient.httpGet(manga.publicUrl, getRequestHeaders()).body.string()
		val title = html.extractJsonValue("name")?.html() ?: manga.title
		val description = html.extractJsonValue("description")?.html()?.takeIf { it.isNotBlank() }
		val cover = html.extractJsonValue("thumbnail_url_complete")?.html()?.toAbsoluteUrlOrNull(domain)
			?: Regex("property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
			?: manga.coverUrl
		val tags = Regex("&quot;slug&quot;:&quot;([^&]+)&quot;,&quot;tag_category_id&quot;:[^,]+,&quot;name&quot;:&quot;([^&]+)&quot;")
			.findAll(html).mapTo(LinkedHashSet()) { ContentTag(it.groupValues[2].html(), it.groupValues[1].html(), source) }
		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = if (tags.isNotEmpty()) tags else manga.tags,
			contentRating = ContentRating.ADULT,
			chapters = listOf(ContentChapter(
				id = generateUid(manga.url),
				title = "Open",
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
		val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).body.string()
		val urls = LinkedHashSet<String>()
		Regex("&quot;url_complete&quot;:&quot;([^&]*)&quot;").findAll(html).forEach {
			it.groupValues[1].html().takeIf { url -> url.startsWith("http") }?.let(urls::add)
		}
		Regex("&quot;bunny_guid&quot;:&quot;([^&]+)&quot;").findAll(html).forEach {
			urls.add("https://iframe.mediadelivery.net/play/21030/${it.groupValues[1].html()}")
			urls.add("https://vz-c0fe498e-5ab.b-cdn.net/${it.groupValues[1].html()}/playlist.m3u8")
		}
		return urls.mapIndexed { index, url -> ContentPage(id = generateUid("#"), url = url, preview = null, source = source) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val media = filter.tags.firstOrNull { it.key.startsWith("media:") }?.key?.substringAfter(':') ?: "all"
		val tag = filter.tags.firstOrNull { !it.key.startsWith("media:") }
		val sortBy = when (order) {
			SortOrder.POPULARITY -> "popularity"
			SortOrder.RATING -> "likes"
			else -> "date"
		}
		val query = filter.query?.takeIf { it.isNotBlank() }?.urlEncoded().orEmpty()
		val path = tag?.let { "/tag/${it.key}" }.orEmpty()
		return "https://$domain$path?sort_by=$sortBy&popularity_type=popular_all&order_by=desc&media_type=$media&ai_filter=all&search_type=all&searchQuery=$query&page=$page"
	}

	private fun parseCards(html: String): List<Content> {
		val byJson = Regex("&quot;id&quot;:(\\d+).*?&quot;slug&quot;:&quot;([^&]+)&quot;.*?&quot;src&quot;:&quot;([^&]+)&quot;.*?&quot;post_title&quot;:&quot;([^&]+)&quot;", RegexOption.DOT_MATCHES_ALL)
			.findAll(html).mapNotNullTo(ArrayList()) {
				val id = it.groupValues[1]
				val slug = it.groupValues[2].html()
				val cover = it.groupValues[3].html().substringAfter("src=", it.groupValues[3].html()).toAbsoluteUrlOrNull(domain)
				val title = it.groupValues[4].html()
				val url = "https://$domain/post/$id/$slug"
				Content(generateUid(url), title, emptySet(), "/post/$id/$slug", url, RATING_UNKNOWN, ContentRating.ADULT, cover, emptySet(), null, emptySet(), source = source)
			}
		if (byJson.isNotEmpty()) return byJson.distinctBy { it.id }
			val doc = Jsoup.parse(Parser.unescapeEntities(html, true))
		return doc.select("a[href*=/post/]").mapNotNull { a ->
			val href = a.attr("href").toAbsoluteUrl(domain)
			val title = a.attr("title").ifBlank { a.text() }.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val cover = a.selectFirst("img")?.attr("src")?.toAbsoluteUrlOrNull(domain)
			Content(generateUid(href), title, emptySet(), href.removePrefix("https://$domain"), href, RATING_UNKNOWN, ContentRating.ADULT, cover, emptySet(), null, emptySet(), source = source)
		}.distinctBy { it.id }
	}

	private fun String.html(): String = Parser.unescapeEntities(replace("\\/", "/"), true)

	private fun String.extractJsonValue(name: String): String? {
		return Regex("&quot;$name&quot;:&quot;([^&]*)&quot;").find(this)?.groupValues?.get(1)
			?: Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)
	}
}
