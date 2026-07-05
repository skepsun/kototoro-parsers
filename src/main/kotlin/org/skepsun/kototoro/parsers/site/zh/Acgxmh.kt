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
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.net.URI
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

	protected fun pathTag(title: String, path: String): ContentTag = ContentTag(title, "path:$path", source)

	protected suspend fun loadIndexTags(title: String, path: String, pages: Int = 1, limit: Int = 24): ContentTagGroup {
		val tags = LinkedHashSet<ContentTag>()
		for (page in 1..pages) {
			val doc = runCatching {
				webClient.httpGet(buildPagedPathUrl(path, page), getRequestHeaders()).parseHtml()
			}.getOrNull() ?: continue
			doc.select("dl.specials dd > a[href]").asSequence()
				.mapNotNull { a ->
					val name = a.selectFirst(".special-title")?.text()?.trim()
						?: a.attr("title").takeIf { it.isNotBlank() }
						?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
					val itemPath = a.attr("href").toSitePath()
					if (name.isNullOrBlank() || itemPath.isNullOrBlank()) null else pathTag(name, itemPath)
				}
				.forEach { tag ->
					if (tags.size < limit) tags.add(tag)
				}
			if (tags.size >= limit) break
		}
		return ContentTagGroup(title, tags, isExclusive = true)
	}

	protected fun buildPagedPathUrl(path: String, page: Int): String {
		val pagedPath = when {
			page <= 1 -> path
			path.matches(Regex("^/special/\\d+/$")) -> "$path$page"
			path.endsWith("/") -> "${path}index-$page.html"
			path.endsWith(".html") -> path.removeSuffix(".html") + "-$page.html"
			else -> "$path/index-$page.html"
		}
		return "https://$domain$pagedPath"
	}

	protected fun String.toSitePath(): String? {
		val absolute = toAbsoluteUrlOrNull(domain) ?: return null
		return runCatching { URI(absolute) }.getOrNull()?.let { uri ->
			uri.rawPath?.takeIf { it.isNotBlank() }?.let { path ->
				if (uri.rawQuery.isNullOrBlank()) path else "$path?${uri.rawQuery}"
			}
		}
	}

	protected fun Document.extractRelatedTags(): LinkedHashSet<ContentTag> {
		return select(".manga-tags a[href*=/tags/], .info span:matchesOwn((相关)?标签：) a[href*=/tags/]")
			.mapNotNullTo(LinkedHashSet()) {
				val name = it.text().trim()
				val key = it.attr("href").substringAfterLast('/').substringBefore('.')
				if (name.isNotBlank() && key.isNotBlank()) ContentTag(name, key, source) else null
			}
	}
}

