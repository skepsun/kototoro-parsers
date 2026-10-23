package org.skepsun.kototoro.parsers.site.en

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
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
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("PIMPBUNNY", "PimpBunny", "en", type = ContentType.HENTAI_VIDEO)
internal class PimpBunny(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.PIMPBUNNY, pageSize = 32) {

    override val configKeyDomain = ConfigKey.Domain("pimpbunny.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val cats = listOf(
            "Exclusive" to "exclusive", "BBC" to "bbc", "Anal" to "anal",
            "Deep Throat" to "deep-throat", "BDSM" to "bdsm", "Bizarre Porn" to "bizarre-porn",
            "Double Penetration" to "double-penetration", "Feet" to "feet", "Fetish" to "fetish",
            "Gang Bang" to "gang-bang", "Lesbian" to "lesbian", "Outdoor" to "outdoor",
            "Blowjob" to "blowjob", "Creampie" to "creampie", "Threesome" to "threesome",
            "Solo" to "solo", "MILF" to "milf", "Teen" to "teen",
            "Cosplay" to "cosplay", "Massage" to "massage", "POV" to "pov",
            "Big Boobs" to "big-boobs", "Interracial" to "interracial", "Public" to "public",
        ).map { ContentTag(it.first, it.second, source) }.toSet()
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = cats,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        logDebug("list url=$url page=$page order=$order query=${filter.query}")
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        logDebug("details url=${manga.publicUrl}")
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        return parseDetails(response.parseHtml(), manga)
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("div.pages-view-video-video-title__9lYVyi")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("div.pages-view-video-description__CuSQws")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst("img.pages-view-video-thumb, img[data-original]")?.let {
                (it.attr("data-original").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }

        val videoId = doc.selectFirst("script:containsData(pageContext)")?.data()
            ?.let { Regex("videoId:\\s*'([^']+)'").find(it)?.groupValues?.get(1) }
            ?: manga.url.substringAfterLast("-").ifBlank { manga.url }

        val authors = mutableSetOf<String>()
        doc.select("div.pages-view-video-model-title__jPOPZM a").forEach { a ->
            val name = a.text().trim().ifEmpty { a.attr("href").substringAfterLast("/").replace("-", " ").trim() }
            if (name.isNotBlank()) authors.add(name)
        }

        val categories = mutableSetOf<ContentTag>()
        val tagSection = doc.select("ul.pages-view-video-categories__OWVJKQ").firstOrNull {
            it.selectFirst("span.ui-text-bold__NmZm1L")?.text() == "Categories"
        }
        tagSection?.select("a[href*=/categories/]")?.forEach { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/categories/").substringBefore("/").trim()
            if (text.isNotEmpty() && key.isNotEmpty()) {
                categories.add(ContentTag(text, key, source))
            }
        }

        val tags = mutableSetOf<ContentTag>()
        val tagSectionTags = doc.select("ul.pages-view-video-categories__OWVJKQ").firstOrNull {
            it.selectFirst("span.ui-text-bold__NmZm1L")?.text() == "Tags"
        }
        tagSectionTags?.select("a[href*=/tags/]")?.forEach { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/tags/").substringBefore("/").trim()
            if (text.isNotEmpty() && key.isNotEmpty()) {
                tags.add(ContentTag(text, key, source))
            }
        }

        if (categories.isEmpty()) {
            doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.forEach { kw ->
                val trimmed = kw.trim()
                if (trimmed.isNotBlank()) {
                    categories.add(ContentTag(trimmed, trimmed.lowercase().replace(" ", "-"), source))
                }
            }
        }

        val allTags = (categories + tags).toSet()

        val chapter = ContentChapter(
            id = generateUid("${manga.url}|$videoId"),
            // 只保存详情页地址：流地址带 v-acctoken（约 2 小时过期），
            // 由 getPages 播放前现取，避免缓存的章节地址过期后 403。
            url = manga.url,
            title = "Video",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            tags = if (allTags.isNotEmpty()) allTags else manga.tags,
            contentRating = ContentRating.ADULT,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        // 兼容已有缓存章节：若地址本身就是媒体直链，直接播放（get_file 需要 Referer）。
        val storedStream = chapter.url.takeIf { isStreamUrl(it) }
        if (storedStream != null) {
            return listOf(videoPage(chapter, storedStream.toAbsoluteUrl(domain)))
        }

        // v-acctoken 约 2 小时过期，因此每次播放都重新从详情页取流。
        val detailUrl = chapter.url.toAbsoluteUrl(domain)
        logDebug("resolve stream url=$detailUrl")
        val response = webClient.httpGet(detailUrl, getRequestHeaders())
        val streamUrl = resolveStreamUrl(response.parseHtml())
            ?: throw ParseException("PimpBunny: video stream not found on page", detailUrl)
        return listOf(videoPage(chapter, streamUrl))
    }

    /**
     * 取可播放的流地址，按优先级：
     * 1. 详情页 KVS 播放器配置（video_url / video_alt_url*，多档画质）
     * 2. ld+json 的 contentUrl（部分视频只有它，最新页面上不总是存在）
     * 3. `<video>` / `<source>` 标签
     */
    internal fun resolveStreamUrl(doc: Document): String? {
        val html = doc.outerHtml()
        return resolvePlayerStream(html) ?: resolveLdJsonStream(doc) ?: resolveVideoTagStream(doc)
    }

    /**
     * 解析 KVS 播放器配置里的多档地址（`video_url: '...'` 一类），取最高可用画质。
     * - `_redirect: 1` 的档位是付费跳转（如 4K 指向 `?upgrade=true`），必须跳过；
     * - 以 `function/0/` 开头的地址是 license 混淆地址，用同页 license_code 解混淆。
     */
    private fun resolvePlayerStream(html: String): String? {
        val licenseCode = LICENSE_CODE_REGEX.find(html)?.groupValues?.get(1)
        val slots = PLAYER_URL_REGEX.findAll(html).mapNotNull { match ->
            val key = match.groupValues[1]
            if (isRedirectSlot(html, key)) {
                return@mapNotNull null
            }
            val raw = Parser.unescapeEntities(match.groupValues[2].replace("\\/", "/"), false)
                .replace("&amp;", "&")
                .trim()
            val url = when {
                raw.startsWith("function/0/") -> licenseCode?.let { kvsDecodeUrl(raw, it) } ?: return@mapNotNull null
                else -> raw
            }
            if (!isStreamUrl(url)) {
                return@mapNotNull null
            }
            QualitySlot(qualityOf(html, key, url), url)
        }.toList()

        if (slots.isEmpty()) {
            return null
        }
        val preferred = slots.filter { it.quality <= MAX_PREFERRED_QUALITY }
        return (preferred.maxByOrNull { it.quality } ?: slots.maxByOrNull { it.quality })?.url
    }

    private fun isRedirectSlot(html: String, key: String): Boolean =
        REDIRECT_REGEX_TEMPLATE.replace("{key}", Regex.escape(key)).toRegex()
            .containsMatchIn(html)

    private fun qualityOf(html: String, key: String, url: String): Int {
        val label = TEXT_REGEX_TEMPLATE.replace("{key}", Regex.escape(key)).toRegex()
            .find(html)?.groupValues?.get(1)
        return label?.let { QUALITY_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: QUALITY_REGEX.find(url.substringBefore('?').substringAfterLast('/'))
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
    }

    private fun resolveLdJsonStream(doc: Document): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = runCatching { JSONObject(script.data()).optString("contentUrl") }.getOrNull()
            val url = raw?.replace("\\/", "/")?.trim().orEmpty()
            if (isStreamUrl(url)) {
                return Parser.unescapeEntities(url, false)
            }
        }
        return null
    }

