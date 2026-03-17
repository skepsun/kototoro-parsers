package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
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
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.Locale

@ContentSourceParser("SIMPCITY", "SimpCity", "en", type = ContentType.HENTAI_MANGA)
internal class Simpcity(context: ContentLoaderContext) : SimpcityBaseParser(
    context = context,
    source = ContentParserSource.valueOf("SIMPCITY"),
    videoOnly = false,
)

@Broken("Under development")
@ContentSourceParser("SIMPCITY_VIDEO", "SimpCity Video", "en", type = ContentType.HENTAI_VIDEO)
internal class SimpcityVideo(context: ContentLoaderContext) : SimpcityBaseParser(
    context = context,
    source = ContentParserSource.valueOf("SIMPCITY_VIDEO"),
    videoOnly = true,
)

internal abstract class SimpcityBaseParser(
    context: ContentLoaderContext,
    source: ContentParserSource,
    private val videoOnly: Boolean,
) : PagedContentParser(context, source, pageSize = 24), ContentParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("simpcity.cr", "simpcity.su")
    private val replyChaptersKey = ConfigKey.Toggle("reply_chapters", "Reply Chapters (media posts)", false)
    override val authUrl: String
        get() = "https://$domain/login/"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        if (!videoOnly) keys.add(replyChaptersKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val now = System.currentTimeMillis()
        if (cachedForumTags != null && cachedHomeLabelTags != null && (now - lastCacheTime) < CACHE_TTL) {
            logDebug("getFilterOptions: using cached tags")
            return buildFilterOptions(cachedForumTags!!, cachedHomeLabelTags!!)
        }

        logDebug("getFilterOptions: fetching from network")
        val homeDoc = webClient.httpGet("https://$domain/", getRequestHeaders()).parseHtml()
        val forumTags = parseForumTags(homeDoc)
        val homeLabelTags = parseHomeLabelTags(homeDoc)
        
        cachedForumTags = forumTags
        cachedHomeLabelTags = homeLabelTags
        lastCacheTime = now
        
        return buildFilterOptions(forumTags, homeLabelTags)
    }

    private suspend fun buildFilterOptions(forumTags: Set<ContentTag>, homeLabelTags: Set<ContentTag>): ContentListFilterOptions {
        val forumPath = forumTags.firstOrNull { it.title.contains("onlyfans", ignoreCase = true) }
            ?.key
            ?.substringAfter(FORUM_KEY_PREFIX)
            ?: DEFAULT_FORUM_PATH
            
        val prefixTags = fetchPrefixTags(forumPath)
        
        val groups = buildList {
            if (forumTags.isNotEmpty()) add(ContentTagGroup("Forums", forumTags))
            if (prefixTags.isNotEmpty()) add(ContentTagGroup("Prefixes", prefixTags))
            if (homeLabelTags.isNotEmpty()) add(ContentTagGroup("Home Labels", homeLabelTags))
        }
        val allTags = LinkedHashSet<ContentTag>(forumTags.size + prefixTags.size + homeLabelTags.size).apply {
            addAll(forumTags)
            addAll(prefixTags)
            addAll(homeLabelTags)
        }
        return ContentListFilterOptions(
            availableTags = allTags,
            tagGroups = groups,
            availableContentTypes = if (videoOnly) {
                EnumSet.of(ContentType.HENTAI_VIDEO)
            } else {
                EnumSet.of(ContentType.HENTAI_MANGA, ContentType.HENTAI_VIDEO)
            },
        )
    }

    override suspend fun isAuthorized(): Boolean {
        val cookies = context.cookieJar.getCookies(domain)
        return cookies.any { it.name.equals("xf_user", ignoreCase = true) }
    }

    override suspend fun getUsername(): String {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val doc = webClient.httpGet("https://$domain/account/", getRequestHeaders()).parseHtml()
        if (isLoginPage(doc)) throw AuthRequiredException(source)
        return doc.selectFirst("a.p-navgroup-link--user, .menu-userDetails a.username, h1.memberHeader-name span")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "SimpCity User"
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        if (!filter.query.isNullOrBlank()) {
            return searchThreads(page, filter.query)
        }

        val hasForumFilter = filter.tags.any {
            it.key.startsWith(FORUM_KEY_PREFIX) ||
                it.key.startsWith(PREFIX_KEY_PREFIX) ||
                it.key.startsWith(LABEL_KEY_PREFIX)
        }
        val url = when {
            hasForumFilter -> {
                val forumPath = selectedForumPath(filter) ?: DEFAULT_FORUM_PATH
                val prefixIds = resolveSelectedPrefixIds(filter, forumPath)
                buildForumListUrl(forumPath, page, order, prefixIds)
            }
            order == SortOrder.UPDATED || order == SortOrder.NEWEST || order == SortOrder.UPDATED_ASC || order == SortOrder.NEWEST_ASC -> {
                buildWhatsNewUrl(page)
            }
            else -> {
                buildTrendingUrl(page)
            }
        }
        logDebug("getListPage: page=$page order=$order url=$url tags=${filter.tags.size}")
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        logDocShape("getListPage.primary", doc)
        logPaginationState("getListPage.primary", doc, page)
        if (isLoginPage(doc)) {
            logDebug("getListPage: login page detected for $url")
            throw AuthRequiredException(source)
        }
        if (page > 1 && !isRequestedPageLikelyValid(doc, page, url)) {
            logDebug("getListPage: requested page-$page not found in primary document, return empty")
            return emptyList()
        }
        val list = parseThreadCards(doc)
        logDebug("getListPage: structItems=${list.size}")
        if (list.isNotEmpty()) return list
        val flatThreads = parseThreadLinksFlat(doc)
        logDebug("getListPage: flatThreadLinks=${flatThreads.size}")
        if (flatThreads.isNotEmpty()) return flatThreads
        val fallback = parseWhatsNewRows(doc)
        logDebug("getListPage: whatsNewRows=${fallback.size}")
        if (fallback.isNotEmpty()) return fallback
        val nodeExtraRows = parseNodeExtraRows(doc)
        logDebug("getListPage: nodeExtraRows=${nodeExtraRows.size}")
        if (nodeExtraRows.isNotEmpty()) return nodeExtraRows

        if (page <= 1) {
            val homeUrl = "https://$domain/"
            logDebug("getListPage: fallback to home url=$homeUrl")
            val homeDoc = webClient.httpGet(homeUrl, getRequestHeaders()).parseHtml()
            logDocShape("getListPage.homeFallback", homeDoc)
            logPaginationState("getListPage.homeFallback", homeDoc, page)
            if (isLoginPage(homeDoc)) throw AuthRequiredException(source)
            val homeList = parseNodeExtraRows(homeDoc)
            logDebug("getListPage: homeNodeExtraRows=${homeList.size}")
            if (homeList.isNotEmpty()) return homeList
        }

        if (page > 1) {
            logDebug("getListPage: page-$page has no items after primary parse, return empty")
            return emptyList()
        }

        // Final fallback: some first-page endpoints may return anti-bot/minimal shell.
        // Fall back to a known forum thread list to avoid empty home on page 1.
        val safeForumUrl = buildForumListUrl(
            forumPath = DEFAULT_FORUM_PATH,
            page = page,
            order = order,
            prefixIds = emptyList(),
        )
        logDebug("getListPage: fallback to safe forum url=$safeForumUrl")
        val safeDoc = webClient.httpGet(safeForumUrl, getRequestHeaders()).parseHtml()
        logDocShape("getListPage.safeForumFallback", safeDoc)
        logPaginationState("getListPage.safeForumFallback", safeDoc, page)
        if (isLoginPage(safeDoc)) throw AuthRequiredException(source)
        val safeList = parseThreadCards(safeDoc)
        logDebug("getListPage: safeForumItems=${safeList.size}")
        if (safeList.isNotEmpty()) return safeList
        val safeFlat = parseThreadLinksFlat(safeDoc)
        logDebug("getListPage: safeForumFlatLinks=${safeFlat.size}")
        if (safeFlat.isNotEmpty()) return safeFlat
        val safeRows = parseWhatsNewRows(safeDoc)
        logDebug("getListPage: safeForumRows=${safeRows.size}")
        if (safeRows.isNotEmpty()) return safeRows
        val safeNodeRows = parseNodeExtraRows(safeDoc)
        logDebug("getListPage: safeForumNodeRows=${safeNodeRows.size}")
        return safeNodeRows
    }

    override suspend fun getDetails(manga: Content): Content {
        val threadUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(threadUrl, getRequestHeaders()).parseHtml()
        if (isLoginPage(doc)) throw AuthRequiredException(source)
        val titleElement = doc.selectFirst("h1.p-title-value")
        titleElement?.select(".label")?.remove()
        val title = titleElement?.text()?.trim().orEmpty().ifBlank { manga.title }
        val tags = LinkedHashSet<ContentTag>().apply {
            addAll(manga.tags)
            addAll(parseTags(doc))
        }
        val author = doc.selectFirst(".message-attribution-main a.username, a.username")?.text()?.trim()
        val firstPost = doc.selectFirst("article.message-body, .message-userContent .bbWrapper, .bbWrapper")
        val description = firstPost?.html()?.trim().takeUnless { it.isNullOrBlank() }
        val allMedia = extractMedia(doc)
        // Prefer the first non-GIF image as cover, or the very first media found
        val bestCover = allMedia.firstOrNull { it.kind == MediaKind.IMAGE && !it.url.lowercase(Locale.ROOT).endsWith(".gif") }
            ?: allMedia.firstOrNull()
        
        val basePath = normalizeThreadPath(manga.url).orEmpty().ifBlank { manga.url.toRelativeUrl(domain) }
        val chapters = buildChapters(doc, basePath)

        return manga.copy(
            title = title,
            tags = tags,
            authors = setOfNotNull(author),
            description = description,
            contentRating = ContentRating.ADULT,
            coverUrl = bestCover?.preview ?: bestCover?.url ?: manga.coverUrl,
            largeCoverUrl = bestCover?.preview ?: bestCover?.url ?: manga.largeCoverUrl,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val isVideoUrl = chapter.url.startsWith(VIDEO_TOKEN_PREFIX)
        val replyPostId = chapter.url.substringAfter(REPLY_CHAPTER_MARKER, "")
            .trim()
            .takeIf { it.isNotBlank() }
        val isReplyChapter = !isVideoUrl && replyPostId != null
        
        if (isVideoUrl) {
            val token = chapter.url.removePrefix(VIDEO_TOKEN_PREFIX)
            val threadPath = token.substringBefore("|", "")
            val rawUrl = token.substringAfter("|", token)
            val firstPageUrl = threadPath.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain)
            
            val media = if (isExternalVideoHostUrl(rawUrl)) {
                resolveExternalVideoLinks(rawUrl).map { MediaItem(it, null, MediaKind.VIDEO) }
            } else {
                listOf(MediaItem(rawUrl, null, MediaKind.VIDEO))
            }
            
            return media.mapIndexed { index, item ->
                val videoHeaders = getPlayHeaders(item.url, firstPageUrl)
                ContentPage(
                    id = generateUid("${chapter.id}#$index:${item.url}"),
                    url = item.url,
                    preview = item.preview,
                    headers = videoHeaders,
                    source = source,
                ).also {
                    println("Simpcity: Extracted Video URL: ${item.url}")
                }
            }
        }

        val chapterPath = chapter.url.substringBefore(REPLY_CHAPTER_MARKER)
            .ifBlank { chapter.url }
        val firstPageUrl = chapterPath.toAbsoluteUrl(domain)
        val firstDoc = webClient.httpGet(firstPageUrl, getRequestHeaders()).parseHtml()
        if (isLoginPage(firstDoc)) throw AuthRequiredException(source)

        val pageUrls = when {
            isReplyChapter -> listOf(chapterPath.toRelativeUrl(domain))
            videoOnly -> listOf(chapterPath.toRelativeUrl(domain))
            else -> listOf(chapterPath.toRelativeUrl(domain))
        }
        logDebug("getPages: chapter=${chapter.title} isVideoUrl=$isVideoUrl isReply=$isReplyChapter videoOnly=$videoOnly pageUrls=${pageUrls.size}")

        val media = ArrayList<MediaItem>()
        pageUrls.forEachIndexed { index, path ->
            val url = path.toAbsoluteUrl(domain)
            // Add a small delay between page requests to avoid rate limiting
            if (index > 0) kotlinx.coroutines.delay(400)
            
            val doc = if (index == 0 && normalizeThreadPath(path) == normalizeThreadPath(chapterPath)) {
                firstDoc
            } else {
                webClient.httpGet(url, getRequestHeaders()).parseHtml()
            }
            if (isLoginPage(doc)) throw AuthRequiredException(source)
            if (isReplyChapter) {
                val post = findPostById(doc, replyPostId!!)
                if (post != null) media += extractMediaFromBlock(post)
            } else {
                media += extractMedia(doc)
            }
        }

        val mediaAfterResolve = if (videoOnly) {
            resolveExternalVideoMedia(media)
        } else {
            media
        }
        val filteredMedia = selectMediaByMode(mediaAfterResolve)
        logDebug("getPages: mediaRaw=${media.size} mediaAfterResolve=${mediaAfterResolve.size} mediaFiltered=${filteredMedia.size}")
        if (filteredMedia.isEmpty()) {
            context.requestBrowserAction(this, firstPageUrl)
            return emptyList()
        }

        return filteredMedia.mapIndexed { index, item ->
            ContentPage(
                id = generateUid("${chapter.id}#${index}:${item.url}"),
                url = item.url,
                preview = item.preview,
                source = source,
            )
        }
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    private fun getPlayHeaders(videoUrl: String, threadUrl: String? = null): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val videoDomain = domainOf(videoUrl)
        
        // 核心：模拟最新的 Edge/Chrome 145 User-Agent
        headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"
        
        // 针对 Bunkr/Turbo 站群的特殊 Referer 策略
        val referer = when {
            videoDomain.contains("bunkr") || videoDomain.contains("turbo") || videoUrl.contains("gigachad-cdn") -> {
                if (videoDomain.contains("turbo")) "https://turbo.cr/" else "https://bunkr.cr/"
            }
            else -> threadUrl ?: "https://$videoDomain/"
        }
        headers["Referer"] = referer
        
        headers["Accept"] = "*/*"
        headers["Accept-Language"] = "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7"
        headers["Range"] = "bytes=0-"
        
        // 注入 Client Hints (Cloudflare/Bunkr 强校验项)
        headers["sec-ch-ua"] = "\"Not:A-Brand\";v=\"99\", \"Microsoft Edge\";v=\"145\", \"Chromium\";v=\"145\""
        headers["sec-ch-ua-mobile"] = "?0"
        headers["sec-ch-ua-platform"] = "\"Windows\""
        
        headers["Sec-Fetch-Dest"] = "video"
        headers["Sec-Fetch-Mode"] = "no-cors"
        headers["Sec-Fetch-Site"] = "cross-site"
        
        return headers
    }

    private suspend fun searchThreads(page: Int, query: String): List<Content> {
        val searchUrl = buildString {
            append("https://").append(domain)
            append("/search/search?keywords=").append(query.urlEncoded())
            append("&o=date")
        }
        val firstResp = webClient.httpGet(searchUrl, getRequestHeaders())
        val firstDoc = firstResp.parseHtml()
        val resolvedSearchUrl = firstDoc.selectFirst("link[rel='canonical']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("/")) "https://$domain$it" else it }
            ?: firstResp.request.url.toString()
        val targetUrl = if (page <= 1) {
            resolvedSearchUrl
        } else {
            buildSearchPageUrl(resolvedSearchUrl, query, page)
        }
        logDebug("searchThreads: page=$page query=$query resolved=$resolvedSearchUrl target=$targetUrl")
        val doc = if (targetUrl == resolvedSearchUrl) firstDoc else webClient.httpGet(targetUrl, getRequestHeaders()).parseHtml()
        logDocShape("searchThreads", doc)
        logPaginationState("searchThreads", doc, page)
        if (isLoginPage(doc)) {
            logDebug("searchThreads: login page detected for $targetUrl")
            throw AuthRequiredException(source)
        }
        val fromStruct = parseThreadCards(doc)
        logDebug("searchThreads: structItems=${fromStruct.size}")
        if (fromStruct.isNotEmpty()) return fromStruct
        val fromFlat = parseThreadLinksFlat(doc)
        logDebug("searchThreads: flatThreadLinks=${fromFlat.size}")
        if (fromFlat.isNotEmpty()) return fromFlat

        val fallback = doc.select(".contentRow-title a[href*='/threads/'], h3.contentRow-title a[href*='/threads/']")
            .mapNotNull { parseSearchLink(it) }
            .distinctBy { it.url }
        logDebug("searchThreads: fallbackRows=${fallback.size}")
        return fallback
    }

    private fun buildSearchPageUrl(resolvedUrl: String, query: String, page: Int): String {
        val absolute = resolvedUrl.toAbsoluteUrl(domain)
        val base = absolute.substringBefore('?').trimEnd('/')
        val queryPart = absolute.substringAfter('?', "")
        val idMatch = SEARCH_RESULT_ID_REGEX.find(base)
        val withPage = if (idMatch != null) "$base/page-$page" else "$base"
        val params = ArrayList<String>(4)
        params += "q=${query.urlEncoded()}"
        params += "o=date"
        if (idMatch == null) params += "page=$page"
        if (queryPart.isNotBlank()) {
            queryPart.split('&').forEach { part ->
                if (part.isBlank()) return@forEach
                val key = part.substringBefore('=')
                if (key == "q" || key == "o" || key == "page" || key == "keywords") return@forEach
                params += part
            }
        }
        return if (params.isEmpty()) withPage else "$withPage?${params.joinToString("&")}"
    }

    private fun parseSearchLink(link: Element): Content? {
        val path = normalizeThreadPath(link.attr("href")) ?: return null
        val title = link.text().trim().ifBlank { return null }
        return Content(
            id = generateUid(path),
            title = title,
            altTitles = emptySet(),
            url = path,
            publicUrl = path.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseThreadCards(doc: Document): List<Content> {
        val cards = doc.select(".structItem--thread, li.structItem")
        if (cards.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<Content>(cards.size)
        val seen = HashSet<String>(cards.size)
        for (card in cards) {
            val link = card.selectFirst(".structItem-title a[href*='/threads/'], a[data-tp-primary='on'][href*='/threads/'], a[href*='/threads/']")
            val href = link?.attr("href")
                ?: card.selectFirst(".structItem-title[uix-href]")?.attr("uix-href")
                ?: continue
            val path = normalizeThreadPath(href) ?: continue
            if (!seen.add(path)) continue
            val title = (link?.text() ?: card.selectFirst(".structItem-title")?.text().orEmpty()).trim().ifBlank { continue }
            val finalTitle = title.ifBlank {
                card.selectFirst("a[href*='/threads/'][title]")?.attrOrNull("title")?.trim().orEmpty()
            }.ifBlank { continue }
            val cover = extractCoverFromContainer(card)
            val tags = card.select(".label--prefix, .labelLink, a[href*='prefix_id']").mapNotNull { item ->
                val t = item.text().trim()
                if (t.isBlank()) null else ContentTag(title = t, key = t.lowercase(Locale.ROOT), source = source)
            }.toSet()
            val author = card.selectFirst("a.username")?.text()?.trim()

            result += Content(
                id = generateUid(path),
                title = finalTitle,
                altTitles = emptySet(),
                url = path,
                publicUrl = path.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover,
                tags = tags,
                state = null,
                authors = setOfNotNull(author),
                source = source,
            )
        }
        return result
    }

    private fun parseThreadLinksFlat(doc: Document): List<Content> {
        val links = doc.select(
            ".structItem-title a[href*='/threads/'], " +
                "a[data-tp-primary='on'][href*='/threads/'], " +
                "a.node-extra-title[href*='/threads/'], " +
                ".contentRow-title a[href*='/threads/'], " +
                "a[href*='/threads/'][title]"
        )
        if (links.isEmpty()) return emptyList()
        val result = ArrayList<Content>(links.size)
        val seen = HashSet<String>(links.size)
        for (link in links) {
            val base = parseSearchLink(link) ?: continue
            if (!seen.add(base.url)) continue
            val row = link.closest(".structItem, li.block-row, .contentRow, .node-extra-row")
            val cover = extractCoverFromContainer(row)
            val tags = parseTagsFromContainer(row, link)
            val author = row?.selectFirst("a.username")?.text()?.trim()
            result += base.copy(
                coverUrl = cover,
                tags = tags,
                authors = setOfNotNull(author),
            )
        }
        return result
    }

    private fun extractCoverFromContainer(container: Element?): String? {
        if (container == null) return null

        // 1. Prefer background-image from any element that looks like a cover holder (accurate for XenForo)
        val styleEls = container.select(".dcThumbnail, [style*='background-image'], .structItem-cell--icon, .avatar")
        for (el in styleEls) {
            val styleUrl = extractUrlFromStyle(el.attr("style"))
            if (styleUrl != null && !styleUrl.contains("no_image.jpg")) {
                return normalizeSimpcityUrl(styleUrl)
            }
        }

        // 2. Check for real image tags (excluding placeholders)
        val imgEls = container.select("img")
        for (el in imgEls) {
            val src = el.attrOrNull("data-url", "data-src", "src")
            if (src != null && src.isNotBlank() && !src.startsWith("data:") && !src.contains("no_image.jpg")) {
                return normalizeSimpcityUrl(src.toAbsoluteUrl(domain))
            }
        }

        // 3. Final resort: style from any element if it contains a URL
        container.select("[style*='url(']").forEach { el ->
            val url = extractUrlFromStyle(el.attr("style"))
            if (url != null && !url.contains("no_image.jpg")) return normalizeSimpcityUrl(url)
        }

        return null
    }

    private fun normalizeSimpcityUrl(url: String): String {
        if (url.startsWith("data:")) return url
        val absUrl = if (url.startsWith("http")) url else url.toAbsoluteUrl(domain)
        val host = DOMAIN_FROM_URL_REGEX.find(absUrl)?.groupValues?.getOrNull(1) ?: return absUrl
        if (host.contains("simpcity.") && host != domain) {
            return absUrl.replace(host, domain)
        }
        return absUrl
    }

    private fun parseTagsFromContainer(container: Element?, link: Element): Set<ContentTag> {
        val scope = container ?: link
        return scope.select(".label--prefix, .labelLink .label, .label, a[href*='prefix_id']")
            .mapNotNull { item ->
                val t = item.text().trim()
                if (t.isBlank()) null else ContentTag(title = t, key = t.lowercase(Locale.ROOT), source = source)
            }
            .toSet()
    }

    private fun findPostById(doc: Document, postId: String): Element? {
        return doc.selectFirst(
            "article#js-post-$postId, " +
                "article[data-content='post-$postId'], " +
                "li#js-post-$postId, " +
                "li[data-content='post-$postId']"
        )
    }

    private fun extractPostId(post: Element): String? {
        val id = post.attrOrNull("id").orEmpty()
        POST_ID_REGEX.find(id)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        val content = post.attrOrNull("data-content").orEmpty()
        POST_ID_REGEX.find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        val href = post.selectFirst("a[href*='/post-'], a[href*='/posts/']")?.attrOrNull("href").orEmpty()
        return POST_ID_REGEX.find(href)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun postContainsMedia(post: Element): Boolean {
        if (post.selectFirst("img[data-url], img[data-src], img[src], video[src], video source[src], iframe[src]") != null) {
            return true
        }
        return post.selectFirst(
            "a[href*='bunkr'], a[href*='turbo'], a[href*='cyberdrop'], a[href*='saint'], " +
                "a[href$='.mp4'], a[href$='.m3u8'], a[href$='.webm']"
        ) != null
    }

    private fun extractMediaFromBlock(block: Element): List<MediaItem> {
        val result = LinkedHashSet<MediaItem>()

        // New: Prioritize a[href] over img tags to get high-res images directly
        block.select("a[href]").forEach { a ->
            val href = a.attrOrNull("href") ?: return@forEach
            val abs = href.toAbsoluteUrlOrNull(domain) ?: return@forEach
            if (isIgnoredImage(abs)) return@forEach
            when {
                IMAGE_URL_REGEX.containsMatchIn(abs) -> {
                    if (!videoOnly) result += MediaItem(abs, abs, MediaKind.IMAGE)
                }
                VIDEO_URL_REGEX.containsMatchIn(abs) -> result += MediaItem(abs, null, MediaKind.VIDEO)
                isExternalVideoHostUrl(abs) -> result += MediaItem(abs, null, MediaKind.EMBED)
            }
        }

        if (!videoOnly) {
            block.select("img[data-url], img[data-src], img[src]").forEach { img ->
                val raw = img.attrOrNull("data-url", "data-src", "src") ?: return@forEach
                if (isIgnoredMediaUrl(raw)) return@forEach
                val src = raw.toAbsoluteUrlOrNull(domain) ?: return@forEach
                if (isIgnoredImage(src)) return@forEach
                result += MediaItem(url = src, preview = src, kind = MediaKind.IMAGE)
            }
        }

        block.select("video[src], video source[src]").forEach { v ->
            val raw = v.attrOrNull("src") ?: return@forEach
            val src = raw.toAbsoluteUrlOrNull(domain) ?: return@forEach
            val poster = (if (v.tagName() == "source") v.parent() else v)?.attrOrNull("poster")?.toAbsoluteUrlOrNull(domain)
            result += MediaItem(src, poster, MediaKind.VIDEO)
        }

        block.select("iframe[src]").forEach { iframe ->
            val raw = iframe.attrOrNull("src") ?: return@forEach
            if (isIgnoredMediaUrl(raw)) return@forEach
            val src = raw.toAbsoluteUrlOrNull(domain) ?: return@forEach
            result += MediaItem(src, null, MediaKind.EMBED)
        }

        URL_IN_SCRIPT_REGEX.findAll(block.html()).forEach { m ->
            val url = m.value.replace("\\/", "/")
            val kind = when {
                VIDEO_URL_REGEX.containsMatchIn(url) -> MediaKind.VIDEO
                IMAGE_URL_REGEX.containsMatchIn(url) -> MediaKind.IMAGE
                else -> null
            } ?: return@forEach
            if (!videoOnly && kind != MediaKind.IMAGE) return@forEach
            if (videoOnly && kind == MediaKind.IMAGE) return@forEach
            result += MediaItem(url.toAbsoluteUrl(domain), null, kind)
        }

        return result.toList()
    }

    private fun parseWhatsNewRows(doc: Document): List<Content> {
        val rows = doc.select("li.block-row .contentRow, .contentRow")
        if (rows.isEmpty()) return emptyList()
        val result = ArrayList<Content>(rows.size)
        val seen = HashSet<String>(rows.size)
        for (row in rows) {
            val link = row.selectFirst(".contentRow-title a[href*='/threads/'], .contentRow-main > a[href*='/threads/'], .contentRow-main a[href*='/threads/']") ?: continue
            val path = normalizeThreadPath(link.attr("href")) ?: continue
            if (!seen.add(path)) continue
            val title = link.ownText().trim().ifBlank {
                link.text().trim()
            }.ifBlank { continue }
            val cover = extractCoverFromContainer(row)
            val tags = row.select(".label").mapNotNull { item ->
                val t = item.text().trim()
                if (t.isBlank()) null else ContentTag(title = t, key = t.lowercase(Locale.ROOT), source = source)
            }.toSet()
            val author = row.selectFirst("a.username")?.text()?.trim()
            result += Content(
                id = generateUid(path),
                title = title,
                altTitles = emptySet(),
                url = path,
                publicUrl = path.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover,
                tags = tags,
                state = null,
                authors = setOfNotNull(author),
                source = source,
            )
        }
        return result
    }

    private fun parseNodeExtraRows(doc: Document): List<Content> {
        val links = doc.select(".node-extra-title[href*='/threads/'], a.node-extra-title[href*='/threads/']")
        if (links.isEmpty()) return emptyList()
        val result = ArrayList<Content>(links.size)
        val seen = HashSet<String>(links.size)
        for (link in links) {
            val path = normalizeThreadPath(link.attr("href")) ?: continue
            if (!seen.add(path)) continue
            val title = link.ownText().trim().ifBlank { link.text().trim() }.ifBlank { continue }
            val cover = extractCoverFromContainer(link.closest(".node-extra-row"))
            val tags = link.select(".label").mapNotNull { item ->
                val t = item.text().trim()
                if (t.isBlank()) null else ContentTag(title = t, key = t.lowercase(Locale.ROOT), source = source)
            }.toSet()
            result += Content(
                id = generateUid(path),
                title = title,
                altTitles = emptySet(),
                url = path,
                publicUrl = path.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover,
                tags = tags,
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
        return result
    }

    private fun parseTags(doc: Document): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        doc.select("a.tagItem, a.labelLink, a[href*='/tags/'], .label--prefix, h1.p-title-value .label").forEach { a ->
            val title = a.text().trim()
            if (title.isBlank()) return@forEach
            val rawKey = a.attr("href").substringAfterLast('/').substringBefore('?').ifBlank {
                title.lowercase(Locale.ROOT)
            }
            tags += ContentTag(title = title, key = rawKey, source = source)
        }
        return tags
    }

    private suspend fun buildChapters(doc: Document, threadPath: String): List<ContentChapter> {
        if (videoOnly) {
            return buildVideoChapters(doc, threadPath)
        }
        
        if (config[replyChaptersKey]) {
            val replyChapters = buildReplyChapters(doc, threadPath)
            if (replyChapters.isNotEmpty()) return replyChapters
        }
        
        val pagePaths = collectThreadPageUrls(doc, threadPath)
        return pagePaths.mapIndexed { index, path ->
            ContentChapter(
                id = generateUid("${threadPath}#p${index + 1}"),
                title = if (pagePaths.size > 1) "Page ${index + 1}" else "Thread",
                number = (index + 1).toFloat(),
                volume = 0,
                url = path,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
    }

    private suspend fun buildVideoChapters(firstDoc: Document, threadPath: String): List<ContentChapter> {
        val pagePaths = collectThreadPageUrls(
            doc = firstDoc,
            threadPath = threadPath,
        ).take(MAX_VIDEO_AUTO_SCAN_PAGES)
        
        val chapters = ArrayList<ContentChapter>()
        var videoOrdinal = 1
        
        pagePaths.forEachIndexed { pageIndex, path ->
            val doc = if (pageIndex == 0) firstDoc else {
                kotlinx.coroutines.delay(400)
                webClient.httpGet(path.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
            }
            if (isLoginPage(doc)) throw AuthRequiredException(source)
            
            val posts = doc.select("article.message--post, article[data-content^='post-'], li.message")
            posts.forEach { post ->
                val author = post.selectFirst("a.username")?.text()?.trim() ?: "Unknown"
                val date = post.selectFirst("time[data-timestamp]")?.text()?.trim() ?: ""
                
                // Extract videos from this specific post only (no recursive extractMedia which scans whole doc)
                val media = extractMediaFromBlock(post).filter { it.kind == MediaKind.VIDEO || it.kind == MediaKind.EMBED }
                
                media.forEach { item ->
                    chapters += ContentChapter(
                        id = generateUid("${threadPath}#video:${item.url}"),
                        title = "Video $videoOrdinal - $author ($date)".trim(),
                        number = videoOrdinal.toFloat(),
                        volume = 0,
                        url = "$VIDEO_TOKEN_PREFIX$path|${item.url}",
                        scanlator = null,
                        uploadDate = post.selectFirst("time[data-timestamp]")?.attrOrNull("data-timestamp")?.toLongOrNull()?.times(1000L) ?: 0L,
                        branch = null,
                        source = source,
                    )
                    videoOrdinal++
                }
            }
        }
        return chapters
    }

    private suspend fun buildReplyChapters(firstDoc: Document, threadPath: String): List<ContentChapter> {
        val pagePaths = collectThreadPageUrls(
            doc = firstDoc,
            threadPath = threadPath,
        ).take(MAX_REPLY_CHAPTER_SCAN_PAGES)
        val chapters = ArrayList<ContentChapter>()
        var ordinal = 1
        pagePaths.forEachIndexed { pageIndex, path ->
            val doc = if (pageIndex == 0) firstDoc else webClient.httpGet(path.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
            if (isLoginPage(doc)) throw AuthRequiredException(source)
            val posts = doc.select("article.message--post, article[data-content^='post-'], li.message")
            posts.forEach { post ->
                val postId = extractPostId(post) ?: return@forEach
                if (!postContainsMedia(post)) return@forEach
                val author = post.selectFirst("a.username")?.text()?.trim().orEmpty()
                val title = if (author.isBlank()) "Reply $ordinal" else "Reply $ordinal - $author"
                chapters += ContentChapter(
                    id = generateUid("${threadPath}#reply:$postId"),
                    title = title,
                    number = ordinal.toFloat(),
                    volume = 0,
                    url = "${path}${REPLY_CHAPTER_MARKER}${postId}",
                    scanlator = null,
                    uploadDate = post.selectFirst("time[data-timestamp]")?.attrOrNull("data-timestamp")?.toLongOrNull()?.times(1000L)
                        ?: 0L,
                    branch = null,
                    source = source,
                )
                ordinal += 1
                if (chapters.size >= MAX_REPLY_CHAPTER_COUNT) {
                    logDebug("buildReplyChapters: hit chapter limit=$MAX_REPLY_CHAPTER_COUNT")
                    return chapters
                }
            }
        }
        logDebug("buildReplyChapters: pages=${pagePaths.size} chapters=${chapters.size}")
        return chapters
    }

    private fun collectThreadPageUrls(doc: Document, threadPath: String): List<String> {
        val normalizedBase = normalizeThreadPath(threadPath) ?: threadPath.toRelativeUrl(domain)
        val baseRoot = normalizedBase.substringBefore("/page-").trimEnd('/')
        
        val pageIndices = mutableSetOf<Int>()
        pageIndices += pageIndex(normalizedBase)
        
        doc.select("a[href*='/threads/'][href*='/page-'], nav.pageNav-main a[href], a.pageNav-jump[href]").forEach { a ->
            val path = normalizeThreadPath(a.attr("href")) ?: return@forEach
            if (path.substringBefore("/page-").trimEnd('/') == baseRoot) {
                pageIndices += pageIndex(path)
            }
        }
        
        val maxPage = pageIndices.maxOrNull() ?: 1
        // Cap the max page to avoid scanning too many pages if it's a huge thread
        val cappedMax = maxPage.coerceAtMost(MAX_ALL_PAGES_SCAN)
        
        val finalPaths = ArrayList<String>()
        for (i in 1..cappedMax) {
            finalPaths += if (i == 1) "$baseRoot/" else "$baseRoot/page-$i/"
        }
        return finalPaths
    }

    private suspend fun fetchPrefixTags(forumPath: String): Set<ContentTag> {
        val now = System.currentTimeMillis()
        cachedPrefixTags[forumPath]?.takeIf { (now - lastCacheTime) < CACHE_TTL }?.let { return it }

        val url = forumPath.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        logDocShape("fetchPrefixTags", doc)
        if (isLoginPage(doc)) throw AuthRequiredException(source)
        val tags = LinkedHashSet<ContentTag>()
        doc.select("a[href*='prefix_id'], input[name*='prefix_id'], select[name*='prefix_id'] option[value]").forEach { el ->
            val pair = parsePrefixIdAndTitle(el) ?: return@forEach
            tags += ContentTag(
                title = pair.second,
                key = "$PREFIX_KEY_PREFIX${pair.first}",
                source = source,
            )
        }
        cachedPrefixTags[forumPath] = tags
        return tags
    }

    private fun parseForumTags(doc: Document): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        doc.select("a[href*='/forums/'], .node-title a[href*='/forums/']").forEach { a ->
            val path = normalizeForumPath(a.attr("href")) ?: return@forEach
            val title = a.text().trim().ifBlank { return@forEach }
            tags += ContentTag(
                title = title,
                key = "$FORUM_KEY_PREFIX$path",
                source = source,
            )
        }
        return tags
    }

    private fun parseHomeLabelTags(doc: Document): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        doc.select(".node-extra-title .label, .structItem-title .label, .label").forEach { el ->
            val title = el.text().trim()
            if (title.isBlank()) return@forEach
            tags += ContentTag(
                title = title,
                key = "$LABEL_KEY_PREFIX$title",
                source = source,
            )
        }
        return tags
    }

    private fun parsePrefixIdAndTitle(el: Element): Pair<String, String>? {
        if (el.tagName().equals("option", ignoreCase = true)) {
            val id = el.attrOrNull("value")?.trim()?.takeIf { it.all(Char::isDigit) } ?: return null
            val title = el.text().trim().ifBlank { "Prefix $id" }
            return id to title
        }
        if (el.tagName().equals("input", ignoreCase = true)) {
            val id = el.attrOrNull("value")?.trim()?.takeIf { it.all(Char::isDigit) } ?: return null
            val label = el.parent()?.text()?.trim().orEmpty().ifBlank { "Prefix $id" }
            return id to label
        }
        val href = el.attr("href")
        val id = PREFIX_ID_REGEX.find(href)?.groupValues?.getOrNull(1) ?: return null
        val title = el.text().trim().ifBlank { "Prefix $id" }
        return id to title
    }

    private fun selectedForumPath(filter: ContentListFilter): String? {
        return filter.tags.firstOrNull { it.key.startsWith(FORUM_KEY_PREFIX) }?.key?.substringAfter(FORUM_KEY_PREFIX)
    }

    private fun selectedPrefixIds(filter: ContentListFilter): List<String> {
        return filter.tags
            .asSequence()
            .mapNotNull { tag ->
                if (tag.key.startsWith(PREFIX_KEY_PREFIX)) tag.key.substringAfter(PREFIX_KEY_PREFIX) else null
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun selectedLabelTitles(filter: ContentListFilter): List<String> {
        return filter.tags
            .asSequence()
            .mapNotNull { tag ->
                if (tag.key.startsWith(LABEL_KEY_PREFIX)) tag.key.substringAfter(LABEL_KEY_PREFIX) else null
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private suspend fun resolveSelectedPrefixIds(filter: ContentListFilter, forumPath: String): List<String> {
        val explicit = selectedPrefixIds(filter).toMutableSet()
        val labelTitles = selectedLabelTitles(filter)
        if (labelTitles.isEmpty()) return explicit.toList()

        val prefixTags = fetchPrefixTags(forumPath)
        val normalizedMap = prefixTags.associateBy { normalizeLabelForMatch(it.title) }
        labelTitles.forEach { title ->
            val matched = normalizedMap[normalizeLabelForMatch(title)]
                ?: prefixTags.firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?: return@forEach
            val id = matched.key.substringAfter(PREFIX_KEY_PREFIX).takeIf { it.isNotBlank() } ?: return@forEach
            explicit += id
        }
        return explicit.toList()
    }

    private fun buildTrendingUrl(page: Int): String {
        return if (page <= 1) {
            "https://$domain/search-forums/trending/"
        } else {
            "https://$domain/search-forums/trending/page-$page/"
        }
    }

    private fun buildWhatsNewUrl(page: Int): String {
        return if (page <= 1) {
            "https://$domain/whats-new/"
        } else {
            "https://$domain/whats-new/page-$page/"
        }
    }

    private fun buildForumListUrl(forumPath: String, page: Int, order: SortOrder, prefixIds: List<String>): String {
        val path = normalizeForumPath(forumPath) ?: DEFAULT_FORUM_PATH
        val pagedPath = if (page > 1) {
            path.trimEnd('/') + "/page-$page/"
        } else {
            path
        }
        val params = ArrayList<String>(prefixIds.size + 2)
        when (order) {
            SortOrder.POPULARITY -> params += "order=reply_count"
            SortOrder.NEWEST -> params += "order=post_date"
            else -> params += "order=last_post_date"
        }
        prefixIds.forEachIndexed { index, id ->
            params += "prefix_id%5B$index%5D=${id.urlEncoded()}"
        }
        return buildString {
            append(pagedPath.toAbsoluteUrl(domain))
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
    }

    private fun normalizeForumPath(rawHref: String): String? {
        if (rawHref.isBlank()) return null
        var path = rawHref.toAbsoluteUrl(domain).toRelativeUrl(domain)
        path = path.substringBefore('#').substringBefore('?')
        if (!path.contains("/forums/")) return null
        path = path.replace(FORUM_PAGE_REGEX, "/")
        if (!FORUM_ID_REGEX.containsMatchIn(path)) return null
        if (!path.endsWith('/')) path += "/"
        return path
    }

    private fun normalizeThreadPath(rawHref: String): String? {
        if (rawHref.isBlank()) return null
        var path = rawHref.toAbsoluteUrl(domain).toRelativeUrl(domain)
        path = path.substringBefore('#').substringBefore('?')
        if (!path.contains("/threads/")) return null
        path = path.replace(THREAD_SUFFIX_REGEX, "/")
        if (!path.endsWith('/')) path += "/"
        return path
    }

    private fun extractMedia(doc: Document): List<MediaItem> {
        val result = LinkedHashSet<MediaItem>()
        val containers = doc.select("article.message-body, .message-userContent .bbWrapper, .bbWrapper")
        val scope = if (containers.isEmpty()) listOf(doc.body()) else containers

        scope.forEach { block ->
            result += extractMediaFromBlock(block)
        }

        // Note: Global script scan removed as it's too noisy for covers.
        // Script scan is still performed within individual post blocks in extractMediaFromBlock.

        return result.toList()
    }

    private fun selectMediaByMode(all: List<MediaItem>): List<MediaItem> {
        if (!videoOnly) {
            // Content mode: strictly only images to prevent errors in reader
            return all.filter { it.kind == MediaKind.IMAGE }
        }
        val hostDirect = all.filter { it.kind == MediaKind.VIDEO && isExternalVideoHostUrl(it.url) }
        if (hostDirect.isNotEmpty()) return hostDirect
        val videoLike = all.filter { it.kind == MediaKind.VIDEO || it.kind == MediaKind.EMBED }
        return if (videoLike.isNotEmpty()) videoLike else all
    }

    private suspend fun resolveExternalVideoMedia(all: List<MediaItem>): List<MediaItem> {
        if (all.isEmpty()) return all
        val out = LinkedHashSet<MediaItem>(all.size)
        all.forEach { item ->
            if (!isExternalVideoHostUrl(item.url)) {
                out += item
                return@forEach
            }
            val resolved = resolveExternalVideoLinks(item.url)
            if (resolved.isEmpty()) {
                out += item
            } else {
                resolved.forEach { videoUrl ->
                    out += MediaItem(
                        url = videoUrl,
                        preview = item.preview,
                        kind = MediaKind.VIDEO,
                    )
                }
            }
        }
        return out.toList()
    }

    private suspend fun resolveExternalVideoLinks(url: String): List<String> {
        return runCatching {
            // No premature return for .mp4, because Bunkr uses .mp4 URLs that redirect to landing pages
            val host = domainOf(url)
            val visited = LinkedHashSet<String>()
            val pending = ArrayDeque<String>()
            fun enqueue(value: String) {
                if (value.isBlank()) return
                if (visited.add(value)) pending.addLast(value)
            }
            fun enqueueVariants(base: String) {
                enqueue(base)
                if (base.contains("/embed/")) {
                    enqueue(base.replace("/embed/", "/v/"))
                    enqueue(base.replace("/embed/", "/d/"))
                }
                if (base.contains("/v/")) {
                    enqueue(base.replace("/v/", "/embed/"))
                    enqueue(base.replace("/v/", "/d/"))
                }
                if (base.contains("/f/")) {
                    enqueue(base.replace("/f/", "/v/"))
                    enqueue(base.replace("/f/", "/d/"))
                    enqueue(base.replace("/f/", "/embed/"))
                }
            }
            enqueueVariants(url)
            val links = LinkedHashSet<String>()
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            val currentHost = domainOf(candidate)
            logDebug("Visiting candidate: $candidate (host: $currentHost)")
            
            if (isTrueVideoUrl(candidate)) {
                logDebug("Candidate is already a direct link: $candidate")
                links += candidate
                // Even if it's a direct link, sometimes it's also a landing page (Bunkr), so we continue to parse it
            }
            
            // Try signing for Turbo (Bunkr doesn't seem to have a public /api/sign like Turbo)
            if (candidate.contains("turbo.cr")) {
                val videoId = Regex("/(?:v|embed|f|d|i)/([^/?#.]+)").find(candidate)?.groupValues?.getOrNull(1)
                if (videoId != null && videoId.length > 5) {
                    runCatching {
                        val videoHost = domainOf(candidate)
                        val signUrl = "https://$videoHost/api/sign?v=$videoId"
                        logDebug("Attempting API sign: $signUrl")
                        val json = webClient.httpGet(signUrl, getRequestHeaders().newBuilder().set("Referer", candidate).build()).body?.string().orEmpty()
                        if (json.contains("\"success\":true") && (json.contains("\"url\":") || json.contains("\"link\":"))) {
                            val direct = json.substringAfter("\"url\":\"").substringBefore("\"")
                                .ifBlank { json.substringAfter("\"link\":\"").substringBefore("\"") }
                            if (direct.isNotEmpty() && isTrueVideoUrl(direct)) {
                                logDebug("Found direct link via API: $direct")
                                links += direct
                            }
                        }
                    }.onFailure { logDebug("API sign failed for $candidate: ${it.message}") }
                }
            }

            val resp = webClient.httpGet(candidate, getRequestHeaders())
            if (!resp.isSuccessful) {
                logDebug("Candidate $candidate failed with status: ${resp.code}")
                continue
            }
            val doc = resp.parseHtml()
            logDebug("Candidate $candidate returned doc with title: ${doc.title()}")
            
            // Album handling: capture all items in an album
            doc.select("a[href*='/f/'], a[href*='/i/'], a[href*='/v/']").forEach { a ->
                val href = a.attrOrNull("href")?.toAbsoluteUrlOrNull(host) ?: return@forEach
                if (href.contains("/a/") || candidate.contains("/a/")) {
                     enqueueVariants(href)
                }
            }
            doc.select("video source[src], video[src], video").forEach { el ->
                val src = el.attrOrNull("src")?.toAbsoluteUrlOrNull(currentHost) ?: return@forEach
                logDebug("Found video tag src: $src")
                if (isTrueVideoUrl(src)) links += src
            }

            doc.select("meta[property='og:video'], meta[property='og:video:url'], meta[property='og:video:secure_url']").forEach { m ->
                val src = m.attrOrNull("content")?.toAbsoluteUrlOrNull(currentHost) ?: return@forEach
                logDebug("Found og:video: $src")
                if (isTrueVideoUrl(src)) links += src
            }

            doc.select("a[href], a[data-url]").forEach { a ->
                val href = a.attrOrNull("href", "data-url")?.toAbsoluteUrlOrNull(currentHost) ?: return@forEach
                if (isTrueVideoUrl(href)) {
                    logDebug("Found likely direct link in anchor: $href")
                    links += href
                }
            }
            
            // Scan scripts for more links (absolute and relative)
            val html = doc.html()
            URL_IN_SCRIPT_REGEX.findAll(html).forEach { m ->
                val scriptUrl = m.value.replace("\\/", "/").toAbsoluteUrlOrNull(currentHost) ?: return@forEach
                if (isTrueVideoUrl(scriptUrl)) {
                    logDebug("Found likely direct link in script (abs): $scriptUrl")
                    links += scriptUrl
                }
            }
            
            // New: Scan for relative video paths in scripts (e.g. VIDEO_DIRECT = "/xxx.mp4")
            RELATIVE_VIDEO_IN_SCRIPT_REGEX.findAll(html).forEach { m ->
                val raw = m.groupValues[1].replace("\\/", "/")
                val scriptUrl = raw.toAbsoluteUrlOrNull(currentHost) ?: "https://cdn.$currentHost/$raw".toAbsoluteUrlOrNull(currentHost) ?: return@forEach
                if (isTrueVideoUrl(scriptUrl)) {
                    logDebug("Found likely direct link in script (rel): $scriptUrl")
                    links += scriptUrl
                }
            }
            doc.select("a[href*='/v/'], a[href*='/f/'], a[href*='/d/'], a[href*='/embed/'], a[href*='/a/'], a[href*='/i/']").forEach { a ->
                val href = a.attrOrNull("href")?.toAbsoluteUrlOrNull(currentHost) ?: return@forEach
                if (isExternalVideoHostUrl(href)) {
                    enqueueVariants(href)
                }
            }
            
            // Bunkr specific: Find file id for direct download
            doc.select("[data-file-id]").forEach { el ->
                val fileId = el.attr("data-file-id")
                if (fileId.isNotBlank() && fileId.all { it.isDigit() }) {
                    val direct = "https://get.bunkrr.su/file/$fileId"
                    links += direct
                    logDebug("Found Bunkr direct download link: $direct")
                }
            }
        }
        links.toList()
        }.getOrElse { emptyList() }
    }

    private fun pageIndex(path: String): Int {
        return PAGE_NUMBER_REGEX.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    private fun isIgnoredImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/avatar/") ||
            lower.contains("/avatars/") ||
            lower.contains("emoji") ||
            lower.contains("smilies") ||
            lower.endsWith(".svg") ||
            lower.contains("logo") ||
            lower.contains("banner") ||
            lower.contains("no_image") ||
            lower.contains("ads.simpcity") ||
            lower.contains("pixel.gif") ||
            lower.contains("clear.gif") ||
            lower.contains("icon") ||
            lower.contains("button") ||
            lower.contains("social") ||
            lower.contains("onlyfans") ||
            lower.contains("twitter") ||
            lower.contains("instagram") ||
            lower.contains("facebook") ||
            lower.contains("bunkr.cr/assets") ||
            lower.contains("dash.bunkr") ||
            lower.contains("selti-delivery.ru") && (lower.contains("icons") || lower.contains("staff"))
    }

    private fun isIgnoredMediaUrl(raw: String): Boolean {
        val v = raw.trim().lowercase(Locale.ROOT)
        return v.isBlank() || v.startsWith("data:") || v.startsWith("javascript:") || v.startsWith("about:")
    }

    private fun isExternalVideoHostUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return VIDEO_HOST_REGEX.containsMatchIn(lower) || lower.contains("gigachad-cdn")
    }

    private fun isTrueVideoUrl(url: String): Boolean {
        if (!VIDEO_URL_REGEX.containsMatchIn(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        // Known landing page patterns that should be followed, not played directly
        // But some CDNs might actually use these patterns. 
        // We only reject them if they are on the "main" domain (bunkr.cr, turbo.cr, etc.)
        if (isLikelyLandingPageUrl(lower)) {
            logDebug("isTrueVideoUrl: Rejected likely landing page URL: $url")
            return false
        }
        return true
    }

    private fun isLikelyLandingPageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        // If it's on a known CDN, it's NOT a landing page
        if (lower.contains("cdn") || lower.contains("gigachad") || lower.contains("selti") || lower.contains("burger")) return false
        
        // If it's the main domain (no subdomains or just 'www'), it's likely a landing page if it has these patterns
        val isMainDomain = lower.contains("://bunkr.") || lower.contains("://turbo.cr") || lower.contains("://cyberdrop.") || lower.contains("://saint.to")
        
        if (isMainDomain) {
            if (lower.contains("/v/") || lower.contains("/f/") || lower.contains("/i/") || lower.contains("/d/") || lower.contains("/embed/") || lower.contains("/a/")) return true
            // Bunkr/Cyberdrop even uses just filename.mp4 as landing page URL
            if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m4v") || lower.endsWith(".mov")) {
                // However, check if it has a direct-link pattern (usually longer and randomized)
                if (lower.contains("_source")) return false // some sources are actually direct but on main domain
                return true
            }
        }

        return false
    }

    private fun domainOf(url: String): String {
        return DOMAIN_FROM_URL_REGEX.find(url)?.groupValues?.getOrNull(1) ?: domain
    }

    private fun extractUrlFromStyle(style: String): String? {
        if (style.isBlank()) return null
        val raw = STYLE_URL_REGEX.find(style)?.groupValues?.getOrNull(1)?.trim('"', '\'') ?: return null
        return raw.toAbsoluteUrlOrNull(domain)
    }

    private fun normalizeLabelForMatch(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(NON_ALNUM_REGEX, "")
    }

    private fun logDocShape(stage: String, doc: Document) {
        val template = doc.selectFirst("html")?.attr("data-template").orEmpty()
        val title = doc.selectFirst("title")?.text().orEmpty()
        val threadCards = doc.select(".structItem--thread, li.structItem").size
        val whatsNewRows = doc.select("li.block-row .contentRow, .contentRow").size
        val nodeRows = doc.select(".node-extra-title[href*='/threads/']").size
        logDebug("$stage: template=$template title=$title threadCards=$threadCards whatsNewRows=$whatsNewRows nodeRows=$nodeRows")
    }

    private fun logPaginationState(stage: String, doc: Document, requestedPage: Int) {
        val currentPages = doc.select(".pageNav-page--current a, .pageNav-page--current, .pageNavSimple-el--current")
            .mapNotNull { PAGE_NUMERIC_TEXT_REGEX.find(it.text())?.value?.toIntOrNull() }
            .distinct()
        val maxPage = doc.select(".pageNav-page a, .pageNav-page")
            .mapNotNull { PAGE_NUMERIC_TEXT_REGEX.find(it.text())?.value?.toIntOrNull() }
            .maxOrNull()
        val hasRelNext = doc.selectFirst("link[rel='next']") != null
        val hasPageNext = doc.selectFirst("a.pageNav-jump--next, .pageNavSimple-el--next") != null
        val hasRequestedRef = doc.select(
            "a[href*='/page-$requestedPage'], " +
                "link[rel='canonical'][href*='/page-$requestedPage'], " +
                ".menu-row[data-page-url*='/page-$requestedPage']"
        ).isNotEmpty()
        logDebug(
            "$stage: requestedPage=$requestedPage currentPages=${currentPages.joinToString(",")} " +
                "maxPage=${maxPage ?: 1} hasRelNext=$hasRelNext hasPageNext=$hasPageNext hasRequestedRef=$hasRequestedRef"
        )
    }

    private fun isRequestedPageLikelyValid(doc: Document, page: Int, requestUrl: String): Boolean {
        if (page <= 1) return true

        val pageSegment = "/page-$page"
        if (requestUrl.contains(pageSegment, ignoreCase = true)) {
            val canonical = doc.selectFirst("link[rel='canonical']")?.attr("href").orEmpty()
            if (canonical.isNotBlank() && !canonical.contains(pageSegment, ignoreCase = true)) {
                return false
            }
        }

        val currentNumbers = doc.select(".pageNav-page--current a, .pageNav-page--current, .pageNavSimple-el--current")
            .mapNotNull { PAGE_NUMERIC_TEXT_REGEX.find(it.text())?.value?.toIntOrNull() }
        if (currentNumbers.isNotEmpty() && page !in currentNumbers) {
            return false
        }

        val hasPageReference = doc.select(
            "a[href*='/page-$page'], " +
                "link[rel='canonical'][href*='/page-$page'], " +
                "link[rel='next'][href*='/page-$page'], " +
                "link[rel='prev'][href*='/page-$page'], " +
                ".menu-row[data-page-url*='/page-$page']"
        ).isNotEmpty()
        if (hasPageReference) return true

        val maxKnownPage = doc.select(".pageNav-page a, .pageNav-page")
            .mapNotNull { PAGE_NUMERIC_TEXT_REGEX.find(it.text())?.value?.toIntOrNull() }
            .maxOrNull()
        if (maxKnownPage != null && page > maxKnownPage) {
            return false
        }

        return false
    }

    private fun isLoginPage(doc: Document): Boolean {
        val html = doc.selectFirst("html")
        val template = html?.attr("data-template")?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (template == "login") return true
        val title = doc.selectFirst("title")?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (title.startsWith("log in")) return true
        return doc.selectFirst("form[action*='/login/login']") != null
    }

    private fun logDebug(message: String) {
        runCatching { println("[Simpcity] $message") }
    }

    private data class MediaItem(
        val url: String,
        val preview: String?,
        val kind: MediaKind,
    )

    private enum class MediaKind { IMAGE, VIDEO, EMBED }

    private companion object {
        private const val DEFAULT_FORUM_PATH = "/forums/onlyfans.8/"
        private const val FORUM_KEY_PREFIX = "forum:"
        private const val PREFIX_KEY_PREFIX = "prefix:"
        private const val LABEL_KEY_PREFIX = "label:"
        private const val ALL_PAGES_SUFFIX = "#all-pages"
        private const val REPLY_CHAPTER_MARKER = "#reply:"
        private const val VIDEO_TOKEN_PREFIX = "video_url:"

        private val FORUM_ID_REGEX = Regex("/forums/[^/]+\\.\\d+/", RegexOption.IGNORE_CASE)
        private val FORUM_PAGE_REGEX = Regex("/page-\\d+/?$", RegexOption.IGNORE_CASE)
        private val THREAD_SUFFIX_REGEX = Regex("/(unread|latest)/?$", RegexOption.IGNORE_CASE)
        private val PAGE_NUMBER_REGEX = Regex("/page-(\\d+)/", RegexOption.IGNORE_CASE)
        private val PREFIX_ID_REGEX = Regex("prefix_id(?:\\[[^\\]]*]|%5B\\d+%5D)?=(\\d+)", RegexOption.IGNORE_CASE)
        private val IMAGE_URL_REGEX = Regex("\\.(jpg|jpeg|png|webp|gif|avif)(\\?|$)", RegexOption.IGNORE_CASE)
        private val VIDEO_URL_REGEX = Regex("\\.(mp4|m3u8|webm|mov)(\\?|$)", RegexOption.IGNORE_CASE)
        private val URL_IN_SCRIPT_REGEX =
            Regex("""https?:\\?/\\?/[^"'<>\\s]+\.(?:jpg|jpeg|png|webp|gif|avif|mp4|m3u8|webm|mov)[^"'<>\\s]*""", RegexOption.IGNORE_CASE)
        private val RELATIVE_VIDEO_IN_SCRIPT_REGEX =
            Regex("""["']([^"'\'\s<>]+?\.(?:mp4|m3u8|webm|mov))["']""", RegexOption.IGNORE_CASE)
        private val STYLE_URL_REGEX = Regex("""background-image\s*:\s*url\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        private val VIDEO_HOST_REGEX =
            Regex("""https?://(?:www\.)?[^/\s]*(?:bunkr|turbo|cyberdrop|saint)[^/\s]*/""", RegexOption.IGNORE_CASE)
        private val DOMAIN_FROM_URL_REGEX = Regex("""^https?://([^/]+)""", RegexOption.IGNORE_CASE)
        private val SEARCH_RESULT_ID_REGEX = Regex("""/search/(\d+)$""", RegexOption.IGNORE_CASE)
        private val NON_ALNUM_REGEX = Regex("""[^a-z0-9]+""", RegexOption.IGNORE_CASE)
        private val PAGE_NUMERIC_TEXT_REGEX = Regex("""\d+""")
        private val POST_ID_REGEX = Regex("""(?:js-post-|post-|posts/)(\d+)""", RegexOption.IGNORE_CASE)
        private const val MAX_VIDEO_AUTO_SCAN_PAGES = 10
        private const val MAX_ALL_PAGES_SCAN = 20
        private const val MAX_REPLY_CHAPTER_SCAN_PAGES = 20
        private const val MAX_REPLY_CHAPTER_COUNT = 500

        private var cachedForumTags: Set<ContentTag>? = null
        private var cachedHomeLabelTags: Set<ContentTag>? = null
        private val cachedPrefixTags = HashMap<String, Set<ContentTag>>()
        private var lastCacheTime = 0L
        private const val CACHE_TTL = 3600_000L // 1 hour
    }
}
