package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.net.URLEncoder
import java.util.EnumSet
import java.util.LinkedHashSet

/**
 * Generic web-selector parser engine for Chinese anime/video sites.
 *
 * Each source overrides the configuration properties below and the engine
 * interprets them to perform search, details, chapter extraction, and
 * video URL resolution.
 *
 * ## Configuration Properties
 *
 * ### Search / List
 * - [searchUrlTemplate]: search URL with `{keyword}` placeholder
 * - [subjectFormatId]: "a" (CSS selectLists) or "indexed" (CSS selectNames + selectLinks)
 * - [selectLists]: CSS selector for search result items (format "a")
 * - [selectNames]: CSS selector for result names (format "indexed")
 * - [selectLinks]: CSS selector for result links (format "indexed")
 * - [preferShorterName]: pick shorter title when multiple candidates exist
 * - [searchUseOnlyFirstWord]: use only the first word of the search query
 * - [searchRemoveSpecial]: remove special characters from search query
 *
 * ### Chapters / Episodes
 * - [channelFormatId]: "index-grouped" (tabs + episode lists) or "no-channel" (flat)
 * - [selectChannelNames]: CSS selector for channel/tab names
 * - [matchChannelName]: regex to extract channel name from tab text
 * - [selectEpisodeLists]: CSS selector for episode list containers
 * - [selectEpisodesFromList]: CSS selector for episode items within a list
 * - [selectEpisodeLinksFromList]: CSS attribute for episode links (default: "href")
 * - [matchEpisodeSortFromName]: regex to extract episode number from title
 * - [selectEpisodes]: CSS selector for episodes (format "no-channel")
 * - [selectEpisodeLinks]: CSS attribute for episode links (format "no-channel")
 *
 * ### Video URL
 * - [enableNestedUrl]: whether to follow a nested URL before extracting video
 * - [matchNestedUrl]: regex to find the nested URL to follow
 * - [matchVideoUrl]: regex to extract the video URL from page HTML
 * - [cookies]: cookies to set when requesting video page
 * - [addHeadersToVideo]: extra HTTP headers for video requests
 *
 * ### Filters
 * - [filterByEpisodeSort]: whether to filter by episode number
 * - [filterBySubjectName]: whether to filter by anime name
 * - [categoryTags]: optional list of genre/category tags for filter options
 * - [categoryTagParam]: URL parameter name for category tags (default: "type")
 */
