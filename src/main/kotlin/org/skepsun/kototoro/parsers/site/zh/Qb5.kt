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
 * 全本小说网（www.qb5.io）—— 免费全本小说站，服务端渲染的「书库 + 目录 + 在线阅读」三段式模板。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/shuku-{分类id}.html`，第 2 页起 `/shuku-{分类id}-{页码}.html`；每个分类页是一组
 *   `dl.hot > dd`，`h4 a` 书名、`a img` 封面、`p:contains(作者) > span` 作者。
 * - 详情：`/xs-{id}.html`，书名 / 作者 / 分类 / 状态在 `og:novel:*`，简介取自 `og:description`
 *   （站点把简介截断成一行并加省略号，这是详情页唯一稳定的简介来源）。
 * - 目录：`read_url` 指向的 `/xs-{id}/`，`ul.list li a` 一次性给全（近 4000 章也不分页），正序。
 * - 正文：`/xs-{id}/du-{序号}.html`，正文在 `div.articlebody#content`（首个 `h1` 是章节标题，
 *   正文是 `<p>`）；页脚「下一页」指向的是**下一章**而不是本章分页，所以一章只取一页。
 * - 搜索：`/?c=xs&a=sou&q=` 返回空白页（实测 0 字节），不声明搜索能力。
 */
@ContentSourceParser("QB5IO", "全本小说网 (qb5)", "zh", type = ContentType.NOVEL)
internal class Qb5(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.QB5IO, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.qb5.io")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = false,
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
        val category = filter.tags.firstOrNull()?.key?.takeIf { tag -> CATEGORIES.any { it.first == tag } }
            ?: DEFAULT_CATEGORY
        val url = "https://$domain/shuku-$category" + if (page > 1) "-$page.html" else ".html"
        val items = parseListPage(fetchDocument(url))
        if (items.isEmpty() && page == 1) {
            throw NotFoundException("全本小说网(qb5)：分类列表为空，站点结构可能已变化", url)
        }
        return items
    }

    /** 列表页是若干个 `dl.hot`，卡片是其中的 `dd`。 */
    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("dl.hot dd").mapNotNull { card ->
            val titleLink = card.selectFirst("h4 a[href*=/xs-]") ?: return@mapNotNull null
            val href = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = titleLink.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cover = card.selectFirst("a img[src]")?.attrOrNull("src")?.toAbsoluteUrl(domain)
            val author = card.selectFirst("p:contains(作者) span")?.textOrNull()?.trim()
                ?.removePrefix("作者:")?.trim()
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
        if (doc.selectFirst("meta[property=og:novel:book_name]") == null) {
            throw NotFoundException("全本小说网(qb5)：作品不存在或已下架", url)
        }
        val meta = doc.ogMeta()
        val catalogUrl = meta["og:novel:read_url"]?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain)
            ?: url.substringBeforeLast('.').let { "$it/" }
        val chapters = parseChapterLinks(fetchDocument(catalogUrl))
        if (chapters.isEmpty()) {
            throw NotFoundException("全本小说网(qb5)：目录为空，作品可能已下架", catalogUrl)
        }
        val category = meta["og:novel:category"]?.takeIf { it.isNotBlank() }
        return manga.copy(
            title = meta["og:novel:book_name"]?.takeIf { it.isNotBlank() } ?: manga.title,
            description = cleanDescription(meta["og:description"]),
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

    /**
     * 详情页没有独立的简介块，只有 `og:description`，且它带「《书名》全本小说,作者:XXX,」前缀，
     * 去掉前缀和首位多余的标点，只留剧情概要。
     */
    internal fun cleanDescription(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = text.replace(DESCRIPTION_PREFIX_REGEX, "")
            .trimStart(':', '：', ',', '，', ' ')
            .trim()
        return body.takeIf { it.isNotEmpty() } ?: text
    }

    /** 目录页 `/xs-{id}/` 的 `ul.list` 就是全量章节，正序，按章节号补编号。 */
    internal fun parseChapterLinks(doc: Document): List<ContentChapter> {
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<ContentChapter>()
        for (a in doc.select("ul.list li a[href]")) {
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

    /** 正文是 data URL，不能用域名补全（会拼成 `https://域名/data:text/...`）。 */
    override suspend fun getPageUrl(page: ContentPage): String = page.url
        ?: throw ParseException("全本小说网(qb5)：正文页缺少内容", page.id.toString())

    /** `div.articlebody#content` 里第一个 `h1` 是章节标题，其余 `<p>` 是正文。 */
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
        /** 分类页每页 12 部（2026-11 实测，第 1/2 页都是 12 条）。 */
        const val LIST_PAGE_SIZE = 12
        const val DEFAULT_CATEGORY = "1"

        /** 分类 id → 名称，取自首页 `/shuku-{id}.html` 导航。 */
        val CATEGORIES = listOf(
            "1" to "奇幻玄幻",
            "2" to "武侠仙侠",
            "3" to "都市官场",
            "4" to "历史军事",
            "5" to "言情穿越",
            "6" to "灵异悬疑",
            "7" to "游戏竞技",
            "8" to "青春耽美",
            "9" to "科幻同人",
            "10" to "经典畅销",
            "11" to "图书杂志",
            "0" to "其它分类",
        )

        val CHAPTER_REGEX = Regex("""^/xs-\d+/du-\d+\.html$""")
        val CHAPTER_NUMBER_REGEX = Regex("""第\s*(\d+(?:\.\d+)?)\s*章""")
        val DESCRIPTION_PREFIX_REGEX = Regex("""^《[^》]*》[^,，]*[,，]?\s*作者[:：][^,，]*[,，]?\s*""")
        val PLACEHOLDER_MARKERS = listOf("正在手打", "本章节内容", "请稍后", "章节内容不完整")
    }
}
