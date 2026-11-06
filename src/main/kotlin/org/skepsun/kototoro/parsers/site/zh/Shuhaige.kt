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
 * 书海阁小说网移动版（m.shuhaige.net）—— 免费小说站，layui 模板，全部服务端渲染。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/{分类}/`，第 2 页起 `/{分类}/{页码}.html`，20 部/页；卡片是 `ul.list > li`：
 *   `a img` 封面、`p.bookname a` 书名、`p.data a[href^=/author/]` 作者、`p.intro` 简介。
 * - 搜索：表单 `POST /search.html`（`searchkey`），结果页把关键词换成搜索 id 并给出
 *   `/search/{id}/{页码}.html`；第 1 页直接解析 POST 结果，后续页用 id 走 GET。
 * - 详情：`/shu_{id}.html`，`div.detail img` 封面、`p.name strong` 书名、`p.author a` 作者、
 *   `div.intro` 简介、`p a.layui-btn` 分类、`p span.layui-btn` 连载状态。
 * - 目录：`/{id}/`、`/{id}_{页码}/`，`ul.read li a`，50 章/页，总页数在 `select option` 里。
 * - 正文：`/{id}/{章节id}.html`，正文在 `div.content > p`（同页其余区块是「大家在看」等推荐）。
 */
@ContentSourceParser("SHUHAIGE", "书海阁", "zh", type = ContentType.NOVEL)
internal class Shuhaige(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.SHUHAIGE, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("m.shuhaige.net")

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
        val items = if (!query.isNullOrEmpty()) {
            searchPage(page, query)
        } else {
            val category = filter.tags.firstOrNull()?.key?.takeIf { tag -> CATEGORIES.any { it.first == tag } }
                ?: DEFAULT_CATEGORY
            // 分类分页是 `/{分类}/{页码}.html`（页面里的「下一页」写的是 list_N.html，但那个地址 404）
            val url = "https://$domain/$category/" + if (page > 1) "$page.html" else ""
            parseListPage(fetchDocument(url))
        }
        if (items.isEmpty() && page == 1 && query.isNullOrEmpty()) {
            throw NotFoundException("书海阁：分类列表为空，站点结构可能已变化", "$domain/$DEFAULT_CATEGORY/")
        }
        return items
    }

    /**
     * 搜索第 1 页用 POST 提交表单，站点会把关键词换成 `/search/{id}/`；后续页直接 GET，
     * 所以每页都先做一次 POST 拿 id（比缓存状态更稳，站点本身没有稳定书单接口）。
     */
    private suspend fun searchPage(page: Int, query: String): List<Content> {
        val doc = webClient.httpPost("https://$domain/search.html", mapOf("searchkey" to query)).parseHtml()
        if (page <= 1) {
            return parseListPage(doc)
        }
        val searchId = doc.select("option[value^=/search/]")
            .mapNotNull { it.attrOrNull("value") }
            .firstNotNullOfOrNull { SEARCH_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: return emptyList()
        return parseListPage(fetchDocument("https://$domain/search/$searchId/$page.html"))
    }

    /** 分类页与搜索页共用 `ul.list > li` 卡片。 */
    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("ul.list > li").mapNotNull { card ->
            // 卡片里`:contains`书名链指向目录 `/450395/`，详情链接是封面外层的 `/shu_450395.html`
            val href = card.selectFirst("a[href^=/shu_]")?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = card.selectFirst("p.bookname a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val cover = card.selectFirst("a img[src]")?.attrOrNull("src")?.toAbsoluteUrl(domain)
            val author = card.selectFirst("p.data a[href^=/author/]")?.textOrNull()?.trim()
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
                state = parseState(card.selectFirst("p.data span.layui-btn")?.textOrNull()),
                authors = author?.let(::setOf).orEmpty(),
                source = source,
            )
        }
    }

    private fun parseState(text: String?): ContentState? = when (text?.trim()) {
        "连载", "连载中" -> ContentState.ONGOING
        "完结", "已完结", "全本" -> ContentState.FINISHED
        else -> null
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val bookId = doc.selectFirst("body[article-id]")?.attrOrNull("article-id")
            ?: manga.url.let { BOOK_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: throw NotFoundException("书海阁：作品不存在或已下架", url)
        val chapters = parseChapters(bookId)
        if (chapters.isEmpty()) {
            throw NotFoundException("书海阁：目录为空，作品可能已下架", url)
        }
        val category = doc.selectFirst("div.detail p a.layui-btn[href]")?.textOrNull()?.trim()
        return manga.copy(
            title = doc.selectFirst("p.name strong")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: manga.title,
            description = parseDescription(doc),
            coverUrl = doc.selectFirst("div.detail img")?.attrOrNull("src")?.toAbsoluteUrl(domain) ?: manga.coverUrl,
            authors = doc.selectFirst("p.author a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(::setOf)
                ?: manga.authors,
            tags = category?.let { setOf(ContentTag(it, it, source)) } ?: manga.tags,
            state = parseState(doc.selectFirst("div.detail p span.layui-btn.layui-btn-normal")?.textOrNull())
                ?: manga.state,
            chapters = chapters,
        )
    }

    internal fun parseDescription(doc: Document): String? =
        doc.selectFirst("div.intro")
            ?.textOrNull()
            ?.trim()
            ?.trimEnd('…', '.', '。', ' ')
            ?.takeIf { it.isNotEmpty() }

    /** 目录按 50 章一页切分，页数从 `select option` 的 `/1_5/` 这类值里取。 */
    private suspend fun parseChapters(bookId: String): List<ContentChapter> {
        val chapters = LinkedHashMap<String, ContentChapter>()
        var page = 1
        while (page <= MAX_CATALOG_PAGES) {
            val pageUrl = "https://$domain/" + if (page == 1) "$bookId/" else "${bookId}_${page}/"
            val doc = runCatching { fetchDocument(pageUrl) }.getOrNull() ?: break
            val pageChapters = parseCatalogPage(doc, bookId)
            if (pageChapters.isEmpty()) {
                break
            }
            for (chapter in pageChapters) {
                chapters.putIfAbsent(chapter.url, chapter)
            }
            val lastPage = catalogLastPage(doc, bookId)
            if (lastPage == null || page >= lastPage) {
                break
            }
            page++
        }
        var last = 0f
        return chapters.values.map { chapter ->
            val number = if (chapter.number > 0f) chapter.number else last + 1
            last = number
            chapter.copy(number = number)
        }
    }

    internal fun parseCatalogPage(doc: Document, bookId: String): List<ContentChapter> {
        return doc.select("ul.read li a[href]").mapNotNull { a ->
            val href = a.attrAsRelativeUrlOrNull("href")
                ?.takeIf { CHAPTER_REGEX.matches(it) && it.startsWith("/$bookId/") }
                ?: return@mapNotNull null
            val title = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ContentChapter(
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
    }

    internal fun catalogLastPage(doc: Document, bookId: String): Int? = doc
        .select("option[value^=/${bookId}_]")
        .mapNotNull { it.attrOrNull("value") }
        .mapNotNull { BOOK_PAGE_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()

    private fun chapterNumber(title: String): Float =
        CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val doc = fetchDocument(chapter.url.toAbsoluteUrl(domain))
        val html = extractChapter(doc) ?: return listOf(createMessagePage("该章节暂无正文"))
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
        ?: throw ParseException("书海阁：正文页缺少内容", page.id.toString())

    /** 正文在 `div.content`；同页还有「大家在看」等推荐区块，不能整页取文本。 */
    internal fun extractChapter(doc: Document): String? {
        val container = doc.selectFirst("div.content") ?: return null
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
        const val LIST_PAGE_SIZE = 20
        const val MAX_CATALOG_PAGES = 200
        const val DEFAULT_CATEGORY = "XuanHuan"

        /** 分类 slug → 名称，取自 `/sort.html` 导航。 */
        val CATEGORIES = listOf(
            "XuanHuan" to "玄幻",
            "QiHuan" to "奇幻",
            "WuXia" to "武侠",
            "DuShi" to "都市",
            "LiShi" to "历史",
            "JunShi" to "军事",
            "XuanYi" to "悬疑",
            "YouXi" to "游戏",
            "KeHuan" to "科幻",
            "TiYu" to "体育",
            "GuYan" to "古言",
            "XianYan" to "现言",
            "HuanYan" to "幻言",
            "XianXia" to "仙侠",
            "ChuanYue" to "穿越",
            "QiTa" to "其他",
        )

        val BOOK_ID_REGEX = Regex("""/shu_(\d+)\.html""")
        val BOOK_PAGE_REGEX = Regex("""^/\d+_(\d+)/$""")
        val SEARCH_ID_REGEX = Regex("""/search/(\d+)/""")
        val CHAPTER_REGEX = Regex("""^/\d+/\d+\.html$""")
        val CHAPTER_NUMBER_REGEX = Regex("""第\s*(\d+(?:\.\d+)?)\s*[章節节回]""")
        val PLACEHOLDER_MARKERS = listOf("正在手打", "本章节内容", "请稍后", "章节内容不完整")
    }
}
