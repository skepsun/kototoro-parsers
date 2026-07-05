package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.net.URLDecoder
import java.util.EnumSet

@ContentSourceParser("PORN3DX", "Porn3dx Videos", "en", type = ContentType.HENTAI_VIDEO)
internal class Porn3dx(context: ContentLoaderContext) :
	Porn3dxBase(context, ContentParserSource.PORN3DX, ContentType.HENTAI_VIDEO, mediaType = "video")

@ContentSourceParser("PORN3DX_IMAGE", "Porn3dx Images", "en", type = ContentType.HENTAI_MANGA)
internal class Porn3dxImage(context: ContentLoaderContext) :
	Porn3dxBase(context, ContentParserSource.PORN3DX_IMAGE, ContentType.HENTAI_MANGA, mediaType = "image")

internal abstract class Porn3dxBase(
	context: ContentLoaderContext,
	source: ContentParserSource,
	private val contentType: ContentType,
	private val mediaType: String,
) : PagedContentParser(context, source, pageSize = 38) {

	override val configKeyDomain = ConfigKey.Domain("porn3dx.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.RATING)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isSearchWithFiltersSupported = true, isMultipleTagsSupported = false)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(contentType),
		tagGroups = listOf(
			ContentTagGroup(
				title = "AI",
				tags = linkedSetOf(
					ContentTag("All", "ai:all", source),
					ContentTag("Original", "ai:hide_ai", source),
					ContentTag("AI Generated", "ai:ai_only", source),
				),
				isExclusive = true,
			),
			ContentTagGroup(
				title = "Tags",
				tags = listOf(
					"original" to "Original",
					"ai-generated" to "AI Generated",
					"3d-porn" to "3d Porn",
					"blender" to "Blender",
					"3d" to "3d",
					"sexy" to "Sexy",
					"big-tits" to "Big Tits",
					"boobs" to "Boobs",
					"ass" to "Ass",
					"bigboobs" to "Big boobs",
					"picture" to "Picture",
					"big-ass" to "Big Ass",
					"animation" to "Animation",
					"animated" to "Animated",
					"sound" to "Sound",
					"futanari" to "Futanari",
					"anal" to "Anal",
				).mapTo(LinkedHashSet()) { (key, title) -> ContentTag(title, key, source) },
			),
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
				title = if (mediaType == "video") "Watch" else "Open",
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
		if (mediaType == "video") {
			extractBunnyGuids(html).forEach { guid ->
				urls.add("https://vz-c0fe498e-5ab.b-cdn.net/$guid/playlist.m3u8")
			}
		} else {
			Regex("&quot;url_complete&quot;:&quot;([^&]*)&quot;").findAll(html).forEach {
				it.groupValues[1].html().takeIf { url -> url.startsWith("http") }?.let(urls::add)
			}
		}
		return urls.map { url -> ContentPage(id = generateUid(url), url = url, preview = null, source = source) }
	}

	private fun extractBunnyGuids(html: String): Set<String> {
		return Regex("""(?:&quot;|")bunny_guid(?:&quot;|")\s*:\s*(?:&quot;|")([^"&]+)(?:&quot;|")""")
			.findAll(html)
			.mapTo(LinkedHashSet()) { it.groupValues[1].html() }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val aiFilter = filter.tags.firstOrNull { it.key.startsWith("ai:") }?.key?.substringAfter(':') ?: "all"
		val tag = filter.tags.firstOrNull { !it.key.startsWith("ai:") }
		val sortBy = when (order) {
			SortOrder.POPULARITY -> "popularity"
			SortOrder.RATING -> "likes"
			else -> "date"
		}
		val query = filter.query?.takeIf { it.isNotBlank() }?.urlEncoded().orEmpty()
		val path = tag?.let { "/tag/${it.key}" }.orEmpty()
		return "https://$domain$path?sort_by=$sortBy&popularity_type=popular_all&order_by=desc&media_type=$mediaType&ai_filter=$aiFilter&search_type=all&searchQuery=$query&page=$page"
	}

	private fun parseCards(html: String): List<Content> {
		val byLivewire = parseLivewireCards(html)
		if (byLivewire.isNotEmpty()) return byLivewire
		return parseHtmlCards(html)
	}

	private fun parseLivewireCards(html: String): List<Content> {
		return INITIAL_DATA_ATTR_REGEX.findAll(html).mapNotNull { match ->
			val attr = match.groupValues[1].html()
			runCatching { JSONObject(attr) }.getOrNull()
		}.firstNotNullOfOrNull { data ->
			val fingerprint = data.optJSONObject("fingerprint")
			if (fingerprint?.optString("name") != "homepage-gallery") return@firstNotNullOfOrNull null
			val items = data.optJSONObject("serverMemo")
				?.optJSONObject("data")
				?.optJSONArray("images")
				?: return@firstNotNullOfOrNull null
			items.toContents()
		}.orEmpty()
	}

	private fun JSONArray.toContents(): List<Content> {
		val contents = ArrayList<Content>(length())
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val id = item.optLong("id").takeIf { it > 0 } ?: continue
			val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
			val title = item.optString("post_title").html().takeIf { it.isNotBlank() } ?: continue
			val publicUrl = "https://$domain/post/$id/$slug"
			val cover = item.optString("src").html().cleanCoverUrl()
			val tags = linkedSetOf<ContentTag>()
			if (item.optBoolean("is_ai")) tags.add(ContentTag("AI Generated", "ai-generated", source))
			if (item.optBoolean("is_video")) tags.add(ContentTag("Video", "video", source)) else tags.add(ContentTag("Image", "image", source))
			contents += Content(
				id = generateUid(publicUrl),
				title = title,
				altTitles = emptySet(),
				url = "/post/$id/$slug",
				publicUrl = publicUrl,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = cover,
				tags = tags,
				state = null,
				authors = item.optString("username").takeIf { it.isNotBlank() }?.let(::setOf) ?: emptySet(),
				source = source,
			)
		}
		return contents.distinctBy { it.id }
	}

	private fun parseHtmlCards(html: String): List<Content> {
		val doc = org.jsoup.Jsoup.parse(Parser.unescapeEntities(html, true))
		return doc.select("a[href*=/post/]").mapNotNull { a ->
			val href = a.attr("href").toAbsoluteUrl(domain)
			val title = a.attr("title").ifBlank { a.text() }.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val cover = a.selectFirst("img")?.attr("src")?.toAbsoluteUrlOrNull(domain)
			Content(generateUid(href), title, emptySet(), href.removePrefix("https://$domain"), href, RATING_UNKNOWN, ContentRating.ADULT, cover, emptySet(), null, emptySet(), source = source)
		}.distinctBy { it.id }
	}

	private fun String.cleanCoverUrl(): String? {
		if (isBlank()) return null
		val src = if (startsWith("https://$domain/normal/")) {
			substringAfter("src=", this).substringBefore('&').let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
		} else {
			this
		}
		return src.toAbsoluteUrlOrNull(domain)
	}

	private fun String.html(): String = Parser.unescapeEntities(replace("\\/", "/"), true)

	private fun String.extractJsonValue(name: String): String? {
		return Regex("&quot;$name&quot;:&quot;([^&]*)&quot;").find(this)?.groupValues?.get(1)
			?: Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)
	}

	private companion object {
		private val INITIAL_DATA_ATTR_REGEX = Regex("""wire:initial-data="([^"]+)"""")
	}
}
