package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
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
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * PlayVids - 成人视频网站（PeekVids 同源网络）
 *
 * 网站: https://www.playvids.com/
 * 最后验证: 2025-08（经本机 7890 代理实机验证列表/详情/分页/HLS 流地址）
 *
 * 页面结构:
 * - 视频详情: /{videoId}/{slug}/ 或 /v/{videoId}
 * - 列表: /?page={n}，热门: /Trending-Porn
 * - 搜索: /videos?q={query}
 * - 筛选: 分类来自 /categories（<li><a> 列表），请求 /{分类路径}?page={n}
 * - 视频 URL:
 *   - 现代页面: <video data-id="{shortId}">，随后请求 /v-alt/{shortId} 返回
 *     {"data-src1080": "...", "data-src720": "...", ...} 按清晰度取最高
 *   - 旧页面兜底: 视频页内 <source src="...mp4...">
 *
 * 注意: 站点有反爬（429 Rate Limit Exceeded），被限流时列表为空属于站点行为。
 */
@ContentSourceParser(
    "PLAYVIDS", "PlayVids", "en", type = ContentType.HENTAI_VIDEO,
)
internal class PlayVids(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.PLAYVIDS, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("www.playvids.com")

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
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
        val tags = runCatching {
            val response = webClient.httpGet("https://$domain/categories", getRequestHeaders())
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
        val directUrl = chapter.url.takeIf { it.startsWith("http") }
        if (directUrl != null) {
            return listOf(videoPage(chapter, directUrl))
        }
        val detailUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(detailUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val shortId = doc.selectFirst("video[data-id]")?.attr("data-id")?.trim()
        if (!shortId.isNullOrBlank()) {
            val playlistUrl = "https://$domain/v-alt/$shortId"
            val json = runCatching {
                webClient.httpGet(playlistUrl, getRequestHeaders()).parseJsonObject()
            }.getOrNull()
            val best = json?.let { pickBestStream(it) }
            if (best != null) {
                return listOf(videoPage(chapter, best))
            }
        }

        val sourceUrl = extractDirectSource(doc)
            ?: return emptyList()
        return listOf(videoPage(chapter, sourceUrl))
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "https://$domain/")
        .build()

    /**
     * 解析视频列表页。跳过轮播与导航卡片，仅收录指向视频详情页的条目。
     */
    internal fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        for (card in doc.select("div.card")) {
            if (card.classNames().any { it.contains("carousel", ignoreCase = true) }) {
                continue
            }
            val img = card.selectFirst("img[src]") ?: continue
            val link = card.select("a[href]").firstOrNull { isVideoUrl(it.attr("href")) } ?: continue
            val href = link.attr("href")
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue

            val coverUrl = img.attr("src").toAbsoluteUrlOrNull(domain)
            val title = link.selectFirst("span[class*='title']")?.text()?.trim()
                ?: link.attr("title").ifBlank { link.text().trim() }.ifBlank {
                    img.attr("alt")
                }.ifBlank {
                    "Untitled"
                }
            val duration = card.selectFirst("span[class*='duration']")?.text()?.trim()

            items.add(
                Content(
                    id = generateUid(absoluteUrl),
                    title = title,
                    altTitles = emptySet(),
                    url = absoluteUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
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
            ?: doc.selectFirst("video[poster]")?.attr("poster")?.toAbsoluteUrlOrNull(domain)

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
     * 从 /v-alt/{id} 的 JSON 中选取清晰度最高的流地址。
     * 键形如 data-src1080 / data-src720，取数字最大的一项。
     */
    internal fun pickBestStream(json: JSONObject): String? {
        var bestUrl: String? = null
        var bestHeight = -1
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val height = STREAM_KEY_REGEX.find(key)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val url = json.optString(key).takeIf { it.startsWith("http") } ?: continue
            if (height > bestHeight) {
                bestHeight = height
                bestUrl = url
            }
        }
        return bestUrl
    }

    /**
     * 旧版页面兜底: 取 <source src> 中最优的播放地址。站点现以 HLS 为主
     * （…/…,…urlset/master.m3u8），无清晰度标注时按出现顺序取第一个。
     */
    internal fun extractDirectSource(doc: Document): String? {
        val sources = doc.select("source[src]").mapNotNull { element ->
            val raw = element.attr("src").ifBlank { return@mapNotNull null }
            if (raw.startsWith("blob:") || !raw.startsWith("http")) return@mapNotNull null
            val absoluteRaw = raw.replace("&amp;", "&").toAbsoluteUrl(domain)
            absoluteRaw to QUALITY_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        }
        return sources.maxByOrNull { it.second ?: 0 }?.first
    }

    /**
     * 解析分类页（/categories）。分类/标签条目是 <li><a href="/category/..."> 或
     * <a href="/tgs/...">（含 /lesbian/category/... 子分类），其余导航/功能链接忽略。
     */
    internal fun parseCategories(doc: Document): Set<ContentTag> {
        val tags = linkedSetOf<ContentTag>()
        for (link in doc.select("li a[href]")) {
            val href = link.attr("href").ifBlank { continue }
            if (href.startsWith("#")) continue
            val key = href
                .removePrefix("https://$domain")
                .removePrefix("http://$domain")
                .trim('/')
            if (key.isBlank()) continue
            if (!key.contains("category/") && !key.contains("tgs/")) continue
            val title = link.text().trim().ifBlank { continue }
            tags.add(ContentTag(title, key, source))
        }
        return tags
    }

    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val base = "https://$domain"
        return when {
            !filter.query.isNullOrBlank() -> {
                val search = "$base/videos?q=${filter.query.urlEncoded()}"
                if (page > 1) "$search&page=$page" else search
            }
            filter.tags.isNotEmpty() -> {
                val tag = filter.tags.first().key.trim('/')
                if (page > 1) "$base/$tag?page=$page" else "$base/$tag"
            }
            order == SortOrder.POPULARITY -> {
                if (page > 1) "$base/Trending-Porn?page=$page" else "$base/Trending-Porn"
            }
            else -> {
                if (page > 1) "$base/?page=$page" else "$base/"
            }
        }
    }

    private fun isVideoUrl(href: String): Boolean {
        val path = href.toAbsoluteUrl(domain).substringAfter("$domain").trimStart('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false
        return when (segments.first().lowercase()) {
            "videos", "categories", "channels", "pornstars", "trending", "trending-porn", "search", "embed" -> false
            "v" -> segments.size >= 2
            else -> {
                val first = segments.first()
                first.length >= 6 && first.all { it.isLetterOrDigit() || it == '_' }
            }
        }
    }

    private fun videoPage(chapter: ContentChapter, streamUrl: String): ContentPage = ContentPage(
        id = generateUid("page:${chapter.id}"),
        url = streamUrl,
        preview = null,
        headers = mapOf("Referer" to "https://$domain/"),
        source = source,
    )

    private companion object {
        val STREAM_KEY_REGEX = Regex("""data-src(\d{3,})""", RegexOption.IGNORE_CASE)
        val QUALITY_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
    }
}
