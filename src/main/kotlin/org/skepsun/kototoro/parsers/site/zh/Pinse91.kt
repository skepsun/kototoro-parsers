package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.parser.Parser
import java.util.EnumSet

@ContentSourceParser(name = "PINSE91", title = "91Pinse", locale = "zh", type = ContentType.HENTAI_VIDEO)
internal class Pinse91(
    context: ContentLoaderContext,
) : PagedContentParser(
    context = context,
    source = ContentParserSource.PINSE91,
    pageSize = 24,
), ContentParserAuthProvider {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("91pinse.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val authUrl: String = "https://91pinse.com/accounts/login/"

    override suspend fun isAuthorized(): Boolean = false // Placeholder

    override suspend fun getUsername(): String = "" // Placeholder

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,      // 更新
        SortOrder.POPULARITY,  // 观看次数
        SortOrder.RATING,      // 收藏次数
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = LinkedHashSet<ContentTag>()
        val tagGroups = ArrayList<ContentTagGroup>()

        val listTags = LinkedHashSet<ContentTag>().apply {
            add(ContentTag("热门视频", "path:/v/hot/", source))
            add(ContentTag("当前最热", "path:/rank/current-hot", source))
            add(ContentTag("月度趋势", "path:/rank/month-hot", source))
            add(ContentTag("月度收藏", "path:/rank/month-favorite", source))
            add(ContentTag("精选视频", "path:/rank/recently-featured", source))
        }
        tags.addAll(listTags)
        tagGroups.add(ContentTagGroup("榜单/频道", listTags))

        return ContentListFilterOptions(
            availableTags = tags,
            tagGroups = tagGroups,
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: ContentListFilter,
    ): List<Content> {
        val url = buildUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        
        val mangaMap = LinkedHashMap<String, Content>()

        fun putContent(href: String, title: String, coverUrl: String?) {
            val finalTitle = title.cleanTitle().takeIf { it.isNotBlank() && !DURATION_REGEX.matches(it) } ?: return
            mangaMap[href] = Content(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = finalTitle,
                altTitles = emptySet(),
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                authors = emptySet(),
                tags = emptySet(),
                state = null,
                description = null,
                contentRating = ContentRating.ADULT,
                source = source,
                rating = RATING_UNKNOWN,
            )
        }

        doc.select(".video-grid article.video-card").forEach { card ->
            val content = parseVideoCard(card) ?: return@forEach
            putContent(content.url, content.title, content.coverUrl)
        }

        if (mangaMap.isNotEmpty()) {
            return mangaMap.values.toList()
        }

        val elements = doc.select(".video-grid a.video-card-title[href*='/v/']").takeIf { it.isNotEmpty() }
            ?: doc.select("a[href*='/v/']")

        for (el in elements) {
            val href = el.attrAsRelativeUrl("href")
            if (!isVideoPath(href)) continue
            if (href.contains("/author/") || href.contains("/search")) continue
            
            val text = el.text().cleanTitle()
            if (text == "更多" || text.contains("热门") || text.contains("榜单") || text.contains("最新")) continue

            val isDuration = DURATION_REGEX.matches(text)
            val container = el.parent() ?: el
            
            val existing = mangaMap[href]
            if (existing != null && (isDuration || (text.isNotBlank() && !isDuration && existing.title.length > text.length))) {
                continue
            }

            val title = if (!isDuration && text.isNotBlank()) text else {
                el.attr("title").cleanTitle().takeIf { it.isNotBlank() }
                    ?: container.selectFirst(".title, h3, h4, .video-title, .link")?.text()?.cleanTitle()?.takeIf { it.isNotBlank() }
                    ?: container.parent()?.selectFirst(".title, h3, h4, .video-title, .link")?.text()?.cleanTitle()?.takeIf { it.isNotBlank() }
                    ?: if (isDuration) null else text
            }
            
            val finalTitle = title?.cleanTitle()?.takeIf { !DURATION_REGEX.matches(it) } ?: existing?.title
            if (finalTitle.isNullOrBlank()) continue

            val img = el.selectFirst("img") ?: container.selectFirst("img") ?: container.parent()?.selectFirst("img")
            val coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src")
                ?: img?.attrAsAbsoluteUrlOrNull("src")
                ?: img?.attrAsAbsoluteUrlOrNull("data-original")
                ?: existing?.coverUrl

            putContent(href, finalTitle, coverUrl)
        }

        return mangaMap.values.toList()
    }

    override suspend fun getRelatedContent(seed: Content): List<Content> {
        val url = seed.publicUrl.ifBlank { seed.url.toAbsoluteUrl(domain) }
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val similarSection = doc.select("section").firstOrNull {
            it.selectFirst(".watch-section-title")?.text()?.trim() == "相似视频"
        } ?: return emptyList()
        return similarSection.select("article.video-card")
            .mapNotNull(::parseVideoCard)
            .filter { it.id != seed.id && it.url != seed.url }
            .distinctBy { it.id }
    }

    private fun parseVideoCard(card: org.jsoup.nodes.Element): Content? {
        val titleEl = card.selectFirst("a.video-card-title[href*='/v/']") ?: return null
        val href = titleEl.attrAsRelativeUrl("href")
        if (!isVideoPath(href)) return null

        val title = titleEl.attr("title").cleanTitle().takeIf { it.isNotBlank() }
            ?: titleEl.text().cleanTitle().takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[aria-label][href*='/v/']")?.attr("aria-label")?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: return null
        if (DURATION_REGEX.matches(title)) return null

        val img = card.selectFirst("img")
        val coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src")
            ?: img?.attrAsAbsoluteUrlOrNull("src")
            ?: img?.attrAsAbsoluteUrlOrNull("data-original")

        return Content(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            authors = emptySet(),
            tags = emptySet(),
            state = null,
            description = null,
            contentRating = ContentRating.ADULT,
            source = source,
            rating = RATING_UNKNOWN,
        )
    }

    private fun isVideoPath(href: String): Boolean {
        val idPath = href.substringAfter("/v/", "").substringBefore("/").substringBefore("?").trim()
        return idPath.isNotEmpty() && idPath.all { it.isDigit() } && href.length >= 5
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""</?mark>""", RegexOption.IGNORE_CASE), "")
            .replace("&lt;mark&gt;", "", ignoreCase = true)
            .replace("&lt;/mark&gt;", "", ignoreCase = true)
            .trim()
    }

    private fun buildUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        if (!filter.query.isNullOrBlank()) {
            return "https://$domain/v/search?keyword=${java.net.URLEncoder.encode(filter.query!!, "UTF-8")}&page=$page"
        }

        val pathTag = filter.tags.find { it.key.startsWith("path:") }?.key?.substringAfter(":")
        
        val base = if (pathTag != null) {
            "https://$domain$pathTag"
        } else {
            val sortParam = when (order) {
                SortOrder.POPULARITY -> "view"
                SortOrder.RATING -> "like"
                else -> null
            }
            if (sortParam != null) "https://$domain/v/?sort=$sortParam" else "https://$domain/v/"
        }

        return if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        
        val tags = LinkedHashSet<ContentTag>()
        
        // Strategy 1: Look for on-page tags (usually in 'badge' or specific tag classes)
        doc.select("a.badge, a[href*='/tag/'], a[href*='/search?t=']").forEach { 
            val tagText = it.text().trim()
            if (isValidTag(tagText, title)) {
                tags.add(ContentTag(title = tagText, key = tagText, source = source))
            }
        }

        // Strategy 2: Fallback to keywords if no on-page tags found
        if (tags.isEmpty()) {
            val keywords = doc.selectFirst("meta[name=keywords]")?.attr("content")
            if (!keywords.isNullOrBlank()) {
                keywords.split(",").forEach { raw ->
                    val tagText = raw.trim()
                    if (isValidTag(tagText, title)) {
                        tags.add(ContentTag(title = tagText, key = tagText, source = source))
                    }
                }
            }
        }
        
        val chapter = ContentChapter(
            id = generateUid("${manga.url}_video"),
            url = manga.url,
            title = "Video",
            number = 1f,
            uploadDate = 0,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = title,
            description = desc,
            tags = tags,
            chapters = listOf(chapter),
        )
    }

    private fun isValidTag(tag: String, title: String): Boolean {
        if (tag.isBlank()) return false
        if (tag.length > 20) return false // Likely a title or sentence
        
        val loweredTag = tag.lowercase()
        // Filter out generic video site words
        val genericWords = listOf(
            "视频", "观看", "91pinse", "91", "pinse", "免费", "在线", "高清", 
            "hot", "video", "watch", "download", "下载", "无码", "有码"
        )
        if (genericWords.any { it == loweredTag }) return false
        
        // Filter out tags that are too similar to the title
        if (loweredTag == title.lowercase()) return false
        
        return true
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val baseUrl = chapter.url.toAbsoluteUrl(domain)
        val sources = ArrayList<String>()
        val seenUrls = HashSet<String>()

        fun addSource(url: String?) {
            val u = normalizeMediaUrl(url) ?: return
            if (seenUrls.add(u)) sources.add(u)
        }

        val doc = webClient.httpGet(baseUrl, getRequestHeaders()).parseHtml()
        val playbackApiPath = findPlaybackApiPath(doc.outerHtml())

        if (playbackApiPath != null) {
            val apiHeaders = getRequestHeaders().newBuilder()
                .set("Accept", "application/json")
                .set("Origin", "https://$domain")
                .set("Referer", baseUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .build()
            val apiUrl = buildPlaybackApiUrl(playbackApiPath)
            val json = webClient.httpPost(apiUrl, emptyMap(), apiHeaders).parseJson()
            addSource(json.optString("url"))
            addSource(json.optString("fallback_url"))
        } else {
            extractLegacySources(doc, baseUrl, ::addSource)

            if (baseUrl.contains("/v/")) {
                val hdUrl = baseUrl.replace("/v/", "/vhd/")
                runCatching {
                    val hdDoc = webClient.httpGet(hdUrl, getRequestHeaders()).parseHtml()
                    extractLegacySources(hdDoc, hdUrl, ::addSource)
                }
            }
        }

        return sources.map { url ->
            val headersMap = mutableMapOf<String, String>()
            // 严格匹配最新浏览器日志中的播放请求头
            headersMap["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
            headersMap["Referer"] = "https://$domain/"
            headersMap["Origin"] = "https://$domain"
            headersMap["Accept"] = "*/*"
            headersMap["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8"
            headersMap["Sec-Fetch-Dest"] = "empty"
            headersMap["Sec-Fetch-Mode"] = "cors"
            headersMap["Sec-Fetch-Site"] = "cross-site"
            // 增加 Client Hints (Cloudflare 强校验项)
            headersMap["sec-ch-ua"] = "\"Not:A-Brand\";v=\"99\", \"Microsoft Edge\";v=\"145\", \"Chromium\";v=\"145\""
            headersMap["sec-ch-ua-mobile"] = "?0"
            headersMap["sec-ch-ua-platform"] = "\"Windows\""
            
            // 尝试透传 Cookie (如果存在)
            runCatching {
                url.toHttpUrlOrNull()?.let { httpUrl ->
                    val cookies = context.cookieJar.loadForRequest(httpUrl)
                    if (cookies.isNotEmpty()) {
                        headersMap["Cookie"] = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    }
                }
            }
            
            ContentPage(
                id = generateUid(url),
                url = url,
                preview = null,
                headers = headersMap,
                source = source,
            )
        }
    }

    private suspend fun extractLegacySources(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        addSource: (String?) -> Unit,
    ) {
        doc.select("iframe[src]").forEach {
            val iframeUrl = it.attrAsAbsoluteUrlOrNull("src") ?: return@forEach
            if (iframeUrl.contains("player") || iframeUrl.contains("video") || iframeUrl.contains(domain)) {
                runCatching {
                    val iframeHeaders = getRequestHeaders().newBuilder()
                        .set("Referer", pageUrl)
                        .build()
                    val iframeDoc = webClient.httpGet(iframeUrl, iframeHeaders).parseHtml()
                    findUrlsByRegex(iframeDoc.outerHtml()).forEach(addSource)
                }
            }
        }
        findUrlsByRegex(doc.outerHtml()).forEach(addSource)
    }

    internal fun findPlaybackApiPath(html: String): String? {
        return PLAYBACK_API_REGEX.find(html)?.groupValues?.get(1)
    }

    internal fun buildPlaybackApiUrl(path: String) =
        requireNotNull(path.toAbsoluteUrl(domain).toHttpUrlOrNull())
            .newBuilder()
            .addQueryParameter("hd", "1")
            .build()

    internal fun findUrlsByRegex(html: String): List<String> {
        val cleanHtml = decodeMediaText(html)
        val found = LinkedHashSet<String>()

        extractMediaUrls(cleanHtml).forEach(found::add)
        
        Regex("""["']([A-Za-z0-9+/=]{40,})["']""").findAll(cleanHtml).forEach { m ->
            runCatching {
                val decoded = java.util.Base64.getDecoder().decode(m.groupValues[1]).toString(Charsets.UTF_8)
                if (decoded.contains("http")) {
                    extractMediaUrls(decodeMediaText(decoded)).forEach(found::add)
                }
            }
        }

        Regex("""(?i)base64,([A-Za-z0-9+/=]+)""").findAll(cleanHtml).forEach { m ->
            runCatching {
                val decoded = java.util.Base64.getDecoder().decode(m.groupValues[1]).toString(Charsets.UTF_8)
                if (decoded.contains("http")) {
                    extractMediaUrls(decodeMediaText(decoded)).forEach(found::add)
                }
            }
        }
        
        return found.toList()
    }

    private fun extractMediaUrls(text: String): List<String> {
        return MEDIA_URL_REGEX.findAll(text)
            .mapNotNull { normalizeMediaUrl(it.value) }
            .toList()
    }

    private fun normalizeMediaUrl(url: String?): String? {
        val raw = url?.trim()?.trim('"', '\'') ?: return null
        if (raw.isBlank()) return null

        // 不使用 URLDecoder，避免把签名中的 + 转为空格。
        val unescaped = decodeMediaText(raw)
        
        val candidate = unescaped
            .substringBefore("\"")
            .substringBefore("'")
            .substringBefore("<")
            .trim()
            .trimEnd(',', ';', '\\', ')', ']', '}')

        if (!(candidate.startsWith("http://") || candidate.startsWith("https://"))) return null
        
        val lower = candidate.lowercase()
        if (
            !lower.contains(".m3u8") &&
            !lower.contains(".mp4") &&
            !lower.contains("/pl.m3u8")
        ) return null

        return candidate
    }

    private companion object {
        private val DURATION_REGEX = Regex("""^\s*(?:[\[\(]?)\s*(?:\d{1,2}:)?\d{1,2}:\d{2}\s*(?:[\]\)]?)\s*(?:HD)?\s*$""", RegexOption.IGNORE_CASE)
        private val MEDIA_URL_REGEX = Regex(
            """https?://[^"'\s<>]+\.(?:m3u8|mp4)(?:[?#][^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val PLAYBACK_API_REGEX = Regex(
            """["'](/api/videos/\d+/playback)["']""",
            RegexOption.IGNORE_CASE,
        )

        private fun decodeMediaText(value: String): String {
            return Parser.unescapeEntities(value, false)
                .replace("\\/", "/")
                .replace("\\u002f", "/", ignoreCase = true)
                .replace("\\u003d", "=", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\x2f", "/", ignoreCase = true)
                .replace("\\x3d", "=", ignoreCase = true)
                .replace("\\x26", "&", ignoreCase = true)
        }
    }
}
