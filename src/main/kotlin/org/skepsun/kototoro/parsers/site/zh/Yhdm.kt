package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import org.json.JSONObject
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.toJSONObjectOrNull
import java.net.URLDecoder
import java.util.EnumSet

/**
 * 樱花动漫官方门户站群（默认 www.dongman.hk）—— Next.js App Router 服务端渲染，分集与播放地址都在 HTML 里。
 *
 * 站点协议（2026-11 实测）：
 * - 站群同模板：`dongman.hk`、`dongman88.cn`、`hcamelot.com`、`kissanime.hk`、`kissanime.tw`、
 *   `donghua.hk`、`9anime.hk`、`yhdm88.cn`、`yhdm365.cn`、`kotakanime.net` 共用一套路由与 DOM，
 *   所以域名做成 `ConfigKey.Domain`，换域名不用改代码。
 * - 列表：`/category/{dongman|fanju|guochuang|dianying}?page={页码}`、`/latest?page={页码}`，
 *   24 部/页；卡片是 `ul.ws-grid > li > a.ws-card`，标题在 `img[alt]`，封面在 `srcSet` 的
 *   `/_next/image?url=%2Fmedia%2F...` 里（把 `url` 参数解码后拼到站点域名上就能直接访问）。
 * - 搜索：`/search?q={关键字}`（表单字段名是 `q`，不是 `wd`；用 `wd` 会拿到空结果页）。
 * - 排行页 `/top` 是杂志式版面（`article.ws-topone` + `p.ws-topone-no`），与分类页的
 *   `ul.ws-grid` 不共用结构，且混着侧栏推荐，因此只暴露「最近更新」一种排序。
 * - 详情：`/movie/{id}`，正文数据在 `script[type=application/ld+json]` 的 `TVSeries`（名称、封面、
 *   简介、年份）；分集是 `ol.ws-epgrid > li > a.ws-ep`，`title` 是「第N集」，`href` 指向
 *   `/watch/{id}?s={线路下标}&e={集下标}`。一个作品可能有多条线路（多个 `ol.ws-epgrid`）。
 * - 播放页：`/watch/{id}?s=&e=` 的 HTML 里直接内联 HLS 直链（形如
 *   `https://v.fengbao14.com/video/xxx/index.m3u8`），无 JS 挑战、无防盗链。
 *   本站只解析这些公开的免费播放地址，不处理会员或第三方解析线路。
 */
