package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
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
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import kotlin.math.abs

/**
 * FPO.XXX - 成人视频网站（KVS 播放器）
 *
 * 网站: https://www.fpo.xxx/
 * 最后验证: 2025-08（经本机 7890 代理实机验证，KVS 解混淆算法与 yt-dlp 一致）
 *
 * 页面结构:
 * - 视频详情: /video/{slug}/
 * - 列表: / (最新), /popular-2/ (热门), /top-2/ (高评分)
 * - 搜索: /search/{query}/
 * - 筛选: 分类来自 /categories/（a.item 条目），请求 /categories/{slug}/…
 * - 分页: /new-1/{n}/ (最新), /popular-2/{n}/, /top-2/{n}/, /search/{q}/{n}/, /categories/{slug}/{n}/
 * - 视频 URL: 详情页 flashvars 中的 video_url : '...' 与 license_code : '...'
 *   - video_url 以 'function/0/' 开头时为混淆地址，需用 license_code 解混淆（算法与 yt-dlp 一致）
 *   - 解混淆后为 /get_file/.../ 的流地址，播放时需带 Referer/Origin
 *
 * 注意: 详情页 video_url 通常已是带 v-acctoken 的直接地址（不经混淆）；仅当以
 * 'function/0/' 开头时才需要 license_code 解混淆（本解析器两者皆支持）。
 */
