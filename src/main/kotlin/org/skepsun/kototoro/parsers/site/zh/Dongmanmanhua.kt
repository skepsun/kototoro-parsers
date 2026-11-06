package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * 咚漫中文（www.dongmanmanhua.cn）—— LINE Webtoon 的中文条漫站，页面为服务端渲染的 HTML。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：站内**没有分页参数**。`/ranking`（即「漫画分类」页，`/genre` 同内容）一次返回全部约 2400 部作品，
 *   按 12 个题材段落分组：每个段落是 `<h2 data-genre="LOVE">恋爱</h2>` 紧跟 `ul.card_lst > li[id=title_li_...]`。
 *   服务端只认 `sortOrder`：`READ_COUNT`（按人气，默认）、`LIKEIT`（按点赞数）、`UPDATE`（按更新时间）；
 *   因此这里的翻页是本地切片，并把整份列表按排序键缓存 [LISTING_TTL_MS]，避免每页重复下载。
 * - 搜索：`/search?keyword=&searchMode=TITLE&page=N`，约 17~18 条/页，返回同样的卡片结构。
 * - 详情：`/{题材}/{slug}/list?title_no={id}`，作品名/作者/封面/题材在 `div.detail_header`，简介只有 `og:description`。
 *   页面上的埋点属性 `data-sc-event-parameter` 含裸引号（`subcategory_"1":COMEDY`），HTML 解析会截断，
 *   里面的 `serial_status` 取不到；连载状态改用「是否出现在完结段」推断。
 * - 目录：同一页面 `#_listUl li[id=episode_N]`，每页 10 话、新话在前，第 11 话起在 `&page=N`（N 与话号无关），
 *   必须逐页取到「没有新话」为止；`.paginate` 只显示当前 8 个页码，不能用来算总页数。
 * - 阅读页：`/{题材}/{slug}/{话 slug}/viewer?title_no=&episode_no=`，正文图片在 `#_imageList` 的
 *   `img._images[data-url]`（`src` 只是占位透明图），同页还有 300 多张 `img._thumbnailImages` 缩略图必须排除。
 *   图片直链无防盗链，但 `data-url` 带 `x-oss-process=image/quality,q_90` 压缩参数，去掉可拿到原图。
 *   需要登录、付费或尚未到免费解锁时间的话，`#_imageList` 为空，这里显式报错而不是返回空页面。
 */
