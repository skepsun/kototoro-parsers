package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import java.net.URLEncoder
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import java.util.EnumSet

@MangaSourceParser(name = "PINSE91", title = "91Pinse", locale = "zh", type = ContentType.HENTAI_VIDEO)
internal class Pinse91(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.PINSE91,
    pageSize = 24,
), MangaParserAuthProvider {

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

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        val tagGroups = ArrayList<MangaTagGroup>()

        val listTags = LinkedHashSet<MangaTag>().apply {
            add(MangaTag("热门视频", "path:/v/hot/", source))
            add(MangaTag("当前最热", "path:/rank/current-hot", source))
            add(MangaTag("月度趋势", "path:/rank/month-hot", source))
            add(MangaTag("月度收藏", "path:/rank/month-favorite", source))
            add(MangaTag("精选视频", "path:/rank/recently-featured", source))
        }
        tags.addAll(listTags)
        tagGroups.add(MangaTagGroup("榜单/频道", listTags))

        return MangaListFilterOptions(
            availableTags = tags,
            tagGroups = tagGroups,
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = buildUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        
        val mangaMap = LinkedHashMap<String, Manga>()
        val elements = doc.select("a[href*='/v/']")
        val durationRegex = Regex("""\d+:\d+""")

        for (el in elements) {
            val href = el.attrAsRelativeUrl("href")
            if (href == "/v/" || href == "/v" || href.contains("/author/") || href.length < 5) continue
            
            val text = el.text().trim()
            if (text.isEmpty() || text.length < 2 || text == "更多" || text.contains("热门") || text.contains("榜单") || text.contains("最新")) continue

            val isDuration = durationRegex.matches(text)
            val container = el.parent() ?: el
            
            val existing = mangaMap[href]
            if (existing != null && (isDuration || existing.title.length > text.length)) {
                continue
            }

            val title = if (!isDuration) text else {
                el.attr("title").takeIf { it.isNotBlank() }
                    ?: container.selectFirst(".title, h3, h4, .video-title")?.text()
                    ?: text
            }
            
            if (title == text && isDuration && existing != null) continue

            val img = el.selectFirst("img") ?: container.selectFirst("img") ?: container.parent()?.selectFirst("img")
            val coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src")
                ?: img?.attrAsAbsoluteUrlOrNull("src")
                ?: img?.attrAsAbsoluteUrlOrNull("data-original")
                ?: existing?.coverUrl

            mangaMap[href] = Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title.trim(),
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
        }

        return mangaMap.values.toList()
    }

    private fun buildUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
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

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        
        val tags = LinkedHashSet<MangaTag>()
        
        // Strategy 1: Look for on-page tags (usually in 'badge' or specific tag classes)
        doc.select("a.badge, a[href*='/tag/'], a[href*='/search?t=']").forEach { 
            val tagText = it.text().trim()
            if (isValidTag(tagText, title)) {
                tags.add(MangaTag(title = tagText, key = tagText, source = source))
            }
        }

        // Strategy 2: Fallback to keywords if no on-page tags found
        if (tags.isEmpty()) {
            val keywords = doc.selectFirst("meta[name=keywords]")?.attr("content")
            if (!keywords.isNullOrBlank()) {
                keywords.split(",").forEach { raw ->
                    val tagText = raw.trim()
                    if (isValidTag(tagText, title)) {
                        tags.add(MangaTag(title = tagText, key = tagText, source = source))
                    }
                }
            }
        }
        
        val chapter = MangaChapter(
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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val baseUrl = chapter.url.toAbsoluteUrl(domain)
        val sources = ArrayList<String>()
        val seenUrls = HashSet<String>()

        fun addSource(url: String?) {
            val u = url?.takeIf { it.isNotBlank() } ?: return
            if (seenUrls.add(u)) sources.add(u)
        }

        val doc = webClient.httpGet(baseUrl, getRequestHeaders()).parseHtml()
        
        // 1. Iframes (often used for external players)
        doc.select("iframe[src]").forEach {
            val iframeUrl = it.attrAsAbsoluteUrlOrNull("src") ?: return@forEach
            // Only follow iframes that look like players or internal redirectors
            if (iframeUrl.contains("player") || iframeUrl.contains("video") || iframeUrl.contains(domain)) {
                runCatching {
                    val iframeHeaders = getRequestHeaders().newBuilder()
                        .set("Referer", baseUrl)
                        .build()
                    val iframeDoc = webClient.httpGet(iframeUrl, iframeHeaders).parseHtml()
                    // Use regex inside iframe
                    findUrlsByRegex(iframeDoc.outerHtml()).forEach { u -> addSource(u) }
                }
            }
        }

        // 2. Regex in main page
        findUrlsByRegex(doc.outerHtml()).forEach { addSource(it) }

        return sources.map { url ->
            val headersMap = mutableMapOf<String, String>()
            headersMap["Referer"] = baseUrl
            headersMap["User-Agent"] = getRequestHeaders()["User-Agent"] ?: ""
            
            MangaPage(
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
        // m3u8 patterns
        Regex("""https?://[^"'\s>]+\.m3u8[?#][^"'\s>]*""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        Regex("""https?://[^"'\s>]+\.m3u8""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        // mp4 patterns
        Regex("""https?://[^"'\s>]+\.mp4[?#][^"'\s>]*""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        Regex("""https?://[^"'\s>]+\.mp4""", RegexOption.IGNORE_CASE).findAll(html).forEach { found.add(it.value) }
        
        // Detect possible Base64 encoded URLs in scripts
        Regex("""(?i)base64,([A-Za-z0-9+/=]+)""").findAll(html).forEach { m ->
            runCatching {
                val decoded = java.util.Base64.getDecoder().decode(m.groupValues[1]).toString(Charsets.UTF_8)
                if (decoded.contains("http")) found.add(decoded)
            }
        }
        
        return found
    }
}

