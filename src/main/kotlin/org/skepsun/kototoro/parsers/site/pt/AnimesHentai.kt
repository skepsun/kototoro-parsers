package org.skepsun.kototoro.parsers.site.pt

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.GZipOptions
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseRaw
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

	override suspend fun getFilterOptions(): ContentListFilterOptions {
		val genreTags = runCatching { fetchGenreTags() }.getOrElse { fallbackGenreTags() }
		val yearTags = runCatching { fetchYearTags() }.getOrElse { fallbackYearTags() }
		val letterTags = ('a'..'z').mapTo(LinkedHashSet()) {
			ContentTag(it.uppercase(), "letter:$it", source)
		}
		return ContentListFilterOptions(
			availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
			tagGroups = listOf(
				ContentTagGroup("Gêneros", genreTags, isExclusive = true),
				ContentTagGroup("Ano", yearTags, isExclusive = true),
				ContentTagGroup("Inicial", letterTags, isExclusive = true),
			),
		)
	}

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
		val tags = doc.select(".sgeneros a[href*=/genero/]").mapNotNullTo(LinkedHashSet()) { a ->
			val name = a.text().trim()
			val key = a.attr("href").substringAfter("/genero/", "").trim('/')
			if (name.isNotBlank() && key.isNotBlank()) ContentTag(name, key, source) else null
		}
		val chapterLinks = doc.select(".episodios .episodiotitle a[href*=/episodio/]").ifEmpty {
			doc.select(".episodios .imagen a[href*=/episodio/]")
		}
		val chapters = chapterLinks.mapIndexedNotNull { index, a ->
			val href = a.attr("href").toAbsoluteUrl(domain)
			val item = a.parents().firstOrNull { it.`is`("li") }
			val name = item?.selectFirst(".episodiotitle a[href*=/episodio/]")?.text()?.trim()
				?: a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
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
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()
		val urls = LinkedHashSet<String>()
		urls.addAll(extractMediaUrls(doc, domain))
		doc.select("iframe[src]").forEach { iframe ->
			val iframeUrl = iframe.attr("src").toAbsoluteUrlOrNull(domain) ?: return@forEach
			val iframeDoc = runCatching { webClient.httpGet(iframeUrl, getRequestHeaders()).parseHtml() }.getOrNull()
			iframeDoc?.let {
				urls.addAll(extractMediaUrls(it, "www.blogger.com"))
				urls.addAll(resolveBloggerVideoUrls(iframeUrl, it))
			}
		}
		if (urls.isEmpty()) {
			context.requestBrowserAction(this, chapterUrl)
		}
		return urls.map { url -> ContentPage(id = generateUid(url), url = url, preview = null, source = source) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		filter.query?.takeIf { it.isNotBlank() }?.let {
			return "https://$domain/?s=${it.urlEncoded()}" + if (page > 1) "&page=$page" else ""
		}
		filter.tags.firstOrNull()?.let {
			val path = when {
				it.key.startsWith("year:") -> "ano/${it.key.substringAfter(':')}"
				it.key.startsWith("letter:") -> "genero/${it.key.substringAfter(':')}"
				it.key.startsWith("genre:") -> "genero/${it.key.substringAfter(':')}"
				else -> "genero/${it.key}"
			}
			return "https://$domain/$path/" + if (page > 1) "page/$page/" else ""
		}
		return when (order) {
			SortOrder.POPULARITY -> "https://$domain/mais-assistidos/" + if (page > 1) "page/$page/" else ""
			SortOrder.ALPHABETICAL -> "https://$domain/hentai/" + if (page > 1) "page/$page/" else ""
			else -> "https://$domain/episodio/" + if (page > 1) "page/$page/" else ""
		}
	}

	private suspend fun fetchGenreTags(): Set<ContentTag> {
		val doc = webClient.httpGet("https://$domain/generos/", getRequestHeaders()).parseHtml()
		return doc.select(".wp-content a[href*=/genero/]").mapNotNullTo(LinkedHashSet()) { link ->
			val key = link.attr("href").substringAfter("/genero/", "").trim('/')
			val title = link.text().trim()
			if (key.isBlank() || title.isBlank()) null else ContentTag(title, "genre:$key", source)
		}
	}

	private suspend fun fetchYearTags(): Set<ContentTag> {
		val doc = webClient.httpGet("https://$domain/generos/", getRequestHeaders()).parseHtml()
		return doc.select(".releases a[href*=/ano/]").mapNotNullTo(LinkedHashSet()) { link ->
			val year = link.attr("href").substringAfter("/ano/", "").trim('/')
			if (year.matches(YEAR_REGEX)) ContentTag(year, "year:$year", source) else null
		}
	}

	private fun fallbackGenreTags(): Set<ContentTag> = listOf(
		"3d" to "3D",
		"sem-censura" to "Sem Censura",
		"aventura" to "Aventura",
		"comedia" to "Comédia",
		"empregada" to "Empregada",
		"enfermeira" to "Enfermeira",
		"fantasia" to "Fantasia",
		"futanari" to "Futanari",
		"harem" to "Harem",
		"incesto" to "Incesto",
		"lolicon" to "Lolicon",
		"peitoes" to "Peitoes",
		"professora" to "Professora",
		"punicao" to "Punicao",
		"romance" to "Romance",
		"shotacon" to "Shotacon",
		"tentaculos" to "Tentaculos",
		"vida-escolar" to "Vida Escolar",
		"yaoi" to "Yaoi",
		"yuri" to "Yuri",
	).mapTo(LinkedHashSet()) { (key, title) -> ContentTag(title, "genre:$key", source) }

	private fun fallbackYearTags(): Set<ContentTag> = (2026 downTo 2017).mapTo(LinkedHashSet()) {
		val year = it.toString()
		ContentTag(year, "year:$year", source)
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

	private fun extractMediaUrls(doc: Document, fallbackDomain: String): List<String> {
		val urls = LinkedHashSet<String>()
		doc.select("video[src], video source[src], source[src], meta[property=og:video], meta[property=og:video:url]").forEach { element ->
			val raw = element.attr("src").ifBlank { element.attr("content") }
			raw.toAbsoluteUrlOrNull(fallbackDomain)?.let(urls::add)
		}
		MEDIA_URL_REGEX.findAll(doc.outerHtml().replace("\\/", "/")).forEach { match ->
			urls.add(match.value.replace("\\u003d", "=").replace("&amp;", "&"))
		}
		return urls.toList()
	}

	private suspend fun resolveBloggerVideoUrls(iframeUrl: String, doc: Document): List<String> {
		val token = BLOGGER_TOKEN_REGEX.find(iframeUrl)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
			?: return emptyList()
		val html = doc.outerHtml()
		val buildLabel = BLOGGER_BUILD_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
			?: return emptyList()
		val fReq = """[[["WcwnYd","[\"$token\",null,0]",null,"generic"]]]"""
		val headers = Headers.Builder()
			.add("User-Agent", UserAgents.CHROME_DESKTOP)
			.add("Accept", "*/*")
			.add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
			.add("Origin", "https://www.blogger.com")
			.add("Referer", "https://www.blogger.com/")
			.add("X-Same-Domain", "1")
			.build()
		val sidParams = listOf(null, "0")
		val languages = listOf("zh-CN", "en-US")
		for (sid in sidParams) {
			for (language in languages) {
				val sidQuery = sid?.let { "&f.sid=${it.urlEncoded()}" }.orEmpty()
				val endpoint = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
					"?rpcids=WcwnYd&source-path=%2Fvideo.g$sidQuery&bl=${buildLabel.urlEncoded()}&hl=${language.urlEncoded()}&_reqid=82895&rt=c"
				val body = runCatching {
					postBloggerRpc(endpoint, "f.req=${fReq.urlEncoded()}", headers)
				}.getOrNull() ?: continue
				val urls = BLOGGER_VIDEO_URL_REGEX.findAll(body).mapTo(LinkedHashSet()) {
					decodeBloggerUrl(it.value)
				}.sortedByDescending { url ->
					url.substringAfter("itag=", "0").substringBefore('&').toIntOrNull() ?: 0
				}
				if (urls.isNotEmpty()) return urls
			}
		}
		return emptyList()
	}

	private suspend fun postBloggerRpc(url: String, payload: String, headers: Headers): String {
		val request = Request.Builder()
			.url(url.toHttpUrl())
			.headers(headers)
			.post(payload.toRequestBody(null))
			.tag(GZipOptions::class.java, GZipOptions(skip = true))
			.build()
		return context.httpClient.newCall(request).await().parseRaw()
	}

	private fun decodeBloggerUrl(value: String): String = value
		.replace("\\u003d", "=")
		.replace("\\u0026", "&")
		.replace("\\/", "/")
		.replace("&amp;", "&")
		.trimEnd('\\')

	private fun imageUrl(raw: String): String? = raw.takeIf { it.isNotBlank() }?.toAbsoluteUrlOrNull(domain)

	private companion object {
		private val MEDIA_URL_REGEX = Regex("https?://[^\"'\\s<>]+\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
		private val BLOGGER_TOKEN_REGEX = Regex("[?&]token=([^&]+)")
		private val BLOGGER_BUILD_REGEX = Regex(""""cfb2h"\s*:\s*"([^"]+)"""")
		private val BLOGGER_VIDEO_URL_REGEX = Regex("https://[^\"\\s]+googlevideo\\.com/[^\"\\s]+", RegexOption.IGNORE_CASE)
		private val YEAR_REGEX = Regex("""\d{4}""")
	}
}