    private fun resolveVideoTagStream(doc: Document): String? {
        for (el in doc.select("video source[src], video[src], video source[data-src], video[data-src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (src.isBlank()) continue
            val absolute = src.toAbsoluteUrl(domain)
            if (isStreamUrl(absolute)) {
                return absolute
            }
        }
        return null
    }

    private fun isStreamUrl(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) {
            return false
        }
        val path = url.substringBefore('?').lowercase().trimEnd('/')
        return path.contains("/get_file/") || MEDIA_EXTENSIONS.any(path::endsWith)
    }

    internal fun videoPage(chapter: ContentChapter, streamUrl: String): ContentPage = ContentPage(
        id = generateUid("page:${chapter.id}"),
        url = streamUrl,
        preview = null,
        // 站点的 get_file 端点校验来源，缺少 Referer 会直接 403。
        headers = mapOf(
            "Referer" to "https://$domain/",
            "User-Agent" to context.getDefaultUserAgent(),
        ),
        source = source,
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val sortBy = when (order) {
            SortOrder.POPULARITY -> "video_viewed"
            SortOrder.RATING -> "rating"
            else -> "post_date"
        }
        val tag = filter.tags.firstOrNull()?.key
        return when {
            !filter.query.isNullOrBlank() -> {
                val q = filter.query.urlEncoded()
                "https://$domain/search/?q=$q&page=$page"
            }
            tag != null -> "https://$domain/categories/$tag/$page/?videos_per_page=$pageSize&sort_by=$sortBy"
            else -> "https://$domain/videos/$page/?videos_per_page=$pageSize&sort_by=$sortBy"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()

        val cards = doc.select("a.ui-card-link__KxRw6l[href*=/videos/]").ifEmpty {
            doc.select("a[href*=/videos/][class*=card], a[href*=/videos/][class*=video]")
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            if (!href.contains("/videos/")) continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue

            val title = link.selectFirst("div.ui-card-title__igirYJ")?.text()?.trim()
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: "Untitled"

            val thumb = link.selectFirst("img[data-original], img[src]")?.let {
                val raw = it.attr("data-original").ifBlank { it.attr("src") }
                if (raw.startsWith("data:")) null
                else raw.toAbsoluteUrlOrNull(domain)
            }

            items.add(
                Content(
                    id = generateUid(absoluteUrl),
                    url = absoluteUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                    publicUrl = absoluteUrl,
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = thumb,
                    largeCoverUrl = thumb,
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

        logDebug("parsed ${items.size} items")
        return items
    }

    private fun logDebug(message: String) {
        runCatching { println("[PimpBunny] $message") }
    }

    private data class QualitySlot(val quality: Int, val url: String)

    private companion object {
        /** KVS 播放器配置里的画质档位，如 `video_url: '...'`、`video_alt_url3: '...'`。 */
        val PLAYER_URL_REGEX = Regex("""(video_alt_url\d*|video_url)\s*:\s*'([^']*)'""")

        val LICENSE_CODE_REGEX = Regex("""license_code\s*:\s*'([^']*)'""")

        /** 档位标记为跳转时为付费/升级指向（`{key}_redirect: 1`），不可播放。 */
        const val REDIRECT_REGEX_TEMPLATE = """{key}_redirect\s*:\s*'?(?:1|true)'?"""

        /** 档位画质文本（`{key}_text: '1080p'`）。 */
        const val TEXT_REGEX_TEMPLATE = """{key}_text\s*:\s*'([^']*)'"""

        val QUALITY_REGEX = Regex("""(\d{3,4})""")

        val MEDIA_EXTENSIONS = listOf(".mp4", ".m3u8", ".webm")

        /** 优先 1080p 及以下，避免移动端拉取 1440p/4K 大文件。 */
        const val MAX_PREFERRED_QUALITY = 1080
    }
}
