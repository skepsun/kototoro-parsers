package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
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
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * TNAFlix - 成人视频网站
 *
 * 网站: https://www.tnaflix.com/
 * 最后验证: 2025-08（经本机 7890 代理实机验证列表/详情/分页/流地址）
 *
 * 页面结构:
 * - 视频详情: /video{n}/{slug}/
 * - 列表: /featured, /new, /popular, /toprated；分页 ?page={n}
 * - 搜索: /search?what={query}；支持 dir=latest/popular/toprated、d=duration、u=period
 * - 筛选: 分类来自 /categories + 时长(d=short/medium/long/full) + 时期(u=day/week/month/year)
 * - 视频 URL（按优先级）:
 *   1. 详情页 flashvars.config / config: '...' → 拉取 XML → <videoLink>...</videoLink>
 *   2. 详情页 <source src="...mp4...">
 *   3. 详情页内嵌 mp4 链接（排除 trailer），按清晰度取最高
 *
 * 注意: 站点使用 Cloudflare 类反爬，正式环境可能需要在浏览器完成验证后才能访问。
 */
@ContentSourceParser(
    "TNAFLIX", "TNAFlix", "en", type = ContentType.HENTAI_VIDEO,
)
internal class Tnaflix(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.TNAFLIX, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("www.tnaflix.com")

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = linkedSetOf<ContentTag>()
        DURATION_TAGS.forEach { (title, key) -> tags.add(ContentTag(title, key, source)) }
        PERIOD_TAGS.forEach { (title, key) -> tags.add(ContentTag(title, key, source)) }
        runCatching {
            val response = webClient.httpGet("https://$domain/categories", getRequestHeaders())
            tags += parseCategories(response.parseHtml())
        }
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

        val streamUrl = resolveStreamUrl(doc) ?: return emptyList()
        return listOf(videoPage(chapter, streamUrl))
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "https://$domain/")
        .build()

    /**
     * 解析视频列表页。仅收录 /video{n}/ 详情页链接，过滤导航。
     */
    internal fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        val thumbLinks = doc.select("a[class*='thumb'][href*='/video']").ifEmpty {
            doc.select("a[href*='/video']")
        }
        for (link in thumbLinks) {
            val href = link.attr("href").ifBlank { continue }
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!VIDEO_PATH_REGEX.containsMatchIn(absoluteUrl)) continue
            if (!seen.add(absoluteUrl)) continue

            val img = link.selectFirst("img")
            val coverUrl = img?.let {
                it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
            }?.toAbsoluteUrlOrNull(domain)

            val rawHref = link.attr("href")
            val title = doc.select("a[class*='title'], span[class*='title']")
                .firstOrNull { it.attr("href") == rawHref }
                ?.text()?.trim()
                ?: link.selectFirst("span[class*='title']")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: link.attr("title").ifBlank { "Untitled" }

            val duration = link.selectFirst("span[class*='duration']")?.text()?.trim()

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
     * 解析视频详情页得到最终流地址：
     * 1. 页面声明 config 时拉取其 XML 中的 <videoLink>
     * 2. 否则用页面内 <source>/内嵌 mp4 兜底
     */
    internal suspend fun resolveStreamUrl(doc: Document): String? {
        val configUrl = extractConfigUrl(doc)
        if (configUrl != null) {
            val xml = runCatching {
                webClient.httpGet(configUrl, getRequestHeaders()).parseRaw()
            }.getOrNull()
            val fromConfig = xml?.let { extractVideoLinkFromConfig(it) }
            if (fromConfig != null) {
                return fromConfig
            }
        }
        return extractDirectSource(doc)
    }

    /**
     * 从详情页提取 flashvars.config / config 指向的 XML 配置地址。
     */
    internal fun extractConfigUrl(doc: Document): String? {
        val html = doc.outerHtml()
        val config = FLASHVARS_CONFIG_REGEX.find(html)?.groupValues?.get(1)
            ?: CONFIG_SHORT_REGEX.find(html)?.groupValues?.get(1)
            ?: return null
        return config.replace("\\/", "/").replace("&amp;", "&").toAbsoluteUrl(domain)
    }

    /**
     * 从播放器 XML 配置中提取 <videoLink>。
     */
    internal fun extractVideoLinkFromConfig(xml: String): String? {
        return VIDEO_LINK_REGEX.find(xml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.takeIf { it.startsWith("http") }
    }

    /**
     * 详情页兜底: <source src> 或内嵌 mp4（排除 trailer），按清晰度取最高。
     */
    internal fun extractDirectSource(doc: Document): String? {
        val html = doc.outerHtml()
        val sources = doc.select("source[src]").mapNotNull { element ->
            element.attr("src").ifBlank { return@mapNotNull null }
        }.ifEmpty {
            MP4_REGEX.findAll(html).mapNotNull { match ->
                match.value.replace("&amp;", "&").takeIf { !it.contains("trailer", ignoreCase = true) }
            }.toList()
        }
        return sources.maxByOrNull { qualityOf(it) ?: 0 }
    }

    /**
     * 解析分类页（/categories）链接，key 为分类 slug。仅收录单段 /{slug} 形式的
     * 分类/明星/频道链接，过滤登录、导航、外部与 ?d=/?u= 快捷筛选链接。
     */
    internal fun parseCategories(doc: Document): Set<ContentTag> {
        val tags = linkedSetOf<ContentTag>()
        for (link in doc.select("a[href]")) {
            val href = link.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.startsWith("mailto:")) continue
            val key = href.toAbsoluteUrl(domain)
                .substringAfter("$domain/")
                .substringBefore('?')
                .trim('/')
            if (key.isBlank() || !CATEGORY_SLUG_REGEX.matches(key) || key in SKIP_SLUGS) continue
            val title = link.text().trim().ifBlank { continue }
            tags.add(ContentTag(title, key, source))
        }
        return tags
    }

    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val base = "https://$domain"
        val params = mutableListOf<String>()
        var category: String? = null
        for (tag in filter.tags) {
            when {
                tag.key.startsWith("d=") -> params += "d=${tag.key.substringAfter('=')}"
                tag.key.startsWith("u=") -> params += "u=${tag.key.substringAfter('=')}"
                category == null -> category = tag.key.trim('/')
            }
        }

        if (!filter.query.isNullOrBlank()) {
            val dir = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.RATING -> "toprated"
                else -> "latest"
            }
            params += "dir=$dir"
            if (page > 1) params += "page=$page"
            val search = "$base/search?what=${filter.query.urlEncoded()}"
            val query = params.joinToString("&")
            return if (query.isBlank()) search else "$search&$query"
        }
        if (page > 1) params += "page=$page"
        val query = params.joinToString("&")
        val path = category ?: when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.RATING -> "toprated"
            else -> "new"
        }
        return if (query.isBlank()) "$base/$path" else "$base/$path?$query"
    }

    private fun qualityOf(url: String): Int? =
        QUALITY_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun videoPage(chapter: ContentChapter, streamUrl: String): ContentPage = ContentPage(
        id = generateUid("page:${chapter.id}"),
        url = streamUrl,
        preview = null,
        headers = mapOf("Referer" to "https://$domain/"),
        source = source,
    )

    private companion object {
        val VIDEO_PATH_REGEX = Regex("""/video\d+""")
        val CATEGORY_SLUG_REGEX = Regex("""[a-z0-9-]+""")
        val FLASHVARS_CONFIG_REGEX = Regex("""flashvars\.config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val CONFIG_SHORT_REGEX = Regex("""config\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val VIDEO_LINK_REGEX = Regex("""<videoLink>([^<]+)</videoLink>""", RegexOption.IGNORE_CASE)
        val MP4_REGEX = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        val QUALITY_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)

        // 站点真实支持的时长与时期筛选（d=/u= 查询参数）
        val DURATION_TAGS = listOf(
            "Short (1-3 min)" to "d=short",
            "Medium (3-10 min)" to "d=medium",
            "Long (10-30 min)" to "d=long",
            "Full Length (30+ min)" to "d=full",
        )
        val PERIOD_TAGS = listOf(
            "Today" to "u=day",
            "This Week" to "u=week",
            "This Month" to "u=month",
            "This Year" to "u=year",
        )
        val SKIP_SLUGS = setOf(
            "login", "signup", "categories", "galleries", "channels", "pornstars",
            "tags", "dmca", "cookies", "text2257", "content-protection", "parental-control",
            "home", "featured", "new", "popular", "toprated", "search",
        )
    }
}