@ContentSourceParser("YHDM", "樱花动漫", "zh", type = ContentType.VIDEO)
internal class Yhdm(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.YHDM, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.dongman.hk")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val categories = CATEGORIES.map { (slug, title) -> ContentTag(title, slug, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = categories,
            tagGroups = listOf(ContentTagGroup("频道", categories)),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return parseSearchPage(fetchDocument("https://$domain/search?q=${query.urlEncoded()}"))
        }
        val url = run {
            val slug = filter.tags.firstOrNull()?.key?.takeIf { tag -> CATEGORIES.any { it.first == tag } }
                ?: DEFAULT_CATEGORY
            val pageSuffix = if (page > 1) "?page=$page" else ""
            if (slug == LATEST_SLUG) {
                "https://$domain/latest$pageSuffix"
            } else {
                "https://$domain/category/$slug$pageSuffix"
            }
        }
        return parseListPage(fetchDocument(url))
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        if (doc.selectFirst("ol.ws-epgrid") == null && doc.selectFirst("h1") == null) {
            throw ParseException(shortMessage = "樱花动漫：详情页结构缺失", url = url)
        }
        val ld = parseLdJson(doc)
        val title = doc.selectFirst("h1")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ld.getStringOrNull("name")
            ?: manga.title
        val cover = ld.getStringOrNull("image")?.takeIf { it.startsWith("http") } ?: manga.coverUrl
        val chapters = parseChapters(doc)
        if (chapters.isEmpty()) {
            throw ParseException(shortMessage = "樱花动漫：没有解析到分集", url = url)
        }
        val genres = ld.optJSONArray("genre")?.let { array ->
            (0 until array.length()).map { index -> array.optString(index) }.filter { it.isNotBlank() }
        }.orEmpty()
        return manga.copy(
            title = title,
            description = ld.getStringOrNull("description")?.trim()?.takeIf { it.isNotEmpty() }
                ?: manga.description,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = genres.mapTo(HashSet()) { ContentTag(it, it, source) }.ifEmpty { manga.tags },
            state = parseState(doc.selectFirst(".ws-status, .ws-ep-total")?.textOrNull().orEmpty(), chapters.size),
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val pageUrl = chapter.url.toAbsoluteUrl(domain)
        val videoUrl = parseVideoUrl(fetchDocument(pageUrl).html(), pageUrl)
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = videoUrl,
                preview = pageUrl,
                source = source,
            ),
        )
    }

    /** 卡片形如 `a.ws-card`：标题在 `span.ws-card-name`，状态在 `span.ws-card-remarks`，封面在 `srcSet` 的 `url` 参数里。 */
    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("ul.ws-grid li a.ws-card[href^=/movie/]").mapNotNull { card ->
            val href = card.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val img = card.selectFirst("img[alt]")
            val title = card.selectFirst(".ws-card-name")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: img?.attrOrNull("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val remarks = card.selectFirst(".ws-card-remarks")?.textOrNull()?.trim().orEmpty()
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = parseCardCover(img, domain),
                tags = emptySet(),
                state = parseState(remarks, 0),
                authors = emptySet(),
                source = source,
            )
        }
    }

    /**
     * 搜索结果页是另一套结构：`ul.ws-results > li > article.ws-result`，
     * 书名在 `h2.ws-result-name a`，封面在 `a.ws-result-art img` 的 `src`。
     * 搜索没有分页参数（`&page=2` 仍返回同一批），所以只解析首页。
     */
    internal fun parseSearchPage(doc: Document): List<Content> {
        return doc.select("ul.ws-results li article.ws-result").mapNotNull { item ->
            val link = item.selectFirst("h2.ws-result-name a[href^=/movie/]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = link.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val coverImg = item.selectFirst("a.ws-result-art img")
            val excerpt = item.selectFirst(".ws-result-excerpt")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = parseCardCover(coverImg, domain),
                tags = emptySet(),
                state = parseState(item.selectFirst(".ws-byline")?.textOrNull().orEmpty(), 0),
                authors = emptySet(),
                source = source,
                description = excerpt,
            )
        }
    }

    /** 封面来自 Next.js 图片代理：`/_next/image?url=%2Fmedia%2F...`，解码后拼站点域名可直取。 */
    internal fun parseCardCover(img: org.jsoup.nodes.Element?, host: String): String? {
        val candidates = buildList {
            img?.attrOrNull("src")?.takeIf { it.isNotBlank() && !it.contains("/_next/image") }?.let(::add)
            img?.attrOrNull("srcSet")?.let { srcSet ->
                srcSet.split(',').forEach { part ->
                    part.trim().substringBefore(' ').takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
        for (candidate in candidates) {
            val path = NEXT_IMAGE_URL_PARAM.find(candidate)?.groupValues?.get(1)
                ?.let { decodeUrlComponent(it) }
                ?: candidate
            if (path.startsWith("/media/") || path.startsWith("/_next/image")) {
                return path.toAbsoluteUrl(host)
            }
        }
        return null
    }

    /** 分集按线路分组：`ol.ws-epgrid` 的 `aria-label` 里带线路名，单线路时不加前缀。 */
    internal fun parseChapters(doc: Document): List<ContentChapter> {
        val groups = doc.select("ol.ws-epgrid")
        val multiLine = groups.size > 1
        val chapters = LinkedHashMap<String, ContentChapter>()
        var index = 0
        for (group in groups) {
            val lineName = group.attrOrNull("aria-label")
                ?.substringBefore("选集")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            for (a in group.select("a.ws-ep[href]")) {
                val href = a.attrAsRelativeUrlOrNull("href")?.takeIf { it.startsWith("/watch/") }
                    ?: continue
                val rawTitle = a.attrOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: continue
                val title = if (multiLine && lineName != null) "$lineName $rawTitle" else rawTitle
                index++
                chapters[href] = ContentChapter(
                    id = generateUid(href),
                    title = title,
                    number = index.toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = lineName,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }
        }
        return chapters.values.toList()
    }

    /** 播放页里唯一的取数点：内联的 HLS 直链。 */
    internal fun parseVideoUrl(html: String, pageUrl: String): String {
        val url = M3U8_REGEX.find(html)?.value
            ?: throw ParseException(shortMessage = "樱花动漫：播放页没有内联 m3u8 地址", url = pageUrl)
        if (!url.startsWith("http")) {
            throw ParseException(shortMessage = "樱花动漫：播放地址不是绝对地址", url = pageUrl)
        }
        return url
    }

    internal fun parseLdJson(doc: Document): JSONObject {
        return doc.select("script[type=application/ld+json]")
            .mapNotNull { script -> script.data().takeIf { it.isNotBlank() }?.toJSONObjectOrNull() }
            .firstOrNull { it.getStringOrNull("@type") == "TVSeries" }
            ?: JSONObject()
    }

    private fun parseState(text: String, episodeCount: Int): ContentState? = when {
        text.contains("已完结") || REGEX_ALL_EPISODES.containsMatchIn(text) -> ContentState.FINISHED
        text.contains("更新至") || episodeCount > 0 -> ContentState.ONGOING
        else -> null
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private companion object {
        const val LIST_PAGE_SIZE = 24
        const val DEFAULT_CATEGORY = "dongman"
        const val LATEST_SLUG = "latest"

        /** 频道 slug → 名称，取自站点导航（同名站群路由一致）。 */
        val CATEGORIES = listOf(
            "dongman" to "动漫",
            "fanju" to "番剧",
            "guochuang" to "国创",
            "dianying" to "动画电影",
        )

        val NEXT_IMAGE_URL_PARAM = Regex("""url=([^&]+)""")
        val M3U8_REGEX = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")
        val REGEX_ALL_EPISODES = Regex("""全\s*\d+\s*集""")

        fun decodeUrlComponent(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }
    }
}
