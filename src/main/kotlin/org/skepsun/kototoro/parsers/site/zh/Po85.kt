package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import java.util.EnumSet

@ContentSourceParser(name = "PO85", title = "85PO", locale = "zh", type = ContentType.HENTAI_VIDEO)
internal class Po85(
    context: ContentLoaderContext,
) : PagedContentParser(
    context = context,
    source = ContentParserSource.PO85,
    pageSize = 30,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("www.85po.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,      // 最新影片
        SortOrder.POPULARITY,  // 热门影片
        SortOrder.RATING,      // 最赞影片
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
            add(ContentTag("最新影片", "path:/latest-updates/", source))
            add(ContentTag("最赞", "path:/top-rated/", source))
            add(ContentTag("热门", "path:/most-popular/", source))
            add(ContentTag("4K影片", "path:/4k/", source))
        }
        tags.addAll(listTags)
        tagGroups.add(ContentTagGroup("榜单/频道", listTags))

        // Attempt to fetch tags from /tags/ page
        runCatching {
            val doc = webClient.httpGet("https://$domain/tags/", getRequestHeaders()).parseHtml()
            val categoryTags = LinkedHashSet<ContentTag>()
            doc.select("a[href*='/tags/']").forEach { 
                val text = it.text().trim().replace(Regex("""\s+\d+$"""), "") // Remove count
                val href = it.attrAsRelativeUrl("href")
                if (text.length > 1 && !href.endsWith("/tags/")) {
                    categoryTags.add(ContentTag(text, "path:$href", source))
                }
            }
            if (categoryTags.isNotEmpty()) {
                tags.addAll(categoryTags)
                tagGroups.add(ContentTagGroup("所有标签", categoryTags))
            }
        }.onFailure {
             // Fallback if tag page fails
             val fallback = LinkedHashSet<ContentTag>().apply {
                 add(ContentTag("真实偷拍", "path:/tags/zhen-shi-tou-pai/", source))
             }
             tags.addAll(fallback)
             tagGroups.add(ContentTagGroup("热门标签", fallback))
        }

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
        val items = ArrayList<Content>()

        // Specific selector for 85PO grid items
        val videoElements = doc.select(".video-item, .thumb-block, .card, a[href*='/v/']")
        val seen = HashSet<String>()

        for (el in videoElements) {
            val href = el.attrAsRelativeUrl("href")
            if (href == "/v/" || href == "/v" || href.contains("/author/") || href.length < 5 || !seen.add(href)) continue
            
            val container = el.parent() ?: el
            
            var title = el.attr("title").takeIf { it.isNotBlank() }
                ?: el.selectFirst(".title, h3, h4, .video-title")?.text()
                ?: el.text().trim()

            // Cleanup title: Remove resolution prefixes like "4K", "2K", "1K" and duration like "1:46"
            title = title.replace(Regex("""^[124]K\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^\d+:\d+\s+"""), "")
                .trim()

            if (title.isBlank() || title.length < 2) continue

            val img = el.selectFirst("img") ?: container.selectFirst("img") ?: container.parent()?.selectFirst("img")
            val coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src")
                ?: img?.attrAsAbsoluteUrlOrNull("src")
                ?: img?.attrAsAbsoluteUrlOrNull("data-original")

            items.add(
                Content(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = coverUrl,
                    largeCoverUrl = null,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = null,
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                )
            )
        }

        return items
    }

    private fun buildUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        if (!filter.query.isNullOrBlank()) {
            val encodedQuery = java.net.URLEncoder.encode(filter.query!!, "UTF-8")
            // Search pagination: /search/query/page/
            return if (page > 1) "https://$domain/search/$encodedQuery/$page/" else "https://$domain/search/$encodedQuery/"
        }

        val pathTag = filter.tags.find { it.key.startsWith("path:") }?.key?.substringAfter(":")
        
        val base = if (pathTag != null) {
            "https://$domain$pathTag"
        } else {
            when (order) {
                SortOrder.POPULARITY -> "https://$domain/most-popular/"
                SortOrder.RATING -> "https://$domain/top-rated/"
                else -> "https://$domain/latest-updates/"
            }
        }

        // Pagination: /page/ format for lists, but tags might use different logic
        // If it's a tag and page > 1, the user said it doesn't change URL, 
        // but often /2/ works or ?page=2. Since /2/ failed, try ?from= and other guesses.
        val paginationPath = if (page > 1) {
            if (base.contains("/tags/")) {
                 // Try multiple patterns for tags if standard fails
                 // Guesses: /2/, ?page=2, ?from=31
                 "?from=${(page - 1) * 24 + 1}" // KVS standard
            } else {
                 "$page/"
            }
        } else ""

        val result = if (base.endsWith("/")) "$base$paginationPath" else "$base/$paginationPath"
        return result
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        
        val tags = LinkedHashSet<ContentTag>()
        doc.select(".video-tags a, .tags a, a[href*='/tags/']").forEach { 
            val tagText = it.text().trim()
            if (tagText.isNotBlank() && tagText != title && tagText.length < 20 && !tagText.contains("85PO")) {
                tags.add(ContentTag(title = tagText, key = tagText, source = source))
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

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val baseUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(baseUrl, getRequestHeaders()).parseHtml()
        
        val sources = ArrayList<String>()
        val seenUrls = HashSet<String>()

        fun addSource(url: String?) {
            val u = url?.takeIf { it.isNotBlank() } ?: return
            if (seenUrls.add(u)) sources.add(u)
        }

        // 1. Specific 85PO download/stream links
        doc.select("a[href*='/get_file/']").forEach { 
            addSource(it.attrAsAbsoluteUrlOrNull("href"))
        }

        // 2. Iframes (often used for external players)
        doc.select("iframe[src]").forEach {
            val iframeUrl = it.attrAsAbsoluteUrlOrNull("src") ?: return@forEach
            if (iframeUrl.contains("player") || iframeUrl.contains("video") || iframeUrl.contains(domain)) {
                runCatching {
                    val iframeHeaders = getRequestHeaders().newBuilder().set("Referer", baseUrl).build()
                    val iframeDoc = webClient.httpGet(iframeUrl, iframeHeaders).parseHtml()
                    findUrlsByRegex(iframeDoc.outerHtml()).forEach { u -> addSource(u) }
                }
            }
        }

        // 3. Regex in main page
        findUrlsByRegex(doc.outerHtml()).forEach { addSource(it) }

        return sources.map { url ->
            val headersMap = mutableMapOf<String, String>()
            headersMap["Referer"] = baseUrl
            headersMap["User-Agent"] = getRequestHeaders()["User-Agent"] ?: ""
            
            ContentPage(
                id = generateUid(url),
                url = url,
                preview = null,
                headers = headersMap,
                source = source,
            )
        }
    }

    private fun findUrlsByRegex(html: String): List<String> {
        val found = ArrayList<String>()
        Regex("""https?://[^"'\s>]+\.m3u8[?#][^"'\s>]*""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        Regex("""https?://[^"'\s>]+\.m3u8""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        Regex("""https?://[^"'\s>]+\.mp4[?#][^"'\s>]*""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        Regex("""https?://[^"'\s>]+\.mp4""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        
        // Base64
        Regex("""(?i)base64,([A-Za-z0-9+/=]+)""").findAll(html).forEach { m ->
            runCatching {
                val decoded = java.util.Base64.getDecoder().decode(m.groupValues[1]).toString(Charsets.UTF_8)
                if (decoded.contains("http")) found.add(decoded)
            }
        }
        return found
    }
}
