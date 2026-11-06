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
 * SF轻小说（book.sfacg.com）—— 原创轻小说站，书库、详情、目录、正文全部服务端渲染。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/List/?tid={类别}&if={进度}&PageIndex={页码}`，20 部/页。分页参数是 `PageIndex`
 *   （`page=2` 会被忽略、始终返回第 1 页，站内 `redirect()` JS 也只在改筛选时删掉 `PageIndex`）。
 *   卡片是 `ul.Comic_Pic_List`：`li.Conjunction img[alt]` 封面、`strong a[href=/Novel/{id}/]` 书名、
 *   `a[href^=/Club/]` 作者、`span.font_red` 评分（10 分制）、`a[href^=/List/?tid=]` 类别，最后一段是简介。
 * - 搜索：站内搜索框跳的是 `http://s.sfacg.com/?Key={关键字}&S=1&SS=`（`S=1` 才是小说结果，
 *   `S=2` 是漫画且常为空）；结果页是另一套 `ul > li.Conjunction` 结构，且**不支持分页**。
 * - 详情：`/Novel/{id}/`，书名 `h1.title span.text`、封面 `.summary-pic img`、作者 `.author-name span`、
 *   `类型：/字数：[连载中]/人气：` 在 `.count-detail .text-row span.text`、简介 `p.introduce`、
 *   标签 `ul.tag-list li.tag a span.text`。
 * - 目录：`/Novel/{id}/MainIndex/`，按卷分组：`div.story-catalog` 的 `h3.catalog-title` 是卷名，
 *   章在 `div.catalog-list li a[href=/Novel/{id}/{卷id}/{章id}/]`；整本一次给全（几百到上千章）。
 * - 正文：章节页 `div#ChapterBody`（`class="article-content font16"`）内的 `<p>`。付费/VIP 章节
 *   这里给的是占位或提示，解析不到正文时返回提示页，不做任何绕过。
 */
