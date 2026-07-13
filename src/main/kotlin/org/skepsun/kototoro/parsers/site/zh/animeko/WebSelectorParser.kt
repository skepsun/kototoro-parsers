package org.skepsun.kototoro.parsers.site.zh.animeko

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.net.URLEncoder
import java.util.EnumSet

/**
 * Generic web-selector parser engine that drives all animeko sources.
 *
 * Each source is configured via an [AnimekoMediaSource] and this engine
 * interprets the selector configuration to perform search, details,
 * and video URL extraction.
 */
internal abstract class WebSelectorParser(
    source: ContentParserSource,
    context: ContentLoaderContext,
    pageSize: Int = 24,
) : PagedContentParser(context, source, pageSize = pageSize) {

    protected abstract val mediaSourceConfig: AnimekoMediaSource

    override val configKeyDomain: ConfigKey.Domain by lazy {
        val url = mediaSourceConfig.searchConfig.searchUrl
        val host = Regex("https?://([^/]+)").find(url)?.groupValues?.get(1) ?: "unknown"
        ConfigKey.Domain(host)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    // ========================================================================
    // Search / List
    // ========================================================================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val sc = mediaSourceConfig.searchConfig

        val keyword = filter.query?.trim().orEmpty()
        val processedKeyword = if (keyword.isNotEmpty() && sc.searchUseOnlyFirstWord) {
            keyword.split("\\s+".toRegex()).firstOrNull() ?: keyword
        } else keyword

        val encoded = URLEncoder.encode(processedKeyword, "UTF-8")
        var searchUrl = sc.searchUrl.replace("{keyword}", encoded)

        // Pagination: inject page number. Most Chinese sites use one of these patterns.
        if (page > 1) {
            // Try to detect if the URL already has query params
            searchUrl = if (searchUrl.contains("?")) {
                // Append &page=N — but also try common alternatives
                searchUrl.replace(Regex("(&page=\\d+|&p=\\d+)(&|$)"), "") // remove existing page
                "$searchUrl&page=$page"
            } else {
                "$searchUrl?page=$page"
            }
        }

        val doc = webClient.httpGet(searchUrl, getRequestHeaders()).parseHtml()
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        val results = when (sc.subjectFormatId) {
            "indexed" -> extractIndexedSubjects(doc, sc)
            else -> extractSubjectsA(doc, sc)
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
    private fun extractSubjectsA(doc: Document, sc: AnimekoSearchConfig): List<Triple<String, String, String?>> {
        val selector = sc.selectorSubjectFormatA ?: return emptyList()
        val elements = doc.select(selector.selectLists)
        return elements.mapNotNull { el ->
            // The selector may target <a> directly or a parent container
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val name = a.text().trim()
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null

            // Extract cover from parent container or sibling img
            val cover = findCoverImage(el, sc)

            Triple(name, href, cover)
        }
    }

    /** Extract {name, href, coverUrl} via "indexed" format selector. */
    private fun extractIndexedSubjects(doc: Document, sc: AnimekoSearchConfig): List<Triple<String, String, String?>> {
        val sel = sc.selectorSubjectFormatIndexed ?: return emptyList()
        val names = if (sel.selectNames.isNotBlank()) {
            doc.select(sel.selectNames).map { it.text().trim() }
        } else emptyList()

        val linkElements = doc.select(sel.selectLinks)

        return names.zip(linkElements).map { (name, el) ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a")
            val href = a?.attr("href") ?: ""
            val cover = findCoverImage(el, sc)
            Triple(name, href, cover)
        }
    }

    /** Walk up to find an <img> near the element. */
    private fun findCoverImage(el: Element, sc: AnimekoSearchConfig): String? {
        // Try parent chain up to 5 levels
        var current: Element? = el
        for (i in 0..5) {
            if (current == null) break
            val img = current.selectFirst("img")
            if (img != null) {
                return img.attrAsAbsoluteUrlOrNull("src")
                    ?: img.attrAsAbsoluteUrlOrNull("data-src")
                    ?: img.attrAsAbsoluteUrlOrNull("data-original")
            }
            current = current.parent()
        }
        return null
    }

    // ========================================================================
    // Details / Chapters
    // ========================================================================

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val sc = mediaSourceConfig.searchConfig

        val chapters = when (sc.channelFormatId) {
            "no-channel" -> extractNoChannelEpisodes(doc, sc)
            else -> extractGroupedChannelEpisodes(doc, sc)
        }

        // Extract cover image from detail page
        val coverUrl = extractDetailCover(doc) ?: manga.coverUrl

        // Extract description from meta tags or page content
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
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        // Try common cover image patterns
        for (sel in listOf(
            ".detail-pic img", ".vod-pic img", ".module-item-pic img",
            ".detail-img img", ".video-cover img", ".video-pic img",
            ".thumb img", ".poster img", "img.cover",
        )) {
            doc.selectFirst(sel)?.let { img ->
                return img.attrAsAbsoluteUrlOrNull("src")
                    ?: img.attrAsAbsoluteUrlOrNull("data-src")
                    ?: img.attrAsAbsoluteUrlOrNull("data-original")
            }
        }
        return null
    }

    private fun extractDescription(doc: Document): String? {
        // Try meta description
        doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        // Try common description containers
        for (sel in listOf(
            ".detail-desc", ".vod-desc", ".module-info-text", ".video-desc",
            ".detail-content", ".summary", ".desc", ".intro"
        )) {
            doc.selectFirst(sel)?.text()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return null
    }

    private fun extractGroupedChannelEpisodes(doc: Document, sc: AnimekoSearchConfig): List<ContentChapter> {
        val channelConfig = sc.selectorChannelFormatFlattened ?: return emptyList()
        val chapters = ArrayList<ContentChapter>()

        val channelTabs = doc.select(channelConfig.selectChannelNames)
        val channelNamePattern = channelConfig.matchChannelName.takeIf { it.isNotBlank() }?.let { Regex(it) }
        val episodeLists = doc.select(channelConfig.selectEpisodeLists)

        var epCounter = 1

        for (i in channelTabs.indices) {
            val channelName = channelTabs.getOrNull(i)?.text()?.trim() ?: "Channel $i"
            val matchedName = channelNamePattern?.find(channelName)?.run {
                groups["ch"]?.value ?: groups[1]?.value ?: channelName
            } ?: channelName

            val episodeList = episodeLists.getOrNull(i) ?: continue
            val episodeAnchors = if (channelConfig.selectEpisodesFromList.isNotBlank()) {
                episodeList.select(channelConfig.selectEpisodesFromList)
            } else {
                episodeList.select("a")
            }

            val episodeSortPattern = channelConfig.matchEpisodeSortFromName
                .takeIf { it.isNotBlank() }?.let { Regex(it) }

            for (a in episodeAnchors) {
                val href = if (channelConfig.selectEpisodeLinksFromList.isNotBlank()) {
                    a.attr(channelConfig.selectEpisodeLinksFromList)
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

    private fun extractNoChannelEpisodes(doc: Document, sc: AnimekoSearchConfig): List<ContentChapter> {
        val noChannelConfig = sc.selectorChannelFormatNoChannel ?: return emptyList()
        val chapters = ArrayList<ContentChapter>()

        val episodeAnchors = if (noChannelConfig.selectEpisodes.isNotBlank()) {
            doc.select(noChannelConfig.selectEpisodes)
        } else {
            doc.select("a")
        }

        val episodeSortPattern = noChannelConfig.matchEpisodeSortFromName
            .takeIf { it.isNotBlank() }?.let { Regex(it) }

        var epCounter = 1
        for (a in episodeAnchors) {
            val href = if (noChannelConfig.selectEpisodeLinks.isNotBlank()) {
                a.attr(noChannelConfig.selectEpisodeLinks)
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
        val mv = mediaSourceConfig.searchConfig.matchVideo
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl, getRequestHeaders()).parseHtml()

        val videoUrl = extractVideoUrl(doc, mv) ?: return emptyList()

        return listOf(
            ContentPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = null,
                source = source,
            )
        )
    }

    private suspend fun extractVideoUrl(doc: Document, mv: AnimekoMatchVideo): String? {
        val html = doc.outerHtml()

        // Strategy 1: if nested URL is enabled, try to find & follow the nested URL
        if (mv.enableNestedUrl && mv.matchNestedUrl != "\$^") {
            val nestedUrl = findNestedUrl(html, mv.matchNestedUrl)
            if (nestedUrl != null) {
                try {
                    val nestedDoc = webClient.httpGet(nestedUrl, getRequestHeaders()).parseHtml()
                    val nestedHtml = nestedDoc.outerHtml()
                    extractVideoUrlFromHtml(nestedHtml, mv.matchVideoUrl)?.let { return it }
                } catch (_: Exception) {
                    // fall through to direct extraction
                }
            }
        }

        // Strategy 2: direct match on the page HTML
        return extractVideoUrlFromHtml(html, mv.matchVideoUrl)
    }

    /**
     * Search the HTML for a URL that matches [nestedUrlPattern].
     * The pattern is a regex that identifies URLs to follow (e.g. containing m3u8/vip/xigua.php).
     */
    private fun findNestedUrl(html: String, nestedUrlPattern: String): String? {
        val regex = try {
            Regex(nestedUrlPattern)
        } catch (_: Exception) { return null }

        // Extract all URLs from the page, then match against the pattern
        val urlPattern = Regex("""https?://[^\s"'<>]+""")
        for (match in urlPattern.findAll(html)) {
            val url = match.value
            if (regex.containsMatchIn(url)) return url
        }
        return null
    }

    private fun extractVideoUrlFromHtml(html: String, matchPattern: String): String? {
        if (matchPattern.isBlank()) return null

        // First: extract all URLs from the page, test each against the video pattern
        val urlPattern = Regex("""https?://[^\s"'<>]+""")
        val videoRegex = try {
            Regex(matchPattern, setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
            try {
                Regex(matchPattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            } catch (_: Exception) { return null }
        }

        for (match in urlPattern.findAll(html)) {
            val url = match.value
            // Try capturing group "v" (named capture in the regex)
            val vm = videoRegex.find(url)
            if (vm != null) {
                vm.groups["v"]?.value?.let { return it }
                return vm.value
            }
        }

        // Fallback: try the regex on the full HTML
        val m = videoRegex.find(html) ?: return null
        m.groups["v"]?.value?.let { return it }
        return m.value
    }
}