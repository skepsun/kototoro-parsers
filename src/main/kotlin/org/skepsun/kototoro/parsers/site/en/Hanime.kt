package org.skepsun.kototoro.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import java.util.EnumSet
import java.net.URLEncoder

/**
 * Hanime video source parser (skeleton). This minimal implementation focuses on structure.
 * It is annotated and registered via KSP and can be iterated on later.
 */
@Broken("Under development")
@ContentSourceParser(name = "HANIME", title = "Hanime", locale = "en", type = ContentType.HENTAI_VIDEO)
internal class Hanime(
    context: ContentLoaderContext,
) : PagedContentParser(
    context = context,
    source = ContentParserSource.HANIME,
    pageSize = 24,
) {
    private val DISALLOWED_STREAM_HOSTS: Set<String> = setOf(
        "adtng.com",
        "adnxs.com",
        "doubleclick.net",
    )

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "hanime.tv",
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        // Include UA in config for possible video CDN requirements later
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val dynamic = fetchAvailableTags()
        return ContentListFilterOptions(
            availableTags = if (dynamic.isNotEmpty()) dynamic else defaultHanimeTags(),
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: ContentListFilter,
    ): List<Content> {
        // 优先使用 API 获取可分页的结果；失败时回退到 HTML
        val apiItems = runCatching { fetchListByApi(page, filter) }.getOrElse { emptyList() }
        if (apiItems.isNotEmpty()) return apiItems

        val url = buildSearchUrl(page, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        // Strategy 1: anchors linking to video pages (hanime.tv uses /videos/hentai/...)
        val anchors = doc.select("a[href*=/videos], a[href*=/video], a[href*=/hentai]")
        if (anchors.isNotEmpty()) {
            for (a in anchors) {
                val href = runCatching { a.attrAsRelativeUrl("href") }.getOrNull() ?: continue
                if (!seen.add(href)) continue
                // Try to find a reasonable container for title/cover
                val container0 = a.parents().firstOrNull { p ->
                    p.hasClass("card") || p.hasClass("video-item") || p.hasClass("video-card") ||
                        p.hasClass("thumb") || p.hasClass("hentai-item") || p.hasClass("multiple-link-wrapper") ||
                        p.hasClass("card-mobile-panel")
                } ?: a.parent()
                val container = if (a.hasClass("search-result__item")) a else container0

                val title = a.attrOrNull("title")?.takeIf { it.isNotBlank() }
                    ?: a.attrOrNull("aria-label")?.takeIf { it.isNotBlank() }
                    ?: container?.selectFirst(".search-result__item__title, .card-mobile-title, .title, .card-title, h3, h2, .name")?.text()?.takeIf { it.isNotBlank() }
                    ?: a.text().takeIf { it.isNotBlank() }
                    ?: "Untitled"
                val img = container?.selectFirst("img[src], img[data-src], img[data-srcset]") ?: a.selectFirst("img")
                val coverAbs = resolveCover(img, container)

                items.add(
                    Content(
                        id = generateUid(href),
                        url = href,
                        publicUrl = href.toAbsoluteUrl(domain),
                        title = title,
                        altTitles = emptySet(),
                        coverUrl = coverAbs,
                        largeCoverUrl = null,
                        authors = emptySet(),
                        tags = emptySet(),
                        state = null,
                        description = null,
                        contentRating = ContentRating.ADULT,
                        source = source,
                        rating = RATING_UNKNOWN,
                    ),
                )
                if (items.size >= pageSize) break
            }
        }

        // Strategy 2: fallback to generic grid cards (common on hanime.tv)
        if (items.isEmpty()) {
            val cards = doc.select(".search-result__item, .card, .video-item, .video-card, .thumb, .hentai-item")
            for (c in cards) {
                val link = c.selectFirst("a[href]") ?: continue
                val href = runCatching { link.attrAsRelativeUrl("href") }.getOrNull() ?: continue
                if (!seen.add(href)) continue
                val titleEl = c.selectFirst(".search-result__item__title, .title, .card-title, h3, h2, .name")
                val title = titleEl?.text()?.takeIf { it.isNotBlank() } ?: link.attrOrNull("title")
                    ?: link.attrOrNull("aria-label")?.takeIf { it.isNotBlank() }
                    ?: link.text().takeIf { it.isNotBlank() } ?: "Untitled"
                val img = c.selectFirst("img.search-result__item__cover__img, img[src], img[data-src], img[data-srcset]")
                val coverAbs = resolveCover(img, c)

                items.add(
                    Content(
                        id = generateUid(href),
                        url = href,
                        publicUrl = href.toAbsoluteUrl(domain),
                        title = title,
                        altTitles = emptySet(),
                        coverUrl = coverAbs,
                        largeCoverUrl = null,
                        authors = emptySet(),
                        tags = emptySet(),
                        state = null,
                        description = null,
                        contentRating = ContentRating.ADULT,
                        source = source,
                        rating = RATING_UNKNOWN,
                    ),
                )
                if (items.size >= pageSize) break
            }
        }

        return items
    }

    private suspend fun fetchListByApi(page: Int, filter: ContentListFilter): List<Content> {
        val endpoint = "https://members.$domain/api/v5/hentai-search"
        val body = JSONObject()
        val search = JSONObject()
        val tokens = buildSearchTokens(filter)
        search.put("query", tokens.joinToString(" "))
        search.put("page", page)
        // 排序固定为最新，后续可扩展
        search.put("sort_by", "released_at_unix")
        search.put("order_by", "desc")
        body.put("search", search)

        val headers = getRequestHeaders().newBuilder()
            .add("Accept", "application/json")
            .add("X-Directive", "api")
            .build()

        val json = webClient.httpPost(endpoint.toHttpUrl(), body, headers).parseJson()
        // 兼容多种结构：v5/v2（results/hits/videos）与用户提供的顶层 items
        val arr = json.optJSONArray("results")
            ?: json.optJSONObject("hentai_search_v2")?.optJSONArray("hits")
            ?: json.optJSONArray("videos")
            ?: json.optJSONArray("items")
            ?: JSONArray()

        val site = domain
        val list = ArrayList<Content>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("slug").takeIf { it.isNotBlank() }
                ?: o.optString("video_slug").takeIf { it.isNotBlank() }
                ?: o.optString("watch_url").substringAfterLast('/').takeIf { it.isNotBlank() } ?: continue
            val title = o.optString("name").takeIf { it.isNotBlank() }
                ?: o.optString("title").takeIf { it.isNotBlank() } ?: "Untitled"
            val cover = o.optString("cover_url").takeIf { it.isNotBlank() }
                ?: o.optString("poster_url").takeIf { it.isNotBlank() }
                ?: null

            list.add(
                Content(
                    id = generateUid(slug),
                    url = "/hentai-videos/$slug",
                    publicUrl = "https://$site/hentai-videos/$slug",
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = cover,
                    largeCoverUrl = null,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = null,
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
        }
        return list
    }

    private fun buildSearchTokens(filter: ContentListFilter): List<String> {
        val tokens = mutableListOf<String>()
        if (!filter.query.isNullOrBlank()) tokens += filter.query!!
        if (filter.tags.isNotEmpty()) {
            filter.tags.forEach { t ->
                val key = t.key.substringAfter(':', t.title)
                if (key.isNotBlank()) tokens += key
            }
        }
        if (filter.tagsExclude.isNotEmpty()) {
            filter.tagsExclude.forEach { t ->
                val key = t.key.substringAfter(':', t.title)
                if (key.isNotBlank()) tokens += "-$key"
            }
        }
        return tokens
    }

    private fun buildSearchUrl(page: Int, filter: ContentListFilter): String {
        // 当有查询/标签时走 search；否则回退到通用视频列表
        val tokens = mutableListOf<String>()
        if (!filter.query.isNullOrBlank()) tokens += filter.query!!
        if (filter.tags.isNotEmpty()) {
            filter.tags.forEach { t ->
                val key = t.key.substringAfter(':', t.title)
                if (key.isNotBlank()) tokens += key
            }
        }
        if (filter.tagsExclude.isNotEmpty()) {
            filter.tagsExclude.forEach { t ->
                val key = t.key.substringAfter(':', t.title)
                if (key.isNotBlank()) tokens += "-${key}"
            }
        }

        val hasQuery = tokens.isNotEmpty()
        val base = StringBuilder().append("https://").append(domain)
            .append(if (hasQuery) "/search" else "/videos")

        val params = ArrayList<String>()
        if (hasQuery) {
            val q = encode(tokens.joinToString(" "))
            // 同时提交 q 与 query，两者其一命中即可
            params.add("q=" + q)
            params.add("query=" + q)
        }
        if (page > 1) params.add("page=$page")
        if (params.isNotEmpty()) base.append('?').append(params.joinToString("&"))
        return base.toString()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun resolveCover(imgEl: org.jsoup.nodes.Element?, container: org.jsoup.nodes.Element?): String? {
        // Prefer explicit src
        imgEl?.attrAsAbsoluteUrlOrNull("src")?.let { return it }
        // data-src
        imgEl?.attrAsAbsoluteUrlOrNull("data-src")?.let { return it }
        // srcset on img
        imgEl?.attrOrNull("srcset")?.let { parseSrcset(it, domain) }?.let { return it }
        // picture > source[srcset]
        val srcset = container?.selectFirst("picture source[srcset]")?.attrOrNull("srcset")
        if (!srcset.isNullOrBlank()) {
            parseSrcset(srcset, domain)?.let { return it }
        }
        return null
    }

    private fun parseSrcset(srcset: String, domain: String): String? {
        // Choose the last candidate in srcset (usually highest resolution)
        val parts = srcset.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val last = parts.lastOrNull() ?: return null
        val url = last.split(' ').firstOrNull()?.trim() ?: return null
        return url.toAbsoluteUrl(domain)
    }

    private suspend fun fetchAvailableTags(): Set<ContentTag> {
        return try {
            val doc = webClient.httpGet("https://$domain/search", getRequestHeaders()).parseHtml()
            val chips = doc.select(
                "div.search-dialog:has(.search-dialog__toolbar .toolbar__title:matchesOwn(Filters)) " +
                    ".layout.row.wrap span.chip .chip__content",
            )
            val result = LinkedHashSet<ContentTag>(chips.size)
            for (chip in chips) {
                val text = chip.text().trim()
                if (text.isEmpty()) continue
                result += ContentTag(
                    title = text,
                    key = tagKeyFromText(text),
                    source = source,
                )
            }
            result
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun defaultHanimeTags(): Set<ContentTag> = linkedSetOf(
        // 常见标签作为回退；key 统一为 tag:slug
        ContentTag("3D", "tag:3d", source),
        ContentTag("Ahegao", "tag:ahegao", source),
        ContentTag("Anal", "tag:anal", source),
        ContentTag("Big Boobs", "tag:big-boobs", source),
        ContentTag("Blowjob", "tag:blowjob", source),
        ContentTag("Creampie", "tag:creampie", source),
        ContentTag("Double Penetration", "tag:double-penetration", source),
        ContentTag("Footjob", "tag:footjob", source),
        ContentTag("Gangbang", "tag:gangbang", source),
        ContentTag("Handjob", "tag:handjob", source),
        ContentTag("Loli", "tag:loli", source),
        ContentTag("MILF", "tag:milf", source),
        ContentTag("Schoolgirl", "tag:schoolgirl", source),
        ContentTag("Tentacles", "tag:tentacles", source),
        ContentTag("Threesome", "tag:threesome", source),
        ContentTag("Virgin", "tag:virgin", source),
        ContentTag("Yuri", "tag:yuri", source),
        ContentTag("Yaoi", "tag:yaoi", source),
        ContentTag("Oppai", "tag:oppai", source),
        ContentTag("Futanari", "tag:futanari", source),
        ContentTag("NTR", "tag:ntr", source),
        ContentTag("Harem", "tag:harem", source),
        ContentTag("Bdsm", "tag:bdsm", source),
    )

    private fun tagKeyFromText(text: String): String {
        val slug = text.lowercase()
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return "tag:$slug"
    }

    override suspend fun getDetails(manga: Content): Content {
        // 尝试通过 members API 获取更完整的详情与封面
        val slug = manga.publicUrl.substringAfterLast('/')
        val apiDetails = runCatching { fetchDetailsByApi(slug) }.getOrNull()
        if (apiDetails != null) return manga.copy(
            title = apiDetails.title ?: manga.title,
            description = apiDetails.description,
            largeCoverUrl = apiDetails.poster ?: manga.largeCoverUrl,
            tags = if (manga.tags.isEmpty()) apiDetails.tags else manga.tags,
            chapters = listOf(
                ContentChapter(
                    id = generateUid("${manga.url}|video"),
                    url = manga.url,
                    title = "Watch",
                    number = 1f,
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                ),
            ),
        )

        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        // Try to pull description, title, and tags from meta and LD+JSON
        val metaDesc = doc.selectFirst("meta[name=description]")?.attrOrNull("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attrOrNull("content")
        val metaTitle = doc.selectFirst("meta[property=og:title]")?.attrOrNull("content")

        val metaImage = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
        val keywordsRaw = doc.selectFirst("meta[name=keywords]")?.attrOrNull("content")

        val tags = if (!keywordsRaw.isNullOrBlank()) {
            keywordsRaw.split(',')
                .mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
                .map { kw ->
                    ContentTag(title = kw.replaceFirstChar { ch -> ch.uppercase() }, key = kw.lowercase(), source = source)
                }
                .toSet()
        } else emptySet()

        // Create a single chapter pointing to the watch page
        val chapter = ContentChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = metaTitle ?: manga.title,
            description = metaDesc,
            largeCoverUrl = metaImage ?: manga.largeCoverUrl,
            tags = if (manga.tags.isEmpty()) tags else manga.tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val watchUrl = chapter.url.toAbsoluteUrl(domain)
        val slug = watchUrl.substringAfterLast('/')
        // 优先使用 members API 获取播放地址
        val apiStreams = runCatching { fetchStreamsByApi(slug) }.getOrElse { emptyList() }
        if (apiStreams.isNotEmpty()) {
            return apiStreams
        }

        val doc = webClient.httpGet(watchUrl, getRequestHeaders()).parseHtml()

        // Try multiple strategies to extract streams
        val fromVideoTag = extractFromVideoTag(doc)
        val fromLdJson = extractFromLdJson(doc)
        val fromRegex = extractByRegex(doc)

        val streams = (fromVideoTag + fromLdJson + fromRegex)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?: return emptyList()

        // Poster for preview, if available
        val poster = doc.selectFirst("video[poster]")?.attrOrNull("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")

        // 过滤广告域名
        val filtered = streams.filterNot { s ->
            val host = runCatching { s.toHttpUrl().host }.getOrNull()
            host != null && DISALLOWED_STREAM_HOSTS.any { block -> host.endsWith(block) }
        }

        return filtered.map { s ->
            ContentPage(
                id = generateUid(s.toRelativeUrl(domain)),
                url = s,
                preview = poster,
                source = source,
            )
        }
    }

    private data class ApiDetails(
        val title: String?,
        val description: String?,
        val poster: String?,
        val tags: Set<ContentTag>,
    )

    private suspend fun fetchDetailsByApi(slug: String): ApiDetails? {
        val url = "https://members.$domain/api/v5/hentai-videos/$slug"
        val headers = getRequestHeaders().newBuilder()
            .add("Accept", "application/json")
            .add("X-Directive", "api")
            .add("Referer", "https://members.$domain/hentai-videos/$slug")
            .build()
        val json = webClient.httpGet(url, headers).parseJson()
        val hv = json.optJSONObject("hentai_video") ?: return null
        val title = hv.optString("name").takeIf { it.isNotBlank() }
        val desc = hv.optString("description").takeIf { it.isNotBlank() }
        val poster = hv.optString("poster_url").takeIf { it.isNotBlank() }
            ?: hv.optString("cover_url").takeIf { it.isNotBlank() }
        val tagArr = hv.optJSONArray("tags") ?: JSONArray()
        val tags = LinkedHashSet<ContentTag>(tagArr.length())
        for (i in 0 until tagArr.length()) {
            val to = tagArr.optJSONObject(i) ?: continue
            val name = to.optString("text").takeIf { it.isNotBlank() }
                ?: to.optString("name").takeIf { it.isNotBlank() } ?: continue
            val key = to.optString("slug").takeIf { it.isNotBlank() } ?: name.lowercase()
            tags.add(ContentTag(title = name, key = key, source = source))
        }
        return ApiDetails(title = title, description = desc, poster = poster, tags = tags)
    }

    private suspend fun fetchStreamsByApi(slug: String): List<ContentPage> {
        val url = "https://members.$domain/api/v5/hentai-videos/$slug"
        val headers = getRequestHeaders().newBuilder()
            .add("Accept", "application/json")
            .add("X-Directive", "api")
            .add("Referer", "https://members.$domain/hentai-videos/$slug")
            .build()
        val json = webClient.httpGet(url, headers).parseJson()
        val hv = json.optJSONObject("hentai_video") ?: JSONObject()
        val poster = hv.optString("poster_url").takeIf { it.isNotBlank() }
            ?: hv.optString("cover_url").takeIf { it.isNotBlank() }
        val raw = json.toString()
        // 从完整 JSON 中兜底提取 m3u8/mp4
        val res = ArrayList<ContentPage>()
        Regex("https?://[^\"'\\s>]+\\.m3u8", RegexOption.IGNORE_CASE).findAll(raw).forEach { m ->
            val s = m.value
            res.add(
                ContentPage(
                    id = generateUid(s.toRelativeUrl(domain)),
                    url = s,
                    preview = poster,
                    source = source,
                ),
            )
        }
        Regex("https?://[^\"'\\s>]+\\.mp4", RegexOption.IGNORE_CASE).findAll(raw).forEach { m ->
            val s = m.value
            res.add(
                ContentPage(
                    id = generateUid(s.toRelativeUrl(domain)),
                    url = s,
                    preview = poster,
                    source = source,
                ),
            )
        }
        // 过滤广告域名
        return res.filterNot { page ->
            val host = runCatching { page.url.toHttpUrl().host }.getOrNull()
            host != null && DISALLOWED_STREAM_HOSTS.any { block -> host.endsWith(block) }
        }
    }

    private fun extractFromVideoTag(doc: Document): List<String> {
        val res = ArrayList<String>()
        val video = doc.selectFirst("video")
        if (video != null) {
            // Sources inside video tag
            val sources = doc.select("video source[src]")
            for (src in sources) {
                val u = src.attrOrNull("src")
                if (!u.isNullOrBlank()) {
                    res.add(u)
                }
            }
            // Direct src on <video>
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
                val node = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
                when (node) {
                    is JSONObject -> {
                        node.optString("contentUrl").takeIf { it.isNotBlank() }?.let { res.add(it) }
                        // Some sites embed nested graph
                        node.optJSONObject("mainEntity")?.optString("contentUrl")?.takeIf { it.isNotBlank() }?.let(res::add)
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            val obj = node.optJSONObject(i) ?: continue
                            obj.optString("contentUrl").takeIf { it.isNotBlank() }?.let { res.add(it) }
                        }
                    }
                }
            }.getOrElse { /* ignore malformed json */ }
        }
        return res
    }

    private fun extractByRegex(doc: Document): List<String> {
        val res = ArrayList<String>()
        val html = doc.outerHtml()
        // Common patterns for HLS and MP4
        val hls = Regex("https?://[^\"'\\s>]+\\.m3u8", RegexOption.IGNORE_CASE)
        val mp4 = Regex("https?://[^\"'\\s>]+\\.mp4", RegexOption.IGNORE_CASE)
        hls.findAll(html).forEach { m -> res.add(m.value) }
        mp4.findAll(html).forEach { m -> res.add(m.value) }
        return res
    }
}
