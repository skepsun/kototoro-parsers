package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
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
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("HANIME", "Hanime", "en", type = ContentType.HENTAI_VIDEO)
internal class Hanime(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HANIME, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("hanime.tv")

    private val apiBase = "https://search.htv-services.com"
    private val disallowedStreamHosts = setOf("adtng.com", "adnxs.com", "doubleclick.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = runCatching { fetchTagsFromBrowse() }.getOrDefault(defaultTags())
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = tags,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val apiItems = runCatching { fetchListByApi(page, order, filter) }.getOrNull()
        if (!apiItems.isNullOrEmpty()) return apiItems

        val url = buildBrowseUrl(page, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val slug = manga.publicUrl.substringAfterLast('/').ifBlank { manga.url.substringAfterLast('/') }

        val apiData = runCatching { fetchVideoDetail(slug) }.getOrNull()
        if (apiData != null) {
            return manga.copy(
                title = apiData.title ?: manga.title,
                description = apiData.description ?: manga.description,
                coverUrl = apiData.cover ?: manga.coverUrl,
                largeCoverUrl = apiData.poster ?: apiData.cover ?: manga.largeCoverUrl,
                tags = if (apiData.tags.isNotEmpty()) apiData.tags else manga.tags,
                contentRating = ContentRating.ADULT,
                chapters = listOf(
                    ContentChapter(
                        id = generateUid("${manga.url}|video"),
                        url = manga.url,
                        title = "Watch",
                        number = 1f, uploadDate = 0L, volume = 0,
                        branch = null, scanlator = null, source = source,
                    ),
                ),
            )
        }

        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: manga.title
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val tagEls = doc.select("a[href*=/browse/tags/]")
        val tags = tagEls.mapNotNull { a ->
            val text = a.text().trim().replaceFirstChar { it.uppercase() }
            val key = a.attr("href").substringAfterLast('/').trim()
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        return manga.copy(
            title = title, description = description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover ?: cover,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            contentRating = ContentRating.ADULT,
            chapters = listOf(
                ContentChapter(
                    id = generateUid("${manga.url}|video"),
                    url = manga.url, title = "Watch",
                    number = 1f, uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                ),
            ),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val watchUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(watchUrl, getRequestHeaders()).parseHtml()

        val pages = mutableListOf<ContentPage>()

        doc.select("video source[src]").forEach { src ->
            val url = src.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            if (url.startsWith("http") && disallowedStreamHosts.none { url.contains(it) }) {
                pages.add(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
            }
        }

        doc.selectFirst("video[src]")?.attr("src")?.takeIf { it.startsWith("http") }?.let { url ->
            if (disallowedStreamHosts.none { url.contains(it) }) {
                pages.add(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
            }
        }

        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching {
                val json = JSONObject(script.data())
                json.optString("contentUrl").takeIf { it.isNotBlank() }?.let { url ->
                    if (url.startsWith("http")) {
                        pages.add(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
                    }
                }
            }
        }

        if (pages.isNotEmpty()) return pages

        val respBody = webClient.httpGet(watchUrl, getRequestHeaders())
        val html = respBody.toString()
        Regex("https?://[^\"'\\s>]+\\.m3u8", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val url = m.value
            if (disallowedStreamHosts.none { url.contains(it) }) {
                pages.add(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
            }
        }
        if (pages.isEmpty()) {
            Regex("https?://[^\"'\\s>]+\\.mp4", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val url = m.value
                if (disallowedStreamHosts.none { url.contains(it) }) {
                    pages.add(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
                }
            }
        }

        if (pages.isNotEmpty()) return pages

        context.requestBrowserAction(this, watchUrl)
        return emptyList()
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private suspend fun fetchListByApi(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val body = JSONObject().apply {
            put("search_text", filter.query ?: "")
            put("tags", JSONArray(filter.tags.map { it.key }))
            put("tags_mode", "AND")
            put("brands", JSONArray())
            put("blacklist", JSONArray())
            put("order_by", when (order) {
                SortOrder.POPULARITY -> "views"
                SortOrder.RATING -> "rating"
                SortOrder.UPDATED -> "released_at_unix"
                else -> "created_at_unix"
            })
            put("ordering", "desc")
            put("page", page)
        }

        val json = webClient.httpPost(apiBase, body).parseJson()
        val hits = runCatching { JSONArray(json.getString("hits")) }.getOrElse { JSONArray() }
        val list = ArrayList<Content>(hits.length())
        for (i in 0 until hits.length()) {
            val o = hits.optJSONObject(i) ?: continue
            val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val title = o.optString("name").takeIf { it.isNotBlank() } ?: "Untitled"
            val cover = o.optString("cover_url").takeIf { it.isNotBlank() }
                ?: o.optString("poster_url").takeIf { it.isNotBlank() }
            val tags = o.optJSONArray("tags") ?: JSONArray()
            val tagSet = LinkedHashSet<ContentTag>(tags.length())
            for (j in 0 until tags.length()) {
                val tag = tags.optString(j).takeIf { it.isNotBlank() } ?: continue
                tagSet.add(ContentTag(tag.replaceFirstChar { it.uppercase() }, tag, source))
            }
            list.add(Content(
                id = generateUid(slug),
                url = "/hentai-videos/$slug",
                publicUrl = "https://$domain/hentai-videos/$slug",
                title = title, altTitles = emptySet(),
                coverUrl = cover, largeCoverUrl = cover,
                authors = emptySet(), tags = tagSet, state = null,
                description = o.optString("description").takeIf { it.isNotBlank() },
                contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
            ))
        }
        return list
    }

    private data class VideoDetailData(
        val title: String?, val description: String?, val cover: String?,
        val poster: String?, val tags: Set<ContentTag>,
    )

    private suspend fun fetchVideoDetail(slug: String): VideoDetailData? {
        val body = JSONObject().apply {
            put("search_text", slug)
            put("tags", JSONArray())
            put("tags_mode", "AND")
            put("brands", JSONArray())
            put("blacklist", JSONArray())
            put("order_by", "created_at_unix")
            put("ordering", "desc")
            put("page", 0)
        }
        val json = webClient.httpPost(apiBase, body).parseJson()
        val hits = runCatching { JSONArray(json.getString("hits")) }.getOrElse { JSONArray() }
        if (hits.length() == 0) return null
        val o = hits.optJSONObject(0) ?: return null
        val title = o.optString("name").takeIf { it.isNotBlank() }
        val desc = o.optString("description").takeIf { it.isNotBlank() }
        val cover = o.optString("cover_url").takeIf { it.isNotBlank() }
        val poster = o.optString("poster_url").takeIf { it.isNotBlank() }
        val tags = o.optJSONArray("tags") ?: JSONArray()
        val tagSet = LinkedHashSet<ContentTag>(tags.length())
        for (j in 0 until tags.length()) {
            val tag = tags.optString(j).takeIf { it.isNotBlank() } ?: continue
            tagSet.add(ContentTag(tag.replaceFirstChar { it.uppercase() }, tag, source))
        }
        return VideoDetailData(title, desc, cover, poster, tagSet)
    }

    private suspend fun fetchTagsFromBrowse(): Set<ContentTag> {
        val doc = webClient.httpGet("https://$domain/browse/tags", getRequestHeaders()).parseHtml()
        return doc.select("a[href*=/browse/tags/]").mapNotNull { a ->
            val text = a.text().trim().replaceFirstChar { it.uppercase() }
            val key = a.attr("href").substringAfterLast('/').trim()
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()
    }

    private fun defaultTags(): Set<ContentTag> = linkedSetOf(
        ContentTag("3D", "3d", source), ContentTag("Ahegao", "ahegao", source),
        ContentTag("Anal", "anal", source), ContentTag("BDSM", "bdsm", source),
        ContentTag("Big Boobs", "big-boobs", source), ContentTag("Blowjob", "blow-job", source),
        ContentTag("Bondage", "bondage", source), ContentTag("Censored", "censored", source),
        ContentTag("Cosplay", "cosplay", source), ContentTag("Creampie", "creampie", source),
        ContentTag("Futanari", "futanari", source), ContentTag("Gangbang", "gangbang", source),
        ContentTag("Harem", "harem", source), ContentTag("Incest", "incest", source),
        ContentTag("Loli", "loli", source), ContentTag("MILF", "milf", source),
        ContentTag("NTR", "ntr", source), ContentTag("Schoolgirl", "schoolgirl", source),
        ContentTag("Tentacles", "tentacles", source), ContentTag("Threesome", "threesome", source),
        ContentTag("Uncensored", "uncensored", source), ContentTag("Virgin", "virgin", source),
        ContentTag("Yuri", "yuri", source),
    )

    private fun buildBrowseUrl(page: Int, filter: ContentListFilter): String {
        val tag = filter.tags.firstOrNull()?.key
        return if (tag != null) {
            "https://$domain/browse/tags/$tag?page=$page"
        } else if (!filter.query.isNullOrBlank()) {
            "https://$domain/search?search_text=${filter.query.urlEncoded()}&page=$page"
        } else {
            "https://$domain/browse?page=$page"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        val cards = doc.select("a[href*=/hentai-videos/]")
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            if (href == "/hentai-videos/") continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.attr("title").takeIf { it.isNotBlank() }
                ?: link.text().trim().ifEmpty { "Untitled" }
            val thumb = link.selectFirst("img[src]")?.let {
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
