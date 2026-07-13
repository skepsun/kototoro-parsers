package org.skepsun.kototoro.parsers.site.zh.animeko

import org.jsoup.nodes.Document
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
 * Each source is configured via an [AnimekoMediaSource] JSON object and this
 * engine interprets the selector configuration to perform search, details,
 * and video URL extraction.
 *
 * The engine supports:
 * - Subject format "a": selectLists CSS selector for anchor elements
 * - Subject format "indexed": separate selectNames + selectLinks CSS selectors
 * - Channel format "index-grouped": channel tabs with grouped episode lists
 * - Channel format "no-channel": flat episode list without channel grouping
 * - Video extraction: regex-based URL matching with optional nested URL support
 */
internal abstract class WebSelectorParser(
    source: ContentParserSource,
    context: ContentLoaderContext,
    pageSize: Int = 24,
) : PagedContentParser(context, source, pageSize = pageSize) {

    // -----------------------------------------------------------------------
    // Abstract: each subclass provides its source config
    // -----------------------------------------------------------------------

    protected abstract val mediaSourceConfig: AnimekoMediaSource

    override val configKeyDomain: ConfigKey.Domain by lazy {
        val url = mediaSourceConfig.searchConfig.searchUrl
        val host = Regex("https?://([^/]+)").find(url)?.groupValues?.get(1) ?: "unknown"
        ConfigKey.Domain(host)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
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

    // -----------------------------------------------------------------------
    // Search / List
    // -----------------------------------------------------------------------

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val sc = mediaSourceConfig.searchConfig

        // Build search URL: allow empty query (app may browse without keyword)
        val keyword = filter.query?.trim() ?: ""
        val processedKeyword = if (keyword.isNotEmpty() && sc.searchUseOnlyFirstWord) {
            keyword.split("\\s+".toRegex()).firstOrNull() ?: keyword
        } else keyword

        val encoded = URLEncoder.encode(processedKeyword, "UTF-8")
        val searchUrl = sc.searchUrl.replace("{keyword}", encoded)

        val doc = webClient.httpGet(searchUrl, getRequestHeaders()).parseHtml()
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        // Extract results based on subject format
        val results = when (sc.subjectFormatId) {
            "indexed" -> extractIndexedSubjects(doc, sc)
            else -> extractSubjectsA(doc, sc)
        }

        for ((name, href) in results) {
            if (href.isBlank() || !seen.add(href)) continue
            val publicUrl = href.toAbsoluteUrl(domain)
            items.add(
                Content(
                    id = generateUid(href),
                    url = href.toRelativeUrl(domain),
                    publicUrl = publicUrl,
                    title = name.takeIf { it.isNotBlank() } ?: "Untitled",
                    coverUrl = null,
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

    private fun extractSubjectsA(doc: Document, sc: AnimekoSearchConfig): List<Pair<String, String>> {
        val selector = sc.selectorSubjectFormatA ?: return emptyList()
        val elements = doc.select(selector.selectLists)
        return elements.mapNotNull { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val name = a.text().trim()
            val href = a.attr("href")
            if (href.isBlank()) null else name to href
        }
    }

    private fun extractIndexedSubjects(doc: Document, sc: AnimekoSearchConfig): List<Pair<String, String>> {
        val sel = sc.selectorSubjectFormatIndexed ?: return emptyList()
        val names = if (sel.selectNames.isNotBlank()) {
            doc.select(sel.selectNames).map { it.text().trim() }
        } else emptyList()

        val links = doc.select(sel.selectLinks).mapNotNull { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            a.attr("href")
        }

        return names.zip(links).map { (name, href) ->
            name to href
        }
    }

    // -----------------------------------------------------------------------
    // Details / Chapters
    // -----------------------------------------------------------------------

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val sc = mediaSourceConfig.searchConfig

        val chapters = when (sc.channelFormatId) {
            "no-channel" -> extractNoChannelEpisodes(doc, sc)
            else -> extractGroupedChannelEpisodes(doc, sc)
        }

        return manga.copy(
            chapters = chapters,
        )
    }

    private fun extractGroupedChannelEpisodes(doc: Document, sc: AnimekoSearchConfig): List<ContentChapter> {
        val channelConfig = sc.selectorChannelFormatFlattened ?: return emptyList()
        val chapters = ArrayList<ContentChapter>()

        val channelTabs = doc.select(channelConfig.selectChannelNames)
        val channelNamePattern = channelConfig.matchChannelName.takeIf { it.isNotBlank() }
            ?.let { Regex(it) }

        val episodeLists = doc.select(channelConfig.selectEpisodeLists)

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

            val episodeSortPattern = channelConfig.matchEpisodeSortFromName.takeIf { it.isNotBlank() }
                ?.let { Regex(it) }

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
                } ?: (chapters.size + 1).toFloat()

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

        val episodeSortPattern = noChannelConfig.matchEpisodeSortFromName.takeIf { it.isNotBlank() }
            ?.let { Regex(it) }

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
            } ?: (chapters.size + 1).toFloat()

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
        }

        return chapters
    }

    // -----------------------------------------------------------------------
    // Video URL Extraction
    // -----------------------------------------------------------------------

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

        if (mv.enableNestedUrl && mv.matchNestedUrl != "\$^") {
            val nestedRegex = try {
                Regex(mv.matchNestedUrl, RegexOption.IGNORE_CASE)
            } catch (_: Exception) { null }

            nestedRegex?.find(html)?.let { match ->
                val nestedUrl = match.value
                try {
                    val nestedDoc = webClient.httpGet(nestedUrl, getRequestHeaders()).parseHtml()
                    val nestedHtml = nestedDoc.outerHtml()
                    return extractVideoUrlFromHtml(nestedHtml, mv.matchVideoUrl)
                } catch (_: Exception) {
                    // Nested URL fetch failed, fall through to direct extraction
                }
            }
        }

        return extractVideoUrlFromHtml(html, mv.matchVideoUrl)
    }

    private fun extractVideoUrlFromHtml(html: String, matchPattern: String): String? {
        if (matchPattern.isBlank()) return null

        val regex = try {
            Regex(matchPattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        } catch (_: Exception) {
            try {
                Regex(matchPattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            } catch (_: Exception) {
                return null
            }
        }

        val match = regex.find(html) ?: return null

        match.groups["v"]?.value?.let { return it }

        return match.value
    }
}