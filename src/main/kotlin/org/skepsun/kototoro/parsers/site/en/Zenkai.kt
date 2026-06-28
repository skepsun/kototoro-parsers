package org.skepsun.kototoro.parsers.site.en

import org.json.JSONObject
import org.jsoup.Jsoup
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
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("ZENKAI", "Zenkai", "en", type = ContentType.VIDEO)
internal class Zenkai(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ZENKAI, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("zenkai.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
        availableTags = buildFilterTags(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removeSuffix(" - Zenkai")?.trim()
            ?: doc.selectFirst(".binfo h1.title")?.text()?.trim() ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".binfo .synopsis .content")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".binfo .poster img")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val mangaId = doc.selectFirst("script:containsData(mangaId)")?.data()
            ?.let { Regex("mangaId\\s*=\\s*(\\d+)").find(it)?.groupValues?.get(1) }
            ?: doc.selectFirst("[data-id]")?.attr("data-id")

        val tags = doc.select("a[href*=/genre/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val mangaSlug = manga.publicUrl.substringAfterLast('/').ifBlank { mangaId ?: "" }
        val chapters = if (mangaId != null) {
            fetchEpisodeList(mangaId, mangaSlug)
        } else {
            emptyList()
        }

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            contentRating = ContentRating.SAFE,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters.ifEmpty {
                listOf(ContentChapter(
                    id = generateUid(manga.url), url = manga.url, title = "Watch",
                    number = 1f, uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                ))
            },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url
        val epUrl = chapterUrl.substringBefore("?").toAbsoluteUrl(domain)
        val queryPart = chapterUrl.substringAfter("?", "")
        val dataIds = queryPart.substringAfter("ids=", "").substringBefore("&")
        val animeId = queryPart.substringAfter("animeId=", "").substringBefore("&")

        if (dataIds.isBlank() || animeId.isBlank()) {
            return trySimpleExtraction(epUrl)
        }

        val results = mutableListOf<ContentPage>()

        try {
            val serverIds = fetchServerList(dataIds)
            for (serverId in serverIds) {
                try {
                    val embedUrl = fetchEmbedUrl(serverId)
                    val videos = extractVideoFromEmbed(embedUrl, epUrl)
                    results.addAll(videos)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        if (results.isNotEmpty()) return results
        return trySimpleExtraction(epUrl)
    }

    private suspend fun trySimpleExtraction(epUrl: String): List<ContentPage> {
        try {
            val response = webClient.httpGet(epUrl, getRequestHeaders())
            val doc = response.parseHtml()

            val videoEl = doc.selectFirst("video source[src], video[src], iframe[src]")
            val videoUrl = videoEl?.attr("src")?.takeIf { it.startsWith("http") }
                ?: videoEl?.attr("data-src")?.takeIf { it.startsWith("http") }

            if (videoUrl != null) {
                return listOf(ContentPage(
                    id = generateUid("video:${epUrl}"),
                    url = videoUrl, preview = null, source = source,
                ))
            }
        } catch (_: Exception) {
        }
        return emptyList()
    }

    private fun getAjaxHeaders(referer: String): Headers = Headers.Builder()
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("Referer", referer)
        .add("User-Agent", context.getDefaultUserAgent())
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun fetchServerIds(animeId: String, episodeNum: Float): String? {
        return try {
            val url = "https://$domain/ajax/episode/list/$animeId"
            val response = webClient.httpGet(url, getAjaxHeaders("https://$domain/"))
            val doc = response.parseHtml()
            val epNumStr = episodeNum.toInt().toString()
            val element = doc.select("li a[data-num]").firstOrNull { it.attr("data-num") == epNumStr }
            element?.attr("data-ids")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchServerList(ids: String): List<String> {
        val url = "https://$domain/ajax/server/list?servers=$ids"
        val response = webClient.httpGet(url, getAjaxHeaders("https://$domain/"))
        val raw = response.body.string()
        val html = try {
            JSONObject(raw).getString("result")
        } catch (_: Exception) {
            raw
        }
        val doc = Jsoup.parse(html)
        return doc.select("li[data-link-id]").mapNotNull { el ->
            el.attr("data-link-id").takeIf { it.isNotBlank() }
        }
    }

    private suspend fun fetchEmbedUrl(serverId: String): String {
        val url = "https://$domain/ajax/server?get=$serverId"
        val response = webClient.httpGet(url, getAjaxHeaders("https://$domain/"))
        val json = JSONObject(response.body.string())
        return json.getJSONObject("result").getString("url")
    }

    private suspend fun extractVideoFromEmbed(embedUrl: String, referer: String): List<ContentPage> {
        val host = try {
            embedUrl.substringAfter("://").substringBefore("/")
        } catch (_: Exception) {
            ""
        }
        val origin = if (host.isNotEmpty()) "https://$host" else "https://$domain"

        val pageHeaders = Headers.Builder()
            .add("Referer", referer)
            .add("User-Agent", context.getDefaultUserAgent())
            .build()

        val pageBody = webClient.httpGet(embedUrl, pageHeaders).body.string()

        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return extractFromApi(dataId, host, embedUrl, referer, origin)
        }

        val iframeSrc = Regex("""<iframe[^>]+src="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolvedSrc = if (iframeSrc.startsWith("http")) iframeSrc
            else embedUrl.substringBeforeLast("/") + "/" + iframeSrc.trimStart('/')
            return extractVideoFromEmbed(resolvedSrc, embedUrl)
        }

        val directM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageBody)?.groupValues?.get(0)
        if (directM3u8 != null) {
            return listOf(ContentPage(
                id = generateUid("m3u8:$embedUrl"), url = directM3u8,
                preview = null, source = source,
                headers = mapOf("Referer" to origin, "Origin" to origin),
            ))
        }

        val sourceSrc = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""").find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            return listOf(ContentPage(
                id = generateUid("source:$embedUrl"), url = sourceSrc,
                preview = null, source = source,
                headers = mapOf("Referer" to origin, "Origin" to origin),
            ))
        }

        val jsVarUrl = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']"""
        ).find(pageBody)?.let { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotEmpty)
                ?: match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)
        }
        if (jsVarUrl != null) {
            val resolved = if (jsVarUrl.startsWith("http")) jsVarUrl
            else embedUrl.substringBeforeLast("/") + "/" + jsVarUrl.trimStart('/')
            return listOf(ContentPage(
                id = generateUid("jsvar:$embedUrl"), url = resolved,
                preview = null, source = source,
                headers = mapOf("Referer" to origin, "Origin" to origin),
            ))
        }

        return emptyList()
    }

    private suspend fun extractFromApi(
        dataId: String, host: String, embedUrl: String,
        referer: String, origin: String,
    ): List<ContentPage> {
        val apiHeaders = Headers.Builder()
            .add("Accept", "*/*")
            .add("Referer", embedUrl)
            .add("Origin", origin)
            .add("User-Agent", context.getDefaultUserAgent())
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val m3u8Url = try {
            val response = webClient.httpGet("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders)
            val json = JSONObject(response.body.string())
            json.getString("sources")
        } catch (_: Exception) {
            null
        } ?: try {
            val response = webClient.httpGet("https://$host/stream/getSourcesNew?id=$dataId&id=$dataId", apiHeaders)
            val json = JSONObject(response.body.string())
            json.getString("sources")
        } catch (_: Exception) {
            null
        }

        if (m3u8Url != null && m3u8Url.startsWith("http")) {
            return listOf(ContentPage(
                id = generateUid("api:$embedUrl"), url = m3u8Url,
                preview = null, source = source,
                headers = mapOf("Referer" to origin, "Origin" to origin),
            ))
        }

        return emptyList()
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun fetchEpisodeList(animeId: String, animeSlug: String): List<ContentChapter> {
        return try {
            val url = "https://$domain/ajax/episode/list/$animeId"
            val response = webClient.httpGet(url, getAjaxHeaders("https://$domain/watch/$animeSlug"))
            val doc = response.parseHtml()
            val episodes = doc.select("li a[data-num]")
            episodes.map { el ->
                val num = el.attr("data-num")
                val epId = el.attr("data-id")
                val dataIds = el.attr("data-ids")
                ContentChapter(
                    id = generateUid("$animeId|$epId"),
                    url = "/watch/$animeSlug-episode-$num?ids=$dataIds&animeId=$animeId",
                    title = "Episode $num",
                    number = num.toFloatOrNull() ?: 0f,
                    uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildFilterTags(): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        tags += ContentTag("Action", "action", source)
        tags += ContentTag("Adventure", "adventure", source)
        tags += ContentTag("Comedy", "comedy", source)
        tags += ContentTag("Drama", "drama", source)
        tags += ContentTag("Fantasy", "fantasy", source)
        tags += ContentTag("Horror", "horror", source)
        tags += ContentTag("Mecha", "mecha", source)
        tags += ContentTag("Music", "music", source)
        tags += ContentTag("Mystery", "mystery", source)
        tags += ContentTag("Romance", "romance", source)
        tags += ContentTag("Sci-Fi", "sci-fi", source)
        tags += ContentTag("Slice of Life", "slice-of-life", source)
        tags += ContentTag("Sports", "sports", source)
        tags += ContentTag("Supernatural", "supernatural", source)
        tags += ContentTag("Thriller", "thriller", source)
        tags += ContentTag("Shounen", "shounen", source)
        tags += ContentTag("Seinen", "seinen", source)
        tags += ContentTag("Shoujo", "shoujo", source)
        tags += ContentTag("Josei", "josei", source)
        tags += ContentTag("Ecchi", "ecchi", source)
        tags += ContentTag("Harem", "harem", source)
        tags += ContentTag("Isekai", "isekai", source)
        tags += ContentTag("Magic", "magic", source)
        tags += ContentTag("Martial Arts", "martial-arts", source)
        tags += ContentTag("Military", "military", source)
        tags += ContentTag("School", "school", source)
        tags += ContentTag("Super Power", "super-power", source)
        tags += ContentTag("Vampire", "vampire", source)
        tags += ContentTag("Game", "game", source)
        tags += ContentTag("Historical", "historical", source)
        tags += ContentTag("Kids", "kids", source)
        tags += ContentTag("Parody", "parody", source)
        tags += ContentTag("Samurai", "samurai", source)
        tags += ContentTag("Psychological", "psychological", source)
        tags += ContentTag("Demons", "demons", source)
        tags += ContentTag("Space", "space", source)
        tags += ContentTag("Cars", "cars", source)
        tags += ContentTag("Dementia", "dementia", source)
        tags += ContentTag("Police", "police", source)
        tags += ContentTag("Mahou Shoujo", "mahou-shoujo", source)
        tags += ContentTag("Shoujo Ai", "shoujo-ai", source)
        tags += ContentTag("Shounen Ai", "shounen-ai", source)
        return tags
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val q = filter.query?.urlEncoded() ?: ""
        if (q.isNotEmpty()) {
            return "https://$domain/search?q=$q&page=$page"
        }
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "popular"
            else -> "latest"
        }
        val tagParam = filter.tags.joinToString(",") { it.key }
        val genreParam = if (tagParam.isNotEmpty()) "&genre=$tagParam" else ""
        return "https://$domain/?sort=$sortParam&page=$page$genreParam"
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        var cards = doc.select("a.film-poster-ahref[href], a.item[href], .card a[href], .video-item a[href], .video-card a[href], article a[href], .post a[href]").toList()
        if (cards.isEmpty()) {
            cards = doc.select("a[href]").filter { a ->
                val h = a.attr("href")
                val hasContent = a.selectFirst("img") != null || a.selectFirst("h3,h2,h4,.title,.name") != null
                val notNav = !h.contains("genre") && !h.contains("category") && !h.contains("tag") &&
                    !h.contains("login") && !h.contains("signup") && !h.contains("random") &&
                    !h.contains("cdn") && !h.contains("static") && !h.contains("assets") &&
                    !h.contains("javascript") && !h.contains("facebook") && !h.contains("twitter") &&
                    h.startsWith("/") && h.count { it == '/' } >= 2 && h.length > 5
                hasContent || notNav
            }
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst("h3, h2, .title, .name")?.text()?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = link.selectFirst("img[src], img[data-src]")?.let {
                (it.attr("data-src").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
            ))
        }
        return items
    }
}