@ContentSourceParser("ACGXMH", "ACG漫画网", "zh", type = ContentType.HENTAI_MANGA)
internal class Acgxmh(context: ContentLoaderContext) :
	AcgxmhBase(context, ContentParserSource.ACGXMH, pageSize = 36) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = false)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.HENTAI_MANGA),
		tagGroups = listOf(
			ContentTagGroup(
				"栏目",
				linkedSetOf(
					pathTag("漫画", "/h/"),
					pathTag("图集", "/hentai/"),
					pathTag("全彩", "/tags/full-color.html"),
					pathTag("网漫", "/webtoon/"),
					pathTag("西漫", "/western/"),
				),
				isExclusive = true,
			),
			loadIndexTags("专题", "/special/"),
			loadIndexTags("戏仿", "/anime/"),
			loadIndexTags("角色", "/characters/", pages = 2, limit = 48),
			loadIndexTags("主题标签", "/tags/", pages = 2, limit = 48),
			loadIndexTags("作品艺术家", "/artist/", pages = 2, limit = 48),
			loadIndexTags("组织主题", "/circle/"),
			loadIndexTags("Coser", "/coser/"),
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		return parseList(webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
		val title = doc.selectFirst("h1.title, h1")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
		val cover = doc.selectFirst(".content img, #content img")?.let { imageUrl(it.attr("src")) } ?: manga.coverUrl
		val tags = doc.extractRelatedTags()
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
			it.key.removePrefix("path:").takeIf { path -> path != it.key }?.let { path ->
				return buildPagedPathUrl(path, page)
			}
		}
		return when (order) {
			SortOrder.POPULARITY -> "https://$domain/hot/" + if (page > 1) "index-$page.html" else ""
			else -> buildPagedPathUrl("/h/", page)
		}
	}

	private fun parseList(doc: Document): List<Content> = doc.select("#list li").mapNotNull { item ->
		val a = item.selectFirst("a.thumb[href], a.title[href]") ?: return@mapNotNull null
		val href = a.attr("href").toAbsoluteUrl(domain)
		if (!href.removePrefix("https://$domain").isImageContentPath()) return@mapNotNull null
		val img = item.selectFirst("img")
		val title = item.selectFirst("a.title")?.text()?.trim()
			?: img?.attr("alt")?.takeIf { it.isNotBlank() }
			?: return@mapNotNull null
		val tags = item.select(".top-tags, .lang").mapNotNullTo(LinkedHashSet()) {
			val text = it.text().trim()
			if (text.isNotBlank()) ContentTag(text, text, source) else null
		}
		Content(generateUid(href), title, emptySet(), href.removePrefix("https://$domain"), href, RATING_UNKNOWN, ContentRating.ADULT, img?.let { imageUrl(it.attr("src")) }, tags, null, emptySet(), source = source)
	}.distinctBy { it.id }

	private fun String.isImageContentPath(): Boolean {
		return startsWith("/h/") || startsWith("/hentai/") || startsWith("/webtoon/") || startsWith("/western/")
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
		tagGroups = listOf(
			ContentTagGroup(
				"栏目",
				linkedSetOf(
					pathTag("H动画", "/gif/"),
					pathTag("里番剧", "/hanime/"),
					pathTag("有声/ASMR", "/asmr/"),
				),
				isExclusive = true,
			),
			loadIndexTags("主播", "/cv/"),
			loadIndexTags("戏仿", "/anime/", limit = 12),
			loadIndexTags("角色", "/characters/", pages = 2, limit = 24),
			loadIndexTags("主题标签", "/tags/", pages = 2, limit = 24),
			loadIndexTags("作品艺术家", "/artist/", pages = 2, limit = 24),
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
		val tags = doc.extractRelatedTags()
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
		val signedPlaylists = urls.filter { it.isSignedMasterPlaylist() }
		if (signedPlaylists.isNotEmpty()) {
			return signedPlaylists
				.flatMap { expandMasterPlaylist(it) }
				.distinct()
				.map { url -> ContentPage(id = generateUid(url), url = url, preview = null, source = source) }
		}
		val playableUrls = LinkedHashSet<String>()
		urls.forEach { url ->
			if (url.isMasterPlaylist()) {
				playableUrls.addAll(expandMasterPlaylist(url))
			} else {
				playableUrls.add(url)
			}
		}
		return playableUrls.map { url -> ContentPage(id = generateUid(url), url = url, preview = null, source = source) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		filter.query?.takeIf { it.isNotBlank() }?.let {
			return "https://$domain/?q=${it.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		filter.tags.firstOrNull()?.let {
			it.key.removePrefix("path:").takeIf { path -> path != it.key }?.let { path ->
				return buildPagedPathUrl(path, page)
			}
		}
		return buildPagedPathUrl(if (order == SortOrder.POPULARITY) "/hanime/" else "/gif/", page)
	}

	private fun parseList(doc: Document): List<Content> = (doc.select(".grid-item") + doc.select("#list li")).mapNotNull { item ->
		val a = item.selectFirst("a.thumb[href], a[href*=/gif/], a[href*=/hanime/]") ?: return@mapNotNull null
		val href = a.attr("href").toAbsoluteUrl(domain)
		if (!href.removePrefix("https://$domain").isVideoContentPath()) return@mapNotNull null
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

	private fun String.isVideoContentPath(): Boolean {
		return startsWith("/gif/") || startsWith("/hanime/") || startsWith("/asmr/")
	}

	private fun String.isMasterPlaylist(): Boolean {
		val path = runCatching { URI(this).rawPath }.getOrNull() ?: return false
		return path.endsWith("/master.m3u8") || path.endsWith("/index.m3u8")
	}

	private fun String.isSignedMasterPlaylist(): Boolean {
		val uri = runCatching { URI(this) }.getOrNull() ?: return false
		val query = uri.rawQuery ?: return false
		return (uri.rawPath.endsWith("/master.m3u8") || uri.rawPath.endsWith("/index.m3u8")) &&
			query.split('&').any { it.substringBefore('=') == "m" }
	}

	private suspend fun expandMasterPlaylist(masterUrl: String): List<String> {
		val masterUri = URI(masterUrl)
		val masterQuery = masterUri.rawQuery?.takeIf { it.isNotBlank() } ?: return listOf(masterUrl)
		val masterPath = masterUri.rawPath
		val baseUrl = masterUrl.substringBefore('?').substringBeforeLast('/') + "/"
		val playlist = runCatching {
			webClient.httpGet(masterUrl, getRequestHeaders()).parseRaw()
		}.getOrElse {
			return listOf(masterUrl)
		}
		val childUrls = playlist.lineSequence()
			.map { it.trim() }
			.filter { it.isNotBlank() && !it.startsWith("#") }
			.map { child -> child.toPlaylistUrl(baseUrl, masterUri) }
			.filter { it.contains(".m3u8", ignoreCase = true) }
			.toList()
		if (childUrls.isEmpty()) return listOf(masterUrl)
		val finalQuery = "$masterQuery&from=${masterPath.urlEncoded()}"
		return childUrls.map { child ->
			val separator = if (child.contains('?')) "&" else "?"
			if (child.contains("m=")) child else "$child$separator$finalQuery"
		}
	}

	private fun String.toPlaylistUrl(baseUrl: String, masterUri: URI): String = when {
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "${masterUri.scheme}:$this"
		startsWith("/") -> "${masterUri.scheme}://${masterUri.host}$this"
		else -> baseUrl + this
	}

	private companion object {
		private val VIDEO_URL_REGEX = Regex("https?://[^\"'\\s<>]+\\.(?:m3u8|mp4|webm)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
	}
}