@ContentSourceParser("SFACG", "SF轻小说", "zh", type = ContentType.NOVEL)
internal class Sfacg(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.SFACG, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("book.sfacg.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genres = CATEGORIES.map { (tid, title) -> ContentTag(title, "tid:$tid", source) }.toSet()
        val progress = PROGRESS.map { (value, title) -> ContentTag(title, "if:$value", source) }.toSet()
        return ContentListFilterOptions(
            availableTags = genres + progress,
            tagGroups = listOf(
                ContentTagGroup("类别", genres),
                ContentTagGroup("写作进度", progress),
            ),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        // 站点按 UA 下发移动版（详情页 12 KB、目录只剩章节名），必须显式要桌面版
        .set("User-Agent", DESKTOP_UA)
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            // 站内搜索只有一页，第 2 页起直接返回空，让宿主停止翻页
            if (page > 1) return emptyList()
            return parseSearchPage(fetchDocument("http://s.sfacg.com/?Key=${query.urlEncoded()}&S=1&SS="))
        }
        val tid = filter.tags.firstNotNullOfOrNull { TAG_TID_REGEX.matchEntire(it.key)?.groupValues?.get(1) }
        val progress = filter.tags.firstNotNullOfOrNull { TAG_IF_REGEX.matchEntire(it.key)?.groupValues?.get(1) }
        // 不带筛选时必须是 `/List/`：`/List/?tid=0` 会 404
        val params = buildList {
            if (tid != null) add("tid=$tid")
            if (progress != null) add("if=$progress")
            if (page > 1) add("PageIndex=$page")
        }
        val url = "https://$domain/List/" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val doc = fetchDocument(url)
        val items = parseListPage(doc)
        if (items.isEmpty() && page == 1) {
            throw NotFoundException("SF轻小说：书库列表为空，站点结构可能已变化", url)
        }
        return items
    }

    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("ul.Comic_Pic_List").mapNotNull { block ->
            val href = block.selectFirst("strong a[href^=/Novel/]")?.attrAsRelativeUrlOrNull("href")
                ?: return@mapNotNull null
            val title = block.selectFirst("strong a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val cover = block.selectFirst("li.Conjunction img")?.attrOrNull("src")?.toAbsoluteUrl(domain)
            val author = block.selectFirst("a[href^=/Club/]")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val rating = block.selectFirst("span.font_red")?.textOrNull()?.trim()
                ?.let { RATING_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
                ?.div(10f)
                ?.takeIf { it in 0f..1f }
            val genre = block.selectFirst("a[href^=/List/?tid=]")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = rating ?: RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = genre?.let { setOf(ContentTag(it, it, source)) }.orEmpty(),
                state = parseState(block.selectFirst("li:not(.Conjunction)")?.textOrNull()),
                authors = author?.let(::setOf).orEmpty(),
                source = source,
                description = parseCardDescription(block),
            )
        }
    }

    /** 搜索结果是另一套结构：`ul > li.Conjunction`（封面没有 a 包裹），书名在 `strong.F14PX a`。 */
    internal fun parseSearchPage(doc: Document): List<Content> {
        return doc.select("li.Conjunction").mapNotNull { coverCell ->
            val block = coverCell.parent() ?: return@mapNotNull null
            val link = block.selectFirst("strong a[href*=Novel]") ?: return@mapNotNull null
            val title = link.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val href = link.attrOrNull("href")?.let { NOVEL_ID_REGEX.find(it)?.groupValues?.get(1) }
                ?.let { "/Novel/$it/" }
                ?: return@mapNotNull null
            val cover = coverCell.selectFirst("img")?.attrOrNull("src")?.toAbsoluteUrl(domain)
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
                authors = emptySet(),
                source = source,
                description = parseCardDescription(block),
            )
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val bookId = NOVEL_ID_REGEX.find(url)?.groupValues?.get(1)
            ?: throw NotFoundException("SF轻小说：无法从地址解析作品 id", url)
        // 新书可能一章都没发，目录为空时照常返回详情（章节列表为空），不要当成下架
        val chapters = parseChapters(fetchDocument("https://$domain/Novel/$bookId/MainIndex/"), bookId)
        val stats = doc.select(".count-detail .text-row span.text").mapNotNull { it.textOrNull()?.trim() }
        val title = doc.selectFirst("h1.title span.text")?.clone()?.also { it.select("span.tag").remove() }
            ?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.title
        return manga.copy(
            title = title,
            description = doc.selectFirst("p.introduce")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: manga.description,
            coverUrl = doc.selectFirst(".summary-pic img")?.attrOrNull("src")?.toAbsoluteUrl(domain)
                ?: manga.coverUrl,
            authors = doc.selectFirst(".author-name span")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::setOf) ?: manga.authors,
            tags = parseDetailTags(doc).ifEmpty { manga.tags },
            state = parseState(stats.firstOrNull { it.contains("字数") }),
            chapters = chapters,
        )
    }

    internal fun parseDetailTags(doc: Document): Set<ContentTag> = doc
        .select("ul.tag-list li.tag a[href^=/stag/] span.text")
        .mapNotNull { it.textOrNull()?.trim()?.takeIf { name -> name.isNotEmpty() } }
        .mapTo(HashSet()) { ContentTag(it, it, source) }

    /** 目录按卷分组，卷名形如「【书名】 第一卷 序篇 少女在异界的日子」，去掉前缀书名更清爽。 */
    internal fun parseChapters(doc: Document, bookId: String): List<ContentChapter> {
        val chapters = ArrayList<ContentChapter>()
        doc.select("div.story-catalog").forEach { section ->
            val volumeTitle = section.selectFirst("h3.catalog-title")?.textOrNull()?.trim()
                ?.substringAfterLast("】")?.trim()
                ?.takeIf { it.isNotEmpty() }
            section.select("div.catalog-list li a[href^=/Novel/$bookId/]").forEach { a ->
                val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
                val title = a.attrOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                chapters += ContentChapter(
                    id = generateUid(href),
                    title = title,
                    number = (chapters.size + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = volumeTitle,
                    source = source,
                )
            }
        }
        return chapters
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val html = extractChapter(doc) ?: return listOf(
            createMessagePage("该章节暂无可读正文（可能是付费/VIP 章节，需在 SF轻小说 官方订阅后阅读）"),
        )
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
        ?: throw ParseException("SF轻小说：正文页缺少内容", page.id.toString())

    /** 正文在 `div#ChapterBody`；页面其余部分是推荐位，不能整页取文本。 */
    internal fun extractChapter(doc: Document): String? {
        val container = doc.selectFirst("div#ChapterBody") ?: return null
        val body = Jsoup.parseBodyFragment(container.html(), doc.baseUri()).body()
        body.select("script, style, ins, iframe, a, span, div.ads, div.vip-tip").remove()
        val text = body.text()
        if (text.isBlank() || PAYWALL_MARKERS.any { text.contains(it) }) {
            return null
        }
        return body.html().trim().ifEmpty { null }
    }

    /**
     * 卡片下半部分是「书名 / 作者 / 综合信息：评分 / 类别 / 日期 / 字数简介」几段 `<br/>` 分隔的文本，
     * 最后一段是简介。用 HTML 切分比正则安全，也能避开书名里出现的「字」。
     */
    private fun parseCardDescription(block: org.jsoup.nodes.Element): String? {
        val cell = block.selectFirst("li:not(.Conjunction)") ?: return null
        val lastSegment = cell.html().split(BR_TAG_REGEX).lastOrNull()
            ?: return null
        val text = Jsoup.parseBodyFragment(lastSegment).body().text().trim()
        return text.takeIf { it.length > 8 }
    }

    private fun parseState(text: String?): ContentState? {
        val value = text ?: return null
        return when {
            value.contains("已完结") || value.contains("完结") -> ContentState.FINISHED
            value.contains("连载") -> ContentState.ONGOING
            else -> null
        }
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

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private companion object {
        const val LIST_PAGE_SIZE = 20

        const val DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

        /** 书库左侧「作品类别」全部 9 项（列表页 `#typeMenu` 的 `redirect('tid', N)`）。 */
        val CATEGORIES = listOf(
            21 to "魔幻",
            22 to "玄幻",
            23 to "古风",
            24 to "科幻",
            25 to "校园",
            26 to "都市",
            27 to "游戏",
            28 to "同人",
            29 to "悬疑",
        )

        /** `#finishMenu` 的写作进度筛选。 */
        val PROGRESS = listOf(
            0 to "连载中",
            1 to "已完结",
        )

        val TAG_TID_REGEX = Regex("""tid:(\d+)""")
        val TAG_IF_REGEX = Regex("""if:(\d+)""")
        val NOVEL_ID_REGEX = Regex("""/Novel/(\d+)""")
        val RATING_REGEX = Regex("""([\d.]+)\s*分""")
        val BR_TAG_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val PAYWALL_MARKERS = listOf("订阅后", "购买本章", "成为VIP", "登录后阅读")
    }
}
