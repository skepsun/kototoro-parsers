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
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
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

    private val apiBase = "https://cached.freeanimehentai.net/api/v10/search_hvs"
    private val manifestsBase = "https://cached.freeanimehentai.net/api/v8/guest/videos"
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
        val htmlItems = parseList(doc)
        if (htmlItems.isNotEmpty()) return htmlItems

        if (filter.tags.isEmpty() && filter.query.isNullOrBlank()) {
            val trendingDoc = webClient.httpGet(
                "https://$domain/browse/trending", getRequestHeaders(),
            ).parseHtml()
            val trendingItems = parseList(trendingDoc)
            if (trendingItems.isNotEmpty()) return trendingItems
        }

        return emptyList()
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

        val videoId = parseVideoIdFromNux(doc)
        if (videoId != null) {
            val manifestUrl = "$manifestsBase/$videoId/manifest"
            val manifest = runCatching {
                webClient.httpGet(manifestUrl, getRequestHeaders()).parseJson()
            }.getOrNull()
            if (manifest != null) {
                val pages = parsePagesFromManifest(manifest)
                if (pages.isNotEmpty()) return pages
            }
        }

        val fromVideoTag = extractFromVideoTag(doc)
        val fromLdJson = extractFromLdJson(doc)
        val fromRegex = extractByRegex(doc)
        val streams = (fromVideoTag + fromLdJson + fromRegex).distinct()
        if (streams.isNotEmpty()) {
            val poster = doc.selectFirst("video[poster]")?.attrOrNull("poster")
                ?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
            return streams.map { s ->
                ContentPage(
                    id = generateUid(s.toRelativeUrl(domain)),
                    url = s,
                    preview = poster,
                    source = source,
                )
            }
        }

        context.requestBrowserAction(this, watchUrl)
        return emptyList()
    }

    private fun parseVideoIdFromNux(doc: Document): Int? {
        val html = doc.outerHtml()
        val nuxRegex = Regex("""window\.__NUXT__=\(function\(([^)]*)\)\{return (.+?)\}\((.*?)\)\)""")
        val match = nuxRegex.find(html) ?: return null
        val paramNames = match.groupValues[1].split(",").map { it.trim() }
        val body = match.groupValues[2]
        val argsStr = match.groupValues[3]

        val idStart = body.indexOf("hentai_video:{id:")
        if (idStart < 0) return null
        val afterId = body.substring(idStart + "hentai_video:{id:".length)
        val varName = afterId.takeWhile { it.isLetter() }
        if (varName.isEmpty()) return afterId.takeWhile { it.isDigit() }.toIntOrNull()

        val args = parseNuxArgs(argsStr)
        val idx = paramNames.indexOf(varName)
        return if (idx >= 0 && idx < args.size) args[idx].toIntOrNull() else null
    }

    private fun parseNuxArgs(argsStr: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        var inString = false
        var stringChar: Char? = null
        for (ch in argsStr) {
            if (inString) {
                current.append(ch)
                if (ch == stringChar && (current.length < 2 || current[current.length - 2] != '\\')) {
                    inString = false
                }
                continue
            }
            when {
                ch == '"' || ch == '\'' -> {
                    inString = true; stringChar = ch; current.append(ch)
                }
                ch == '[' || ch == '{' || ch == '(' -> {
                    depth++; current.append(ch)
                }
                ch == ']' || ch == '}' || ch == ')' -> {
                    depth--; current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    args.add(current.toString().trim()); current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) args.add(current.toString().trim())
        return args
    }

    private fun parsePagesFromManifest(json: JSONObject): List<ContentPage> {
        val result = mutableListOf<ContentPage>()
        val manifest = json.optJSONObject("videos_manifest") ?: return result
        val servers = manifest.optJSONArray("servers") ?: return result
        for (si in 0 until servers.length()) {
            val server = servers.optJSONObject(si) ?: continue
            val streams = server.optJSONArray("streams") ?: continue
            for (ti in 0 until streams.length()) {
                val stream = streams.optJSONObject(ti) ?: continue
                val url = stream.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (disallowedStreamHosts.none { url.contains(it) }) {
                    val quality = stream.optString("height").takeIf { it.isNotBlank() }
                    val label = if (quality != null) "${quality}p" else null
                    result.add(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
                }
            }
        }
        return result
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
        val hls = Regex("https?://[^\"'\\s>]+\\.m3u8", RegexOption.IGNORE_CASE)
        val mp4 = Regex("https?://[^\"'\\s>]+\\.mp4", RegexOption.IGNORE_CASE)
        hls.findAll(html).forEach { m -> res.add(m.value) }
        mp4.findAll(html).forEach { m -> res.add(m.value) }
        return res
    }

    private fun parseListFromNux(doc: Document): List<Content> {
        val html = doc.outerHtml()
        val nuxRegex = Regex("""window\.__NUXT__=\(function\([^)]*\)\{return (.+?)\}\([^)]*\)\)""")
        val match = nuxRegex.find(html) ?: return emptyList()
        val body = match.groupValues[1]

        val vidStart = body.indexOf("hentai_videos:[")
        if (vidStart < 0) return emptyList()

        val arrayStart = body.indexOf('[', vidStart)
        if (arrayStart < 0) return emptyList()
        val arrayEnd = findMatchingBracket(body, arrayStart) ?: return emptyList()
        val arrayStr = body.substring(arrayStart, arrayEnd + 1)
            .replace("\\u002F", "/")

        val items = ArrayList<Content>()
        var pos = 1
        while (pos < arrayStr.length) {
            val objStart = arrayStr.indexOf("{id:", pos)
            if (objStart < 0) break
            val objEnd = findMatchingBrace(arrayStr, objStart) ?: break
            val objStr = arrayStr.substring(objStart, objEnd + 1)

            val slug = Regex("""slug:"([^"]*)"""").find(objStr)?.groupValues?.get(1) ?: run {
                pos = objEnd + 1; continue
            }
            val name = Regex("""name:"([^"]*)"""").find(objStr)?.groupValues?.get(1) ?: "Untitled"
            val cover = Regex("""(?:cover_url|poster_url):"([^"]*)"""").find(objStr)?.groupValues?.get(1)

            items.add(Content(
                id = generateUid(slug),
                url = "/hentai-videos/$slug",
                publicUrl = "https://$domain/hentai-videos/$slug",
                title = name, altTitles = emptySet(),
                coverUrl = cover, largeCoverUrl = cover,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
            ))
            pos = objEnd + 1
        }
        return items
    }

    private fun findMatchingBracket(s: String, start: Int): Int? {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun findMatchingBrace(s: String, start: Int): Int? {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Origin", "https://$domain")
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private suspend fun fetchAllVideos(): List<JSONObject> {
        allVideosCache?.let { return it }
        val hits = runCatching {
            webClient.httpGet(apiBase, getRequestHeaders()).parseJsonArray()
        }.getOrElse { JSONArray() }
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
            url = "/hentai-videos/$slug",
            publicUrl = "https://$domain/hentai-videos/$slug",
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
            SortOrder.UPDATED -> compareByDescending { it.optLong("released_at_unix", 0) }
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

    private suspend fun fetchTagsFromBrowse(): Set<ContentTag> {
        val doc = webClient.httpGet("https://$domain/browse", getRequestHeaders()).parseHtml()
        val nuxTags = parseTagsFromNux(doc)
        if (nuxTags.isNotEmpty()) return nuxTags
        return doc.select("a[href*=/browse/tags/]").mapNotNull { a ->
            val text = a.text().trim().replaceFirstChar { it.uppercase() }
            val key = a.attr("href").substringAfterLast('/').trim()
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()
    }

    private fun parseTagsFromNux(doc: Document): Set<ContentTag> {
        val html = doc.outerHtml()
        val nuxRegex = Regex("""window\.__NUXT__=\(function\([^)]*\)\{return (.+?)\}\([^)]*\)\)""")
        val match = nuxRegex.find(html) ?: return emptySet()
        val body = match.groupValues[1]

        val tagsStart = body.indexOf("hentai_tags:[")
        if (tagsStart < 0) return emptySet()

        val arrayStart = body.indexOf('[', tagsStart)
        if (arrayStart < 0) return emptySet()
        val arrayEnd = findMatchingBracket(body, arrayStart) ?: return emptySet()
        val arrayStr = body.substring(arrayStart, arrayEnd + 1)

        val result = LinkedHashSet<ContentTag>()
        val tagRegex = Regex("""\{id:\d+,text:"([^"]*)"""")
        tagRegex.findAll(arrayStr).forEach { m ->
            val text = m.groupValues[1]
            result.add(ContentTag(text.replaceFirstChar { it.uppercase() }, text, source))
        }
        return result
    }

    private fun defaultTags(): Set<ContentTag> = linkedSetOf(
        ContentTag("3D", "3d", source), ContentTag("Ahegao", "ahegao", source),
        ContentTag("Anal", "anal", source), ContentTag("BDSM", "bdsm", source),
        ContentTag("Big Boobs", "big boobs", source), ContentTag("Blow Job", "blow job", source),
        ContentTag("Bondage", "bondage", source), ContentTag("Censored", "censored", source),
        ContentTag("Cosplay", "cosplay", source), ContentTag("Creampie", "creampie", source),
        ContentTag("Futanari", "futanari", source), ContentTag("Gangbang", "gangbang", source),
        ContentTag("Harem", "harem", source), ContentTag("Incest", "incest", source),
        ContentTag("Loli", "loli", source), ContentTag("MILF", "milf", source),
        ContentTag("NTR", "ntr", source), ContentTag("School Girl", "school girl", source),
        ContentTag("Tentacle", "tentacle", source), ContentTag("Threesome", "threesome", source),
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
        if (items.isNotEmpty()) return items
        return parseListFromNux(doc)
    }
}