@ContentSourceParser(
    "FPOXXX", "FPO.XXX", "en", type = ContentType.HENTAI_VIDEO,
)
internal class FpoXxx(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.FPOXXX, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("www.fpo.xxx")

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = runCatching {
            val response = webClient.httpGet("https://$domain/categories/", getRequestHeaders())
            parseCategories(response.parseHtml())
        }.getOrElse { emptySet() }
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = tags,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(content: Content): Content {
        val response = webClient.httpGet(content.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()
        return parseDetails(doc, content)
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val streamUrl = chapter.url.takeIf { it.startsWith("http") }
        if (streamUrl != null) {
            return listOf(videoPage(chapter, streamUrl))
        }
        val detailUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(detailUrl, getRequestHeaders())
        val doc = response.parseHtml()
        val resolved = resolveStreamUrl(doc) ?: return emptyList()
        return listOf(videoPage(chapter, resolved))
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "https://$domain/")
        .build()

    /**
     * 解析视频列表页。仅接受指向 /video/{slug}/ 的条目，过滤分类/模特等导航链接。
     */
    internal fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        for (link in doc.select("a[href*='/video/']")) {
            val href = link.attr("href").ifBlank { continue }
            if (!href.contains("/video/")) continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue

            val img = link.selectFirst("img")
            val coverUrl = img?.let {
                it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
            }?.toAbsoluteUrlOrNull(domain)
            val title = listOfNotNull(
                link.attr("title").takeIf { it.isNotBlank() },
                img?.attr("alt")?.takeIf { it.isNotBlank() },
            ).firstOrNull() ?: "Untitled"
            val duration = link.selectFirst("span.duration")?.text()?.trim()

            items.add(
                Content(
                    id = generateUid(absoluteUrl),
                    title = title,
                    altTitles = emptySet(),
                    url = relativeUrl(absoluteUrl),
                    publicUrl = absoluteUrl,
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.ADULT,
                    coverUrl = coverUrl,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    largeCoverUrl = coverUrl,
                    description = duration?.let { "Duration: $it" },
                    chapters = null,
                    source = source,
                ),
            )
            if (items.size >= pageSize) break
        }
        return items
    }

    /**
     * 解析视频详情页，生成单个 "Watch" 章节。
     */
    internal fun parseDetails(doc: Document, content: Content): Content {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: content.title
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst("img[data-original], img[src]")?.let {
                it.attr("data-original").ifBlank { it.attr("src") }
            }?.toAbsoluteUrlOrNull(domain)

        val chapter = ContentChapter(
            id = generateUid("${content.url}|video"),
            url = content.url,
            title = "Watch",
            number = 1f,
            volume = 0,
            uploadDate = 0L,
            branch = null,
            scanlator = null,
            source = source,
        )

        return content.copy(
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            coverUrl = cover ?: content.coverUrl,
            largeCoverUrl = cover ?: content.largeCoverUrl,
            contentRating = ContentRating.ADULT,
            chapters = listOf(chapter),
        )
    }

    /**
     * 解析分类页（/categories/）。分类条目标记为 a.item + /categories/{slug}/ 链接。
     */
    internal fun parseCategories(doc: Document): Set<ContentTag> {
        val tags = linkedSetOf<ContentTag>()
        for (link in doc.select("a.item[href*='/categories/'], a[href*='/categories/']")) {
            val href = link.attr("href")
            val key = href
                .removePrefix("https://$domain")
                .removePrefix("http://$domain")
                .trim('/')
                .ifBlank { continue }
            if (!key.startsWith("categories/")) continue
            val title = link.attr("title").ifBlank { link.text().trim() }
                .ifBlank { key.substringAfterLast('/') }
                .trim()
            if (title.isBlank()) continue
            tags.add(ContentTag(title, key, source))
        }
        return tags
    }

    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val base = "https://$domain"
        if (!filter.query.isNullOrBlank()) {
            val search = "$base/search/${filter.query.urlEncoded()}/"
            return if (page > 1) "$search$page/" else search
        }
        val tag = filter.tags.firstOrNull()?.key?.trim('/')
        if (tag != null) {
            val category = "$base/$tag/"
            return if (page > 1) "$category$page/" else category
        }
        return when (order) {
            SortOrder.POPULARITY ->
                if (page > 1) "$base/popular-2/$page/" else "$base/popular-2/"
            SortOrder.RATING ->
                if (page > 1) "$base/top-2/$page/" else "$base/top-2/"
            else ->
                if (page > 1) "$base/new-1/$page/" else "$base/"
        }
    }

    /**
     * 从详情页 flashvars 中解析流地址。
     * 1. video_url + license_code（KVS 混淆地址）→ 解混淆
     * 2. 兜底: 页面内直接的 /get_file/ mp4 链接
     */
    internal fun resolveStreamUrl(doc: Document): String? {
        val html = doc.outerHtml()
        val videoUrlMatch = VIDEO_URL_REGEX.find(html)
        if (videoUrlMatch != null) {
            val raw = videoUrlMatch.groupValues[1].replace("\\/", "/")
            val videoUrl = Parser.unescapeEntities(raw, false)
            val license = LICENSE_CODE_REGEX.find(html)?.groupValues?.get(1)
            return if (license != null) kvsDecodeUrl(videoUrl, license) else videoUrl
        }
        return GET_FILE_REGEX.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
    }

    private fun videoPage(chapter: ContentChapter, streamUrl: String): ContentPage = ContentPage(
        id = generateUid("page:${chapter.id}"),
        url = streamUrl,
        preview = null,
        headers = mapOf(
            "Referer" to "https://$domain/",
            "Origin" to "https://$domain",
        ),
        source = source,
    )

    private fun relativeUrl(absoluteUrl: String): String =
        absoluteUrl.removePrefix("https://$domain").removePrefix("http://$domain")

    private companion object {
        val VIDEO_URL_REGEX = Regex("""video_url\s*:\s*'([^']+)'""", RegexOption.IGNORE_CASE)
        val LICENSE_CODE_REGEX = Regex("""license_code\s*:\s*'([^']+)'""", RegexOption.IGNORE_CASE)
        val GET_FILE_REGEX = Regex(
            """https://www\.fpo\.xxx/get_file/[^"'\s]+\.mp4[^"'\s]*""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * KVS（Kernel Video Sharing）播放器混淆 URL 解码。
 * 算法与 yt-dlp generic.py 中的 _kvs_get_real_url/_kvs_get_license_token 一致。
 *
 * @param videoUrl 详情页 flashvars 中的 video_url，如 'function/0/https://...'
 * @param licenseCode 详情页 flashvars 中的 license_code，如 '$62417872059274'
 */
internal fun kvsDecodeUrl(videoUrl: String, licenseCode: String): String {
    if (!videoUrl.startsWith("function/0/")) {
        return videoUrl
    }
    val token = kvsGetLicenseToken(licenseCode)
    val parsed = videoUrl.removePrefix("function/0/")

    val schemeEnd = parsed.indexOf("://")
    val pathStart = if (schemeEnd >= 0) parsed.indexOf('/', schemeEnd + 3) else -1
    val origin = if (pathStart >= 0) parsed.substring(0, pathStart) else parsed
    val rawPath = if (pathStart >= 0) parsed.substring(pathStart) else ""

    val queryIndex = rawPath.indexOf('?')
    val query = if (queryIndex >= 0) rawPath.substring(queryIndex) else ""
    val path = if (queryIndex >= 0) rawPath.substring(0, queryIndex) else rawPath

    val parts = path.split("/").toMutableList()
    if (parts.size > 3 && parts[3].length >= HASH_LENGTH) {
        val hashSegment = parts[3]
        val head = hashSegment.take(HASH_LENGTH)
        val indices = IntArray(HASH_LENGTH) { it }
        var accum = 0
        for (src in HASH_LENGTH - 1 downTo 0) {
            accum += token[src]
            val dest = (src + accum) % HASH_LENGTH
            val tmp = indices[src]
            indices[src] = indices[dest]
            indices[dest] = tmp
        }
        val decodedHash = buildString {
            for (index in indices) append(head[index])
        } + hashSegment.drop(HASH_LENGTH)
        parts[3] = decodedHash
    }
    return origin + parts.joinToString("/") + query
}

/**
 * 根据 license_code 生成 32 位解码 token（与 yt-dlp _kvs_get_license_token 一致）。
 */
internal fun kvsGetLicenseToken(licenseCode: String): IntArray {
    val code = licenseCode.replace("$", "")
    if (code.isEmpty()) {
        return IntArray(32)
    }
    val licenseValues = code.map { it - '0' }
    val modLicense = code.replace('0', '1')
    val center = modLicense.length / 2
    val frontHalf = modLicense.substring(0, center + 1).toLongOrNull() ?: 0L
    val backHalf = modLicense.substring(center).toLongOrNull() ?: 0L
    val mod = (4L * abs(frontHalf - backHalf)).toString().take(center + 1)

    val result = IntArray(mod.length * 4)
    var index = 0
    for ((tokenIndex, ch) in mod.withIndex()) {
        val current = ch - '0'
        for (offset in 0..3) {
            val licenseIndex = (tokenIndex + offset).coerceAtMost(licenseValues.lastIndex)
            result[index++] = (licenseValues[licenseIndex] + current) % 10
        }
    }
    return result
}

private const val HASH_LENGTH = 32