@ContentSourceParser("DONGMANMANHUA", "咚漫中文", "zh", type = ContentType.MANGA)
internal class Dongmanmanhua(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.DONGMANMANHUA, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.dongmanmanhua.cn")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    @Volatile
    private var listing: Listing? = null

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genres = GENRES.map { (code, title) -> ContentTag(title, code, source) }.toSet()
        val all = ContentTag("全部", TAG_ALL, source)
        return ContentListFilterOptions(
            availableTags = genres + all,
            tagGroups = listOf(ContentTagGroup("题材", genres)),
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
            return parseSearchPage(fetchDocument(searchUrl(query, page)))
        }
        val tagKey = filter.tags.firstOrNull()?.key?.takeIf { it != TAG_ALL }
        val contents = loadListing(sortCodeOf(order))
        val filtered = if (tagKey == null) {
            contents
        } else {
            contents.filter { content -> content.tags.any { it.key == tagKey } }
        }
        return slicePage(filtered, page)
    }

    override suspend fun getDetails(manga: Content): Content {
        val baseUrl = manga.url.toAbsoluteUrl(domain)
        val firstDoc = fetchDocument(baseUrl)
        if (!isDetailsPage(firstDoc)) {
            throw NotFoundException("咚漫中文：作品不存在或已下架", baseUrl)
        }
        val details = parseDetails(firstDoc, manga)
        val chapters = ArrayList<ContentChapter>(LIST_PAGE_SIZE)
        val seen = HashSet<String>()
        var doc: Document? = firstDoc
        var page = 1
        while (doc != null && page <= MAX_EPISODE_PAGES) {
            val fresh = parseChapterLinks(doc).filter { seen.add(it.url) }
            if (fresh.isEmpty()) {
                break
            }
            chapters += fresh
            page++
            doc = runCatching { fetchDocument("$baseUrl&page=$page") }.getOrNull()
        }
        return details.copy(chapters = toChapters(chapters))
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val images = parseViewerImages(fetchDocument(url))
        if (images.isEmpty()) {
            throw ParseException(
                shortMessage = "咚漫中文：该话没有公开图片，可能需要登录、付费或尚未到免费解锁时间",
                url = url,
            )
        }
        val headers = mapOf("Referer" to "https://$domain/")
        return images.mapIndexed { index, imageUrl ->
            ContentPage(
                id = generateUid("$url#$index"),
                url = imageUrl,
                preview = null,
                headers = headers,
                source = source,
            )
        }
    }

    /** `/ranking` 一次给出全部作品（按题材分段），这里按排序键缓存整份结果，翻页只是本地切片 */
    internal suspend fun loadListing(sortCode: String): List<Content> {
        val cached = listing
        val now = System.currentTimeMillis()
        if (cached != null && cached.sortCode == sortCode && now - cached.fetchedAt < LISTING_TTL_MS) {
            return cached.contents
        }
        val contents = parseRankingPage(fetchDocument("https://$domain/ranking?sortOrder=$sortCode"))
        if (contents.isEmpty()) {
            throw ParseException(
                shortMessage = "咚漫中文：分类列表为空（/ranking 结构可能已变化）",
                url = "https://$domain/ranking?sortOrder=$sortCode",
            )
        }
        listing = Listing(sortCode, now, contents)
        return contents
    }

    /**
     * 整份分类页：约 2445 张卡片但只有约 1322 部作品——完结算在「完结」段里又重复出现在题材段，
     * 所以按作品 URL 归并，把两个段的题材都并进 tags；出现在「完结」段即视为已完结。
     */
    internal fun parseRankingPage(doc: Document): List<Content> {
        val byUrl = LinkedHashMap<String, Content>()
        for (header in doc.select("h2[data-genre]")) {
            val code = header.attr("data-genre").trim()
            if (code.isEmpty()) {
                continue
            }
            val title = header.text().trim().ifEmpty { GENRES[code] ?: code }
            val tag = ContentTag(title, code, source)
            val list = header.nextElementSibling()?.takeIf { it.normalName() == "ul" } ?: continue
            for (item in list.select("li[id^=title_li_]")) {
                val card = parseCard(item, tag) ?: continue
                val existing = byUrl[card.url]
                byUrl[card.url] = if (existing == null) card else existing.copy(tags = existing.tags + tag)
            }
        }
        return byUrl.values.map { card ->
            card.copy(state = if (card.tags.any { it.key == GENRE_TERMINATION }) ContentState.FINISHED else null)
        }
    }

    internal fun parseSearchPage(doc: Document): List<Content> {
        return doc.select("li[id^=title_li_]").mapNotNull { parseCard(it, null) }
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        val header = doc.selectFirst("div.detail_header")
        val title = header?.selectFirst("h1.subj")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attrOrNull("content")
                ?.substringBefore('_')?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.title
        val authors = header?.selectFirst("span.author")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setOf(it) }
            ?: manga.authors
        val coverUrl = header?.selectFirst("span.thmb img")?.attrOrNull("src")?.toAbsoluteUrl(domain)
            ?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")?.toAbsoluteUrl(domain)
            ?: manga.coverUrl
        val description = doc.selectFirst("meta[property=og:description]")?.attrOrNull("content")
            ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.description
        val tags = parseGenreTag(header)?.let { manga.tags + it } ?: manga.tags
        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = authors,
            tags = tags,
        )
    }

    /** 详情页 `#_listUl li[id=episode_N]`，新话在前；调用方负责多页拼接与顺序翻转 */
    internal fun parseChapterLinks(doc: Document): List<ContentChapter> {
        return doc.select("li[id^=episode_]").mapNotNull { item ->
            val link = item.selectFirst("a[href*=viewer]") ?: return@mapNotNull null
            val url = toRelativePath(link.attrOrNull("href")) ?: return@mapNotNull null
            val title = item.selectFirst("span.subj")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.selectFirst("span.thmb img")?.attrOrNull("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            ContentChapter(
                id = generateUid(url),
                title = title,
                number = 0f,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = parseDate(item.selectFirst("span.date")?.text()),
                branch = null,
                source = source,
            )
        }
    }

    /** 阅读页正文图片：只有 `#_imageList` 里的 `img._images[data-url]`，同页缩略图是 `_thumbnailImages` */
    internal fun parseViewerImages(doc: Document): List<String> {
        val area = doc.selectFirst("#_imageList") ?: doc.selectFirst("div.viewer_img")
        val nodes = area?.select("img._images[data-url]").orEmpty().ifEmpty {
            doc.select("img._images[data-url]")
        }
        val result = ArrayList<String>(nodes.size)
        for (node in nodes) {
            val source = node.attrOrNull("data-url")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val url = stripImageProcessing(source)
            if (url !in result) {
                result += url
            }
        }
        return result
    }

    /** 站点对不存在的 title_no 返回 404 的「Page not found」，没有任何详情结构 */
    internal fun isDetailsPage(doc: Document): Boolean {
        return doc.selectFirst("div.detail_header") != null || doc.selectFirst("#_listUl") != null
    }

    /** 本地切片：站内列表没有分页参数，页码按 [LIST_PAGE_SIZE] 对过滤后的结果切分 */
    internal fun slicePage(contents: List<Content>, page: Int): List<Content> {
        val from = (page - 1) * LIST_PAGE_SIZE
        if (from < 0 || from >= contents.size) {
            return emptyList()
        }
        return contents.subList(from, minOf(from + LIST_PAGE_SIZE, contents.size))
    }

    private fun parseCard(item: Element, tag: ContentTag?): Content? {
        val link = item.selectFirst("a.card_item[href]") ?: item.selectFirst("a[href]") ?: return null
        val url = toRelativePath(link.attrOrNull("href")) ?: return null
        val title = item.selectFirst("p.subj")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val author = item.selectFirst("p.author")?.text()?.trim().orEmpty()
        val coverUrl = item.selectFirst("img[src]")?.attrOrNull("src")
            ?.let(::stripImageProcessing)?.toAbsoluteUrl(domain)
        return Content(
            id = generateUid(url),
            title = title,
            altTitles = emptySet(),
            url = url,
            publicUrl = url.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = sourceContentRating,
            coverUrl = coverUrl,
            tags = tag?.let { setOf(it) } ?: emptySet(),
            state = null,
            authors = author.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet(),
            source = source,
        )
    }

    /**
     * 详情页题材只能从 `h2.genre` 取显示名（配置页 `data-sc-event-parameter` 里虽然有 `genre:`/`subcategory_`，
     * 但属性值内含裸引号，HTML 解析时会被截断在 `subcategory_`，不可用）；
     * 活动专区等非题材分类返回 null，保留列表里已解析好的 tags。
     */
    private fun parseGenreTag(header: Element?): ContentTag? {
        val name = header?.selectFirst("h2.genre")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val code = GENRES.entries.firstOrNull { it.value == name }?.key ?: return null
        return ContentTag(name, code, source)
    }

    private fun sortCodeOf(order: SortOrder): String = when (order) {
        SortOrder.UPDATED, SortOrder.UPDATED_ASC -> SORT_UPDATE
        else -> SORT_POPULARITY
    }

    private fun searchUrl(query: String, page: Int): String = buildString {
        append("https://").append(domain).append("/search?keyword=")
        append(query.urlEncoded())
        append("&searchMode=TITLE")
        if (page > 1) {
            append("&page=").append(page)
        }
    }

    /** 站内链接有两种写法：`/LOVE/x/list?title_no=1` 与协议相对的 `//www.dongmanmanhua.cn/LOVE/x/list?title_no=1` */
    private fun toRelativePath(raw: String?): String? {
        val link = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (link.startsWith("/") && !link.startsWith("//")) {
            return link
        }
        val withoutScheme = link.substringAfter("//", link)
        val slash = withoutScheme.indexOf('/')
        if (slash < 0) {
            return null
        }
        return withoutScheme.substring(slash).takeIf { it.isNotEmpty() }
    }

    /** 去掉 OSS 图片处理参数（`x-oss-process=image/quality,q_90`），原图质量更高 */
    private fun stripImageProcessing(url: String): String {
        val index = url.indexOf('?')
        if (index < 0) {
            return url
        }
        val base = url.substring(0, index)
        val params = url.substring(index + 1).split('&').filterNot { param ->
            param.startsWith("x-oss-process=") || param.startsWith("type=x-oss-process")
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    private fun parseDate(raw: String?): Long {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        return runCatching {
            SimpleDateFormat("yyyy-M-d", Locale.ROOT).parse(text)?.time
        }.getOrNull() ?: 0L
    }

    /** 站点目录是新话在前，这里翻成从前到后并补上序号 */
    internal fun toChapters(collected: List<ContentChapter>): List<ContentChapter> {
        return collected.asReversed().mapIndexed { index, chapter -> chapter.copy(number = index + 1f) }
    }

    private suspend fun fetchDocument(url: String): Document {
        return webClient.httpGet(url, getRequestHeaders()).parseHtml()
    }

    private class Listing(
        val sortCode: String,
        val fetchedAt: Long,
        val contents: List<Content>,
    )

    private companion object {

        const val LIST_PAGE_SIZE = 30
        const val MAX_EPISODE_PAGES = 60
        const val LISTING_TTL_MS = 10 * 60 * 1000L
        const val TAG_ALL = "ALL"
        const val SORT_POPULARITY = "READ_COUNT"
        const val SORT_UPDATE = "UPDATE"

        /** 站点固定的 12 个题材（`h2[data-genre]` 的取值），顺序与页面一致 */
        val GENRES: Map<String, String> = linkedMapOf(
            "LOVE" to "恋爱",
            "BOY" to "少年",
            "ANCIENTCHINESE" to "古风",
            "FANTASY" to "奇幻",
            "COMEDY" to "搞笑",
            "CAMPUS" to "校园",
            "METROPOLIS" to "都市",
            "HEALING" to "治愈",
            "SUSPENSE" to "悬疑",
            "INSPIRATIONAL" to "励志",
            "FILMADAPTATION" to "影视化",
            "TERMINATION" to "完结",
        )

        const val GENRE_TERMINATION = "TERMINATION"
    }
}
