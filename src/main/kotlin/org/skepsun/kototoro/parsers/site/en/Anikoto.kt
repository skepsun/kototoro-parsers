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

@ContentSourceParser("ANIKOTO", "Anikoto", "en", type = ContentType.VIDEO)
internal class Anikoto(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANIKOTO, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("anikototv.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.NEWEST,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val doc = webClient.httpGet("https://$domain/home", getRequestHeaders()).parseHtml()
        val genres = doc.select("#menu ul.c4 a[href*=/genre/]").mapNotNull { a ->
            val title = a.attr("title").ifBlank { a.text().trim() }
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (title.isNotBlank() && key.isNotBlank()) ContentTag(title, key, source) else null
        }
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
            availableTags = genres.toSet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removeSuffix(" - Anikoto")?.trim()
            ?: doc.selectFirst(".binfo h1.title")?.text()?.trim() ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".binfo .synopsis .content")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".binfo .poster img")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val mangaId = doc.selectFirst("script:containsData(mangaId)")?.data()
            ?.let { Regex("mangaId\\s*=\\s*(\\d+)").find(it)?.groupValues?.get(1) }

        val authors = doc.select(".binfo .info .names")?.text()
            ?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        val tags = doc.select(".binfo .bmeta a[href*=/genre/]").mapNotNull { a ->
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
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            contentRating = ContentRating.SAFE,
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

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val tagParam = filter.tags.joinToString(",") { it.key }
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            val sortParam = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.ALPHABETICAL -> "az"
                SortOrder.NEWEST -> "newest"
                else -> "default"
            }
            "https://$domain/filter?keyword=$q&type=&status=&season=&language=&genre=$tagParam&sort=$sortParam&page=$page"
        } else {
            "https://$domain/home"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        val cards = doc.select("a.item[href*=/watch/]")
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst(".name.d-title")?.text()?.trim()
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim() ?: continue
            val thumb = link.selectFirst(".poster img[src]")?.attr("src")?.toAbsoluteUrlOrNull(domain)
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.SAFE, source = source, rating = RATING_UNKNOWN,
            ))
        }
        return items
    }
}
