package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
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
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import java.util.LinkedHashSet
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("HANIME", "Hanime", "en", type = ContentType.HENTAI_VIDEO)
internal class Hanime(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HANIME, pageSize = 48) {

    init {
        setFirstPage(0)
    }

    override val configKeyDomain = ConfigKey.Domain("hanime.tv")

    private val apiBase = "https://guest.freeanimehentai.net/api/v11/search_hvs"
    private val disallowedStreamHosts = setOf("adtng.com", "adnxs.com", "doubleclick.net")
    private var allVideosCache: List<JSONObject>? = null

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = fetchAllVideos().flatMap { video ->
            val values = video.optJSONArray("tags") ?: JSONArray()
            (0 until values.length()).mapNotNull { i ->
                values.optString(i).takeIf { it.isNotBlank() }
                    ?.let { ContentTag(it.replaceFirstChar(Char::uppercase), it, source) }
            }
        }.toSet()
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = tags,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        return fetchListByApi(page, order, filter)
    }

    override suspend fun getDetails(manga: Content): Content {
        val slug = manga.url.substringBefore('?').trimEnd('/').substringAfterLast('/')

        val apiData = fetchVideoDetail(slug)
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

        val doc = webClient.httpGet(watchUrl(manga.url), getRequestHeaders()).parseHtml()
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
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover ?: manga.largeCoverUrl,
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
        val watchUrl = watchUrl(chapter.url)
        val doc = webClient.httpGet(watchUrl, getRequestHeaders()).parseHtml()

        val fromVideoTag = extractFromVideoTag(doc)
        val fromLdJson = extractFromLdJson(doc)
        val fromRegex = if (fromVideoTag.isEmpty() && fromLdJson.isEmpty()) extractByRegex(doc) else emptyList()
        val streams = (fromVideoTag + fromLdJson + fromRegex)
            .mapNotNull { it.toAbsoluteUrlOrNull(domain) }
            .filter { url -> disallowedStreamHosts.none(url::contains) }
            .distinct()
        if (streams.isNotEmpty()) {
            val poster = doc.selectFirst("video[poster]")?.attrOrNull("poster")
                ?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
            return streams.map { s ->
                ContentPage(
                    id = generateUid(s.toRelativeUrl(domain)),
                    url = s,
                    preview = poster,
                    headers = mapOf("Referer" to watchUrl, "User-Agent" to context.getDefaultUserAgent()),
                    source = source,
                )
            }
        }

        // Browser actions only resolve challenges/authentication, not the player handshake.
        // Returning from the browser would retry this same page and open it again.
        throw ParseException("Hanime: playable video stream missing; native playback is currently unavailable", watchUrl)
    }

    private fun extractFromVideoTag(doc: Document): List<String> {
        val res = ArrayList<String>()
        val video = doc.selectFirst("video")
        if (video != null) {
            val sources = doc.select("video source[src]")
            for (src in sources) {
                val u = src.attrOrNull("src")
                if (!u.isNullOrBlank()) {
                    res.add(u)
                }
            }
            video.attrOrNull("src")?.let { res.add(it) }
        }
        return res
    }

    private fun extractFromLdJson(doc: Document): List<String> {
        val res = ArrayList<String>()
        val scripts = doc.select("script[type=application/ld+json]")
        for (s in scripts) {
            val raw = s.data().trim()
            if (raw.isEmpty()) continue
            runCatching {
                val node = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
                when (node) {
                    is JSONObject -> {
                        node.optString("contentUrl").takeIf { it.isNotBlank() }?.let { res.add(it) }
                        node.optJSONObject("mainEntity")?.optString("contentUrl")
                            ?.takeIf { it.isNotBlank() }?.let(res::add)
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            val obj = node.optJSONObject(i) ?: continue
                            obj.optString("contentUrl").takeIf { it.isNotBlank() }?.let { res.add(it) }
                        }
                    }
                }
            }.getOrElse { }
        }
        return res
    }

    private fun extractByRegex(doc: Document): List<String> {
        val res = ArrayList<String>()
        val html = doc.outerHtml()
        val hls = Regex("https?://[^\"'\\s>]+\\.m3u8(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        val mp4 = Regex("https?://[^\"'\\s>]+\\.mp4(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        hls.findAll(html).forEach { m -> res.add(org.jsoup.parser.Parser.unescapeEntities(m.value, false)) }
        mp4.findAll(html).forEach { m -> res.add(org.jsoup.parser.Parser.unescapeEntities(m.value, false)) }
        return res
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Origin", "https://$domain")
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private suspend fun fetchAllVideos(): List<JSONObject> {
        allVideosCache?.let { return it }
        val hits = webClient.httpGet(apiBase, getRequestHeaders()).parseJson().getJSONArray("data")
        val list = ArrayList<JSONObject>(hits.length())
        for (i in 0 until hits.length()) {
            hits.optJSONObject(i)?.let { list.add(it) }
        }
        allVideosCache = list
        return list
    }

    private fun parseVideoItem(o: JSONObject): Content? {
        val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val title = o.optString("name").takeIf { it.isNotBlank() } ?: "Untitled"
        val cover = o.optString("cover_url").takeIf { it.isNotBlank() }
            ?: o.optString("poster_url").takeIf { it.isNotBlank() }
        val tags = o.optJSONArray("tags") ?: JSONArray()
        val tagSet = LinkedHashSet<ContentTag>(tags.length())
        for (j in 0 until tags.length()) {
            val tag = tags.optString(j).takeIf { it.isNotBlank() } ?: continue
            tagSet.add(ContentTag(tag.replaceFirstChar { it.uppercase() }, tag, source))
        }
        return Content(
            id = generateUid(slug),
            url = "/videos/hentai/$slug",
            publicUrl = "https://$domain/videos/hentai/$slug",
            title = title, altTitles = emptySet(),
            coverUrl = cover, largeCoverUrl = cover,
            authors = emptySet(), tags = tagSet, state = null,
            description = o.optString("description").takeIf { it.isNotBlank() },
            contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
        )
    }

    private suspend fun fetchListByApi(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val all = fetchAllVideos()
        if (all.isEmpty()) return emptyList()

        var filtered: List<JSONObject> = all

        val query = filter.query
        if (!query.isNullOrBlank()) {
            val q = query.lowercase()
            filtered = filtered.filter { obj ->
                obj.optString("name").lowercase().contains(q) ||
                obj.optString("search_titles").lowercase().contains(q)
            }
        }

        val tagKeys = filter.tags.map { it.key.lowercase() }.toSet()
        if (tagKeys.isNotEmpty()) {
            filtered = filtered.filter { obj ->
                val objTags = obj.optJSONArray("tags") ?: return@filter false
                val set = LinkedHashSet<String>(objTags.length())
                for (j in 0 until objTags.length()) {
                    set.add(objTags.optString(j).lowercase())
                }
                tagKeys.all { it in set }
            }
        }

        val comparator = when (order) {
            SortOrder.POPULARITY -> compareByDescending<JSONObject> { it.optInt("views", 0) }
            SortOrder.RATING -> compareByDescending { it.optInt("likes", 0) }
            SortOrder.NEWEST -> compareByDescending { it.optLong("released_at_unix", 0) }
            else -> compareByDescending { it.optLong("created_at_unix", 0) }
        }
        filtered = filtered.sortedWith(comparator)

        val start = page * pageSize
        if (start >= filtered.size) return emptyList()
        val end = minOf(start + pageSize, filtered.size)
        return filtered.subList(start, end).mapNotNull { parseVideoItem(it) }
    }

    private data class VideoDetailData(
        val title: String?, val description: String?, val cover: String?,
        val poster: String?, val tags: Set<ContentTag>,
    )

    private fun watchUrl(url: String): String = "https://$domain/videos/hentai/${url.substringBefore('?')
        .trimEnd('/').substringAfterLast('/')}"

    private suspend fun fetchVideoDetail(slug: String): VideoDetailData? {
        val all = fetchAllVideos()
        val o = all.find { it.optString("slug") == slug } ?: return null
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
}
