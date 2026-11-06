package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * 全本小说网（www.quanben.io）—— 繁体/简体双站的老牌免费小说站，页面是 schema.org 微数据模板。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/c/{分类}.html`，第 2 页起 `/c/{分类}_{页码}.html`；卡片是 `div.row > div.list2`，
 *   `h3 a` 书名、`img` 封面、`p:contains(作者) span` 作者、`p[itemprop=description]` 简介。
 * - 搜索：`/index.php?c=book&a=search&keywords={关键词}`，结果同样是 `div.list2` 卡片。
 * - 详情：`/n/{slug}/`，`og:novel:*` 有书名 / 作者 / 分类 / 状态，简介在 `div.description`。
 * - 目录：`/n/{slug}/list.html`，`ul.list3 li a` 一次性给全；站点标题只有「第N章」没有章名，
 *   所以章名就按站点给的文本走，编号从「第N章」里取，缺编号时用位置补。
 * - 正文：`/n/{slug}/{章节序号}.html`，正文在 `div#content > p`（同页 `h1.headline` 只有章号）。
 */
@ContentSourceParser("QUANBEN", "全本小说网 (quanben)", "zh", type = ContentType.NOVEL)
internal class Quanben(context: ContentLoaderContext) :
    PagedContentParser(
    context,
    ContentParserSource.QUANBEN,
    pageSize = LIST_PAGE_SIZE,
    searchPageSize = SEARCH_PAGE_SIZE,
) {

    override val configKeyDomain = ConfigKey.Domain("www.quanben.io")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val categories = CATEGORIES.map { (key, title) -> ContentTag(title, key, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = categories,
            tagGroups = listOf(ContentTagGroup("分类", categories)),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim()
        if (!query.isNullOrEmpty() && page > 1) {
            // 站点搜索忽略 page 参数（第 2 页与第 1 页完全相同），只给一页就结束
            return emptyList()
        }
        val url = if (!query.isNullOrEmpty()) {
            "https://$domain/index.php?c=book&a=search&keywords=${query.urlEncoded()}"
        } else {
            val category = filter.tags.firstOrNull()?.key?.takeIf { tag -> CATEGORIES.any { it.first == tag } }
                ?: DEFAULT_CATEGORY
            "https://$domain/c/$category" + if (page > 1) "_$page.html" else ".html"
        }
        val items = parseListPage(fetchDocument(url))
        if (items.isEmpty() && page == 1) {
            if (!query.isNullOrEmpty()) {
                return emptyList()
            }
            throw NotFoundException("全本小说网(quanben)：分类列表为空，站点结构可能已变化", url)
        }
        return items
    }

    /** 分类页和搜索结果页共用同一种卡片：`div.list2`。 */
    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("div.list2").mapNotNull { card ->
            val titleLink = card.selectFirst("h3 a[href*=/n/]") ?: return@mapNotNull null
            val href = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = titleLink.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cover = card.selectFirst("img[src]")?.attrOrNull("src")?.toAbsoluteUrl(domain)
            val author = card.selectFirst("p:contains(作者) span")?.textOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = author?.let(::setOf).orEmpty(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val meta = doc.ogMeta()
        if (meta["og:novel:book_name"] == null && doc.selectFirst("div.list2 h3") == null) {
            throw NotFoundException("全本小说网(quanben)：作品不存在或已下架", url)
        }
        val catalogUrl = url.trimEnd('/') + "/list.html"
        val chapters = parseChapterLinks(fetchDocument(catalogUrl))
        if (chapters.isEmpty()) {
            throw NotFoundException("全本小说网(quanben)：目录为空，作品可能已下架", catalogUrl)
        }
        val category = meta["og:novel:category"]?.takeIf { it.isNotBlank() }
        return manga.copy(
            title = meta["og:novel:book_name"]?.takeIf { it.isNotBlank() } ?: manga.title,
            description = parseDescription(doc) ?: meta["og:description"]?.takeIf { it.isNotBlank() },
            coverUrl = meta["og:image"]?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain) ?: manga.coverUrl,
            authors = meta["og:novel:author"]?.takeIf { it.isNotBlank() }?.let(::setOf) ?: manga.authors,
            tags = category?.let { setOf(ContentTag(it, it, source)) } ?: manga.tags,
            state = when (meta["og:novel:status"]?.trim()) {
                "连载", "连载中" -> ContentState.ONGOING
                "完结", "已完结", "全本", "完本" -> ContentState.FINISHED
                else -> manga.state
            },
            chapters = chapters,
        )
    }

    internal fun parseDescription(doc: Document): String? =
        doc.selectFirst("div.description")
            ?.textOrNull()
            ?.trim()
            ?.trimEnd('…', '.', '。', ' ')
            ?.takeIf { it.isNotEmpty() }

    /** 目录页 `ul.list3` 是全量章节，正序。 */
    internal fun parseChapterLinks(doc: Document): List<ContentChapter> {
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<ContentChapter>()
        for (a in doc.select("ul.list3 li a[href]")) {
            val href = a.attrAsRelativeUrlOrNull("href")?.takeIf { CHAPTER_REGEX.matches(it) } ?: continue
            if (!seen.add(href)) continue
            val title = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            chapters += ContentChapter(
                id = generateUid(href),
                title = title,
                number = chapterNumber(title),
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
        // 站点偶尔给出「完本感言」「完结」这类不带章号的条目，用前一章的编号 +1 兜底
        var last = 0f
        return chapters.map { chapter ->
            val number = if (chapter.number > 0f) chapter.number else last + 1
            last = number
            chapter.copy(number = number)
        }
    }

    private fun chapterNumber(title: String): Float =
        CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val doc = fetchDocument(chapter.url.toAbsoluteUrl(domain))
        val html = extractChapter(doc)
            ?: return listOf(createMessagePage("该章节暂无正文"))
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = buildChapterHtml(chapter.title, html).toDataUrl(),
                preview = null,
                source = source,
            ),
        )
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
        ?: throw ParseException("全本小说网(quanben)：正文页缺少内容", page.id.toString())

    /** 正文在 `div#content`；同页的 `span#ad` 广告与 `h1.headline` 章号都去掉。 */
    internal fun extractChapter(doc: Document): String? {
        val container = doc.selectFirst("div#content") ?: return null
        val body = Jsoup.parseBodyFragment(container.html(), doc.baseUri()).body()
        body.select("script, style, ins, iframe, a, h1, span").remove()
        val text = body.text()
        if (text.isBlank() || PLACEHOLDER_MARKERS.any { text.contains(it) }) {
            return null
        }
        return body.html().trim().ifEmpty { null }
    }

    private fun buildChapterHtml(chapterTitle: String?, bodyHtml: String): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
        append("<style>body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;padding:16px;")
        append("padding-bottom:50px;margin:0;line-height:1.9;font-size:1.05rem;}")
        append("h1{font-size:1.25rem;line-height:1.5;margin:0 0 1em;}p{margin:0 0 .9em;text-indent:2em;}</style>")
        append("</head><body>")
        if (!chapterTitle.isNullOrBlank()) {
            append("<h1>").append(chapterTitle).append("</h1>")
        }
        append(bodyHtml)
        append("</body></html>")
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private fun createMessagePage(message: String): ContentPage = ContentPage(
        id = 0L,
        url = "<html><body><p>${message.escapeHtml()}</p></body></html>".toDataUrl(),
        preview = null,
        source = source,
    )

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        /** 分类页每页 12 部；搜索页一次给 96 条且忽略分页参数。 */
        const val LIST_PAGE_SIZE = 12
        const val SEARCH_PAGE_SIZE = 96
        const val DEFAULT_CATEGORY = "xuanhuan"

        /** 分类 slug → 名称，取自首页导航里的分类链接。 */
        val CATEGORIES = listOf(
            "xuanhuan" to "玄幻",
            "dushi" to "都市",
            "yanqing" to "言情",
            "chuanyue" to "穿越",
            "qingchun" to "青春",
            "xianxia" to "仙侠",
            "lingyi" to "灵异",
            "xuanyi" to "悬疑",
            "lishi" to "历史",
            "junshi" to "军事",
            "youxi" to "游戏",
            "jingji" to "竞技",
            "kehuan" to "科幻",
            "zhichang" to "职场",
            "guanchang" to "官场",
            "xianyan" to "现言",
            "danmei" to "耽美",
            "qita" to "其它",
        )

        val CHAPTER_REGEX = Regex("""^/n/[^/]+/\d+\.html$""")
        val CHAPTER_NUMBER_REGEX = Regex("""第\s*(\d+(?:\.\d+)?)\s*[章節节回]""")
        val PLACEHOLDER_MARKERS = listOf("正在手打", "本章节内容", "请稍后", "章节内容不完整")
    }
}