internal abstract class WebSelectorParser(
    source: ContentParserSource,
    context: ContentLoaderContext,
    pageSize: Int = 24,
) : PagedContentParser(context, source, pageSize = pageSize) {

    // ========================================================================
    // Configuration — override in each parser
    // ========================================================================

    /** Search URL template. Use `{keyword}` as placeholder for the search term. */
    protected abstract val searchUrlTemplate: String

    /** Subject format: "a" (single selector) or "indexed" (names + links). */
    protected open val subjectFormatId: String = "a"

    /** CSS selector for result items (format "a"). */
    protected open val selectLists: String = ""

    /** CSS selector for result names (format "indexed"). */
    protected open val selectNames: String = ""

    /** CSS selector for result links (format "indexed"). */
    protected open val selectLinks: String = ""

    /** Prefer shorter title when multiple candidates exist. */
    protected open val preferShorterName: Boolean = false

    /** Channel format: "index-grouped" (tabs + lists) or "no-channel" (flat). */
    protected open val channelFormatId: String = "index-grouped"

    /** CSS selector for channel/tab name elements. */
    protected open val selectChannelNames: String = ""

    /** Regex to extract channel name from tab text. */
    protected open val matchChannelName: String = ""

    /** CSS selector for episode list containers. */
    protected open val selectEpisodeLists: String = ""

    /** CSS selector for episode items within a list. */
    protected open val selectEpisodesFromList: String = "a"

    /** CSS attribute for episode link URLs (default: "href"). */
    protected open val selectEpisodeLinksFromList: String = ""

    /** Regex to extract episode number from title. */
    protected open val matchEpisodeSortFromName: String = "第\\s*(?<ep>.+)\\s*[话集]"

    /** CSS selector for episodes (format "no-channel"). */
    protected open val selectEpisodes: String = ""

    /** CSS attribute for episode links (format "no-channel"). */
    protected open val selectEpisodeLinks: String = ""

    /** Whether to follow a nested URL (e.g. iframe) before extracting video. */
    protected open val enableNestedUrl: Boolean = false

    /** Regex to find the nested URL to follow. */
    protected open val matchNestedUrl: String = ""

    /** Regex to extract the video URL from page HTML. */
    protected open val matchVideoUrl: String = ""

    /** Cookies to set when requesting the video page. */
    protected open val cookies: String = ""

    /** Extra HTTP headers for video requests. */
    protected open val addHeadersToVideo: Map<String, String> = emptyMap()

    /** Use only the first word of the search query. */
    protected open val searchUseOnlyFirstWord: Boolean = true

    /** Remove special characters from search query. */
    protected open val searchRemoveSpecial: Boolean = false

    /** Request interval in milliseconds between requests. */
    protected open val requestInterval: Int = 0

    /** Whether to filter episodes by sort number. */
    protected open val filterByEpisodeSort: Boolean = false

    /** Whether to filter by subject name. */
    protected open val filterBySubjectName: Boolean = false

    /** Source description. */
    protected open val sourceDescription: String = ""

    /** Source icon URL. */
    protected open val sourceIconUrl: String = ""

    // ========================================================================
    // Filters
    // ========================================================================

    /** Optional genre/category tags for filter options. */
    protected open val categoryTags: List<Pair<String, String>> = emptyList()

    /** URL parameter name for category tags. */
    protected open val categoryTagParam: String = "type"

    /** Optional sort order mapping (SortOrder → URL parameter value). */
    protected open val sortOrderMapping: Map<SortOrder, String> = emptyMap()

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override val configKeyDomain: ConfigKey.Domain by lazy {
        val host = Regex("https?://([^/]+)").find(searchUrlTemplate)?.groupValues?.get(1) ?: "unknown"
        ConfigKey.Domain(host)
    }

    override val availableSortOrders: Set<SortOrder>
        get() = if (sortOrderMapping.isNotEmpty()) {
            EnumSet.copyOf(sortOrderMapping.keys)
        } else {
            EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)
        }

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = categoryTags.isNotEmpty(),
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        if (categoryTags.isEmpty()) {
            return ContentListFilterOptions(
                availableContentTypes = EnumSet.of(ContentType.VIDEO),
            )
        }

        val tags = LinkedHashSet<ContentTag>()
        val tagGroups = ArrayList<ContentTagGroup>()

        val groupTags = LinkedHashSet<ContentTag>()
        for ((key, label) in categoryTags) {
            val tag = ContentTag(title = label, key = "$categoryTagParam:$key", source = source)
            groupTags.add(tag)
            tags.add(tag)
        }
        if (groupTags.isNotEmpty()) {
            tagGroups.add(ContentTagGroup("分类", groupTags))
        }

        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
            availableTags = tags,
            tagGroups = tagGroups,
        )
    }

    // ========================================================================
    // Search / List
    // ========================================================================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        var keyword = filter.query?.trim().orEmpty()

        if (keyword.isNotEmpty() && searchUseOnlyFirstWord) {
            keyword = keyword.split("\\s+".toRegex()).firstOrNull() ?: keyword
        }
        if (keyword.isNotEmpty() && searchRemoveSpecial) {
            keyword = keyword.replace(Regex("[^\\w\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]"), "")
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        var searchUrl = searchUrlTemplate.replace("{keyword}", encoded)

        // Append category tags
        if (categoryTags.isNotEmpty() && filter.tags.isNotEmpty()) {
            val tagValues = filter.tags
                .filter { it.key.startsWith("$categoryTagParam:") }
                .map { it.key.substringAfter("$categoryTagParam:") }
            if (tagValues.isNotEmpty()) {
                val sep = if (searchUrl.contains("?")) "&" else "?"
                searchUrl += "$sep$categoryTagParam=${tagValues.joinToString(",")}"
            }
        }

        // Apply sort order
        if (order != SortOrder.UPDATED && sortOrderMapping.isNotEmpty()) {
            val sortValue = sortOrderMapping[order]
            if (sortValue != null) {
                val sep = if (searchUrl.contains("?")) "&" else "?"
                searchUrl += "${sep}order=$sortValue"
            }
        }

        // Pagination
        if (page > 1) {
            if (searchUrl.contains("?")) {
                searchUrl = searchUrl.replace(Regex("(&page=\\d+|&p=\\d+)(&|$)"), "")
                searchUrl += "&page=$page"
            } else {
                searchUrl += "?page=$page"
            }
        }

        val doc = webClient.httpGet(searchUrl, getRequestHeaders()).parseHtml()
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        val results = when (subjectFormatId) {
            "indexed" -> extractIndexedSubjects(doc)
            else -> extractSubjectsA(doc)
        }

        for ((name, href, coverUrl) in results) {
            if (href.isBlank() || !seen.add(href)) continue
            val publicUrl = href.toAbsoluteUrl(domain)
            items.add(
                Content(
                    id = generateUid(href),
                    url = href.toRelativeUrl(domain),
                    publicUrl = publicUrl,
                    title = name.takeIf { it.isNotBlank() } ?: "Untitled",
                    coverUrl = coverUrl,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    largeCoverUrl = null,
                    description = null,
                    chapters = null,
                    source = source,
                )
            )
            if (items.size >= pageSize) break
        }

        return items
    }

    /** Extract {name, href, coverUrl} via "a" format selector. */
    private fun extractSubjectsA(doc: Document): List<Triple<String, String, String?>> {
        if (selectLists.isBlank()) return emptyList()
        val elements = doc.select(selectLists)
        return elements.mapNotNull { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            var name = a.text().trim()
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null

            if (preferShorterName && a.children().isNotEmpty()) {
                val directText = a.ownText().trim()
                if (directText.isNotBlank()) name = directText
            }

            val cover = findCoverImage(el)
            Triple(name, href, cover)
        }
    }

    /** Extract {name, href, coverUrl} via "indexed" format selector. */
    private fun extractIndexedSubjects(doc: Document): List<Triple<String, String, String?>> {
        val names = if (selectNames.isNotBlank()) {
            doc.select(selectNames).map { it.text().trim() }
        } else emptyList()

        val linkElements = if (selectLinks.isNotBlank()) {
            doc.select(selectLinks)
        } else emptyList()

        return names.zip(linkElements).map { (name, el) ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a")
            val href = a?.attr("href") ?: ""
            val cover = findCoverImage(el)
            Triple(name, href, cover)
        }
    }

    /** Walk up to find an <img> near the element. Prefers lazy-load attributes over src. */
    private fun findCoverImage(el: Element): String? {
        var current: Element? = el
        for (i in 0..5) {
            if (current == null) break
            val img = current.selectFirst("img")
            if (img != null) {
                // Prefer lazy-load attributes first (real image), fall back to src
                val cover = img.attrAsAbsoluteUrlOrNull("data-original")
                    ?: img.attrAsAbsoluteUrlOrNull("data-src")
                    ?: img.attrAsAbsoluteUrlOrNull("data-img")
                    ?: img.attrAsAbsoluteUrlOrNull("data-lazy")
                    ?: img.attrAsAbsoluteUrlOrNull("src")
                if (cover != null && !isPlaceholderUrl(cover)) return cover
                // If src is the only option, use it anyway
                return img.attrAsAbsoluteUrlOrNull("src")
            }
            current = current.parent()
        }
        return null
    }

    /** Check if a URL looks like a placeholder/loading image. */
    private fun isPlaceholderUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("load.gif") || lower.contains("loading.gif") ||
            lower.contains("load.png") || lower.contains("loading.png") ||
            lower.contains("loading.svg") || lower.contains("placeholder") ||
            lower.contains("/load.") || lower.contains("nopic") ||
            lower.contains("no-img") || lower.contains("default.jpg") ||
            lower.contains("default.png") || lower.contains("img_none")
    }

    // ========================================================================
    // Details / Chapters
    // ========================================================================

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val chapters = when (channelFormatId) {
            "no-channel" -> extractNoChannelEpisodes(doc)
            else -> extractGroupedChannelEpisodes(doc)
        }

        val coverUrl = extractDetailCover(doc) ?: manga.coverUrl
        val description = extractDescription(doc)

        return manga.copy(
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            description = description,
            chapters = chapters,
        )
    }

    private fun extractDetailCover(doc: Document): String? {
        // Try og:image meta first
        doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !isPlaceholderUrl(it) }?.let { return it }

        for (sel in listOf(
            ".detail-pic img", ".vod-pic img", ".module-item-pic img",
            ".detail-img img", ".video-cover img", ".video-pic img",
            ".thumb img", ".poster img", "img.cover",
            ".module-item-pic > img", ".vodlist_thumb img", ".content_thumb img",
            ".detail-poster img", ".detail-cover img",
        )) {
            doc.selectFirst(sel)?.let { img ->
                // Prefer lazy-load attributes over src
                val cover = img.attrAsAbsoluteUrlOrNull("data-original")
                    ?: img.attrAsAbsoluteUrlOrNull("data-src")
                    ?: img.attrAsAbsoluteUrlOrNull("data-img")
                    ?: img.attrAsAbsoluteUrlOrNull("src")
                if (cover != null && !isPlaceholderUrl(cover)) return cover
            }
        }

        // Fallback: any img with data-src or data-original that looks like a cover
        for (attr in listOf("data-original", "data-src", "data-img")) {
            val img = doc.selectFirst("img[$attr]")
            img?.attrAsAbsoluteUrlOrNull(attr)?.let { url ->
                if (!isPlaceholderUrl(url) && !url.contains("logo") && !url.contains("icon")) return url
            }
        }

        return null
    }

    private fun extractDescription(doc: Document): String? {
        doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        for (sel in listOf(
            ".detail-desc", ".vod-desc", ".module-info-text", ".video-desc",
            ".detail-content", ".summary", ".desc", ".intro"
        )) {
            doc.selectFirst(sel)?.text()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return null
    }

    private fun extractGroupedChannelEpisodes(doc: Document): List<ContentChapter> {
        if (selectChannelNames.isBlank() || selectEpisodeLists.isBlank()) return emptyList()
        val chapters = ArrayList<ContentChapter>()

        val channelTabs = doc.select(selectChannelNames)
        val channelNamePattern = matchChannelName.takeIf { it.isNotBlank() }?.let { Regex(it) }
        val episodeLists = doc.select(selectEpisodeLists)

        var epCounter = 1

        for (i in channelTabs.indices) {
            val channelName = channelTabs.getOrNull(i)?.text()?.trim() ?: "Channel $i"
            val matchedName = channelNamePattern?.find(channelName)?.run {
                groups["ch"]?.value ?: groups[1]?.value ?: channelName
            } ?: channelName

            val episodeList = episodeLists.getOrNull(i) ?: continue
            val episodeAnchors = if (selectEpisodesFromList.isNotBlank()) {
                episodeList.select(selectEpisodesFromList)
            } else {
                episodeList.select("a")
            }

            val episodeSortPattern = matchEpisodeSortFromName
                .takeIf { it.isNotBlank() }?.let { Regex(it) }

            for (a in episodeAnchors) {
                val href = if (selectEpisodeLinksFromList.isNotBlank()) {
                    a.attr(selectEpisodeLinksFromList)
                } else {
                    a.attr("href")
                }
                if (href.isBlank()) continue

                val title = a.text().trim()
                val epNum = episodeSortPattern?.find(title)?.run {
                    groups["ep"]?.value?.toFloatOrNull() ?: groups[1]?.value?.toFloatOrNull()
                } ?: epCounter.toFloat()

                chapters.add(
                    ContentChapter(
                        id = generateUid("${href}|${matchedName}|$epNum"),
                        url = href.toRelativeUrl(domain),
                        title = title.takeIf { it.isNotBlank() } ?: "EP${epNum.toInt()}",
                        number = epNum,
                        uploadDate = 0L,
                        volume = 0,
                        branch = matchedName.takeIf { it != channelName },
                        scanlator = null,
                        source = source,
                    )
                )
                epCounter++
            }
        }

        return chapters
    }

    private fun extractNoChannelEpisodes(doc: Document): List<ContentChapter> {
        val chapters = ArrayList<ContentChapter>()

        val episodeAnchors = if (selectEpisodes.isNotBlank()) {
            doc.select(selectEpisodes)
        } else {
            doc.select("a")
        }

        val episodeSortPattern = matchEpisodeSortFromName
            .takeIf { it.isNotBlank() }?.let { Regex(it) }

        var epCounter = 1
        for (a in episodeAnchors) {
            val href = if (selectEpisodeLinks.isNotBlank()) {
                a.attr(selectEpisodeLinks)
            } else {
                a.attr("href")
            }
            if (href.isBlank()) continue

            val title = a.text().trim()
            val epNum = episodeSortPattern?.find(title)?.run {
                groups["ep"]?.value?.toFloatOrNull() ?: groups[1]?.value?.toFloatOrNull()
            } ?: epCounter.toFloat()

            chapters.add(
                ContentChapter(
                    id = generateUid("${href}|$epNum"),
                    url = href.toRelativeUrl(domain),
                    title = title.takeIf { it.isNotBlank() } ?: "EP${epNum.toInt()}",
                    number = epNum,
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            )
            epCounter++
        }

        return chapters
    }

    // ========================================================================
    // Video URL Extraction
    // ========================================================================

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl, getRequestHeaders()).parseHtml()
        val html = doc.outerHtml()

        // Strategy 1: extract player_aaaa JSON config (mxproCMS pattern)
        extractPlayerConfig(html)?.let { url ->
            return listOf(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
        }

        // Strategy 2: AJAX play endpoint (e.g. /_senfun_plays/ID/ep)
        extractFromAjaxPlayEndpoint(doc, html)?.let { url ->
            return listOf(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
        }

        // Strategy 3: static regex / nested URL extraction
        extractVideoUrl(doc)?.let { url ->
            return listOf(ContentPage(id = generateUid(url), url = url, preview = null, source = source))
        }

        // Strategy 4: iframe src
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
        if (iframeSrc != null && iframeSrc.startsWith("http")) {
            return listOf(ContentPage(id = generateUid(iframeSrc), url = iframeSrc, preview = null, source = source))
        }

        // Strategy 5: browser/WebView for JS-rendered video URLs
        context.requestBrowserAction(this, fullUrl)
        return emptyList()
    }

    /**
     * Extract video URL from `player_aaaa` config object embedded in the page.
     * Common pattern on mxproCMS-based sites (e.g. 番茄动漫, 饭团动漫).
     *
     * Example: var player_aaaa={"flag":"play",...,"url":"https://hn.bfvvs.com/play/XXX/index.m3u8",...}
     */
    private fun extractPlayerConfig(html: String): String? {
        val idx = html.indexOf("player_aaaa")
        if (idx < 0) return null

        val openBrace = html.indexOf('{', idx)
        if (openBrace < 0) return null

        // Match balanced braces to get the full JSON object
        var depth = 0
        var closeBrace = -1
        for (i in openBrace until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        closeBrace = i
                        break
                    }
                }
            }
        }
        if (closeBrace < 0) return null

        val configJson = html.substring(openBrace, closeBrace + 1)

        // Parse the "url" field from the JSON
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val urlMatch = urlRegex.find(configJson) ?: return null
        val url = urlMatch.groupValues[1]
            .replace("\\/", "/") // unescape JSON slashes
        return if (url.contains(".m3u8") || url.contains(".mp4")) url else null
    }

    /**
     * Try to find and call an AJAX play endpoint.
     *
     * Many sites use a pattern like:
     *   $._<site>_plays/<vod_id>/<ep>
     *   e.g. /_senfun_plays/2020897226/ep24
     *
     * The response is JSON with a `video_plays` array containing `play_data` URLs.
     */
    private suspend fun extractFromAjaxPlayEndpoint(doc: Document, html: String): String? {
        // Find AJAX endpoint patterns in script tags
        val ajaxPatterns = listOf(
            Regex("""url\s*:\s*"(/_[a-zA-Z]+_plays?/[^"]+)""""),
            Regex(""""(/_[a-zA-Z]+_plays?/[^"]+)""""),
        )

        var ajaxPath: String? = null
        for (pattern in ajaxPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                ajaxPath = match.groupValues[1]
                break
            }
        }

        if (ajaxPath == null) return null

        val ajaxUrl = ajaxPath.toAbsoluteUrl(domain)
        return try {
            val body = webClient.httpGet(ajaxUrl, getRequestHeaders()).body.string()
            extractPlayDataFromJson(body)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract the first `play_data` URL from a JSON response.
     *
     * Handles several common response formats:
     * 1. { "video_plays": [{"play_data": "https://..."}] }
     * 2. { "url": "https://..." }
     * 3. { "data": [{...}] }
     */
    private fun extractPlayDataFromJson(body: String): String? {
        // Try video_plays[0].play_data
        val playDataRegex = Regex(""""play_data"\s*:\s*"([^"]+)"""")
        val playDataMatch = playDataRegex.find(body)
        if (playDataMatch != null) {
            val url = playDataMatch.groupValues[1].replace("\\/", "/")
            if (url.contains(".m3u8") || url.contains(".mp4")) return url
        }

        // Try direct url field
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
        val urlMatch = urlRegex.find(body)
        if (urlMatch != null) {
            return urlMatch.groupValues[1].replace("\\/", "/")
        }

        // Try mp4
        val mp4Regex = Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)"""")
        val mp4Match = mp4Regex.find(body)
        if (mp4Match != null) {
            return mp4Match.groupValues[1].replace("\\/", "/")
        }

        return null
    }

    private suspend fun extractVideoUrl(doc: Document): String? {
        val html = doc.outerHtml()

        // Strategy 1: follow nested URL
        if (enableNestedUrl && matchNestedUrl != "\$^" && matchNestedUrl.isNotBlank()) {
            val nestedUrl = findNestedUrl(html)
            if (nestedUrl != null) {
                try {
                    val nestedDoc = webClient.httpGet(nestedUrl, getRequestHeaders()).parseHtml()
                    val nestedHtml = nestedDoc.outerHtml()
                    extractVideoUrlFromHtml(nestedHtml)?.let { return it }
                } catch (_: Exception) {
                    // fall through
                }
            }
        }

        // Strategy 2: direct match
        return extractVideoUrlFromHtml(html)
    }

    private fun findNestedUrl(html: String): String? {
        val regex = try {
            Regex(matchNestedUrl)
        } catch (_: Exception) { return null }

        val urlPattern = Regex("""https?://[^\s"'<>]+""")
        for (match in urlPattern.findAll(html)) {
            val url = match.value
            if (regex.containsMatchIn(url)) return url
        }
        return null
    }

    private fun extractVideoUrlFromHtml(html: String): String? {
        if (matchVideoUrl.isBlank()) return null

        val urlPattern = Regex("""https?://[^\s"'<>]+""")
        val videoRegex = try {
            Regex(matchVideoUrl, setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
            try {
                Regex(matchVideoUrl, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            } catch (_: Exception) { return null }
        }

        for (match in urlPattern.findAll(html)) {
            val url = match.value
            val vm = videoRegex.find(url) ?: continue
            val captured = tryOrNull { vm.groups["v"]?.value }
            return captured ?: vm.value
        }

        // Fallback: try the regex on the full HTML
        val m = videoRegex.find(html) ?: return null
        val captured = tryOrNull { m.groups["v"]?.value }
        return captured ?: m.value
    }

    private inline fun <T> tryOrNull(block: () -> T): T? {
        return try { block() } catch (_: IllegalArgumentException) { null }
    }
}