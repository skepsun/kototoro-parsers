package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.Base64
import java.util.EnumSet

/**
 * 落秋中文（luoqiuzw.com）—— 传统笔趣阁模板小说站，全站 UTF-8。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/class/{分类}/{页码}.html`，30 条/页；分类 0 = 全站最近更新，1..7 = 玄幻/修真/都市/历史/网游/科幻/其他。
 *   每条是 `<li>` 加 `span.s1..s5`（分类、书名、最新章节、作者、MM-DD 更新日期）。
 * - 详情：`/book/{id}/`，书名/作者/简介/封面/分类/状态/最新章节全在 `og:*` 与 `og:novel:*` meta 里，
 *   比页面上的 `#info` 文本块稳定得多。
 * - 目录：详情页 `#list dd` 只列**前 99 章**，第 100 章起在 `/chapters/{id}/{页码}.html`（99 章/页）。
 *   末页的「下一页」会绕回第 1 章，必须靠去重收尾，不能只信「有没有下一页」。
 * - 阅读页：`/book/{id}/{章节id}.html`，正文是 `#content` 内一串
 *   `document.writeln(qsbs.bb('<base64 段落>'))`；多页章节依次为 `{id}_1.html`、`{id}_2.html`…
 *   （`_n` 是第 n+1 页），越界的后缀会被站点回落到第 1 页内容。
 *   少量章节（如完本感言）不是 base64 而是纯 `<p>` 明文，另一种是「本章节内容正在手打中」占位页。
 * - 搜索：`/search.html` 无论参数如何都返回同一张固定页（页面上写着空结果），没有可用检索接口，
 *   因此不声明搜索能力。站点也没有排行榜页（/top/、/paihang/ 等均 404），排序只支持按更新时间。
 */
@ContentSourceParser("LUOQIU", "落秋中文", "zh", type = ContentType.NOVEL)
internal class Luoqiu(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.LUOQIU, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("www.luoqiuzw.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = LinkedHashSet<ContentTag>()
        tags += ContentTag("全部", CATEGORY_ALL, source)
        CATEGORIES.forEach { (key, title) -> tags += ContentTag(title, key, source) }
        return ContentListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(ContentTagGroup("分类", tags)),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 站点搜索页恒返回固定占位页，这里不再发请求，直接返回空列表
        if (!filter.query.isNullOrBlank()) {
            return emptyList()
        }
        val category = filter.tags.firstOrNull()?.key
            ?.takeIf { it == CATEGORY_ALL || it in CATEGORIES }
            ?: CATEGORY_ALL
        val url = buildString {
            append("https://").append(domain).append("/class/").append(category).append('/')
            if (page > 1) {
                append(page).append(".html")
            }
        }
        return parseListPage(fetchDocument(url))
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        if (!isBookPage(doc)) {
            throw NotFoundException("落秋中文：作品不存在或已下架（站点返回「索引文件不存在」）", url)
        }
        val details = parseDetails(doc, manga)
        val firstChapters = parseChapterLinks(doc)
        val bookId = bookIdOf(url)
        // 详情页只给前 99 章，99 章整说明还有后续目录页
        val restChapters = if (bookId != null && firstChapters.size >= DETAIL_CHAPTER_LIMIT) {
            fetchChapterPages(bookId, firstChapters)
        } else {
            emptyList()
        }
        return details.copy(chapters = renumber(firstChapters + restChapters))
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val content = getChapterContent(chapter) ?: return listOf(
            createMessagePage("该章节暂无正文（站点提示：本章节内容正在手打中）"),
        )
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = content.html.toDataUrl(),
                preview = null,
                source = source,
            ),
        )
    }

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        val base = chapter.url.removeSuffix(".html")
        if (base == chapter.url) {
            return null
        }
        val parts = ArrayList<String>()
        var suffix = 0 // 0 = 第一页（无后缀），n = 第 n+1 页
        while (suffix <= MAX_CHAPTER_PART) {
            val url = "https://$domain" + if (suffix == 0) "$base.html" else "${base}_$suffix.html"
            val doc = runCatching { fetchDocument(url) }.getOrNull() ?: break
            val part = extractChapterPart(doc, chapter.title) ?: break
            parts += part
            val next = doc.selectFirst(".bottem2 a.next")?.attrOrNull("href") ?: break
            val nextSuffix = partSuffix(next, base) ?: break
            if (nextSuffix != suffix + 1) {
                break
            }
            suffix = nextSuffix
        }
        if (parts.isEmpty()) {
            return null
        }
        return NovelChapterContent(html = buildChapterHtml(chapter.title, parts.joinToString("")))
    }

    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("li").mapNotNull { li ->
            val titleLink = li.selectFirst("span.s2 a[href]") ?: return@mapNotNull null
            val href = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val bookId = bookIdOf(href) ?: return@mapNotNull null
            val title = titleLink.text().trim().ifEmpty { return@mapNotNull null }
            val categoryName = li.selectFirst("span.s1")?.text()?.trim()?.trim('[', ']').orEmpty()
            val author = li.selectFirst("span.s4")?.text()?.trim().orEmpty()
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = sourceContentRating,
                coverUrl = coverUrl(bookId),
                tags = tagForCategoryName(categoryName)?.let { setOf(it) } ?: emptySet(),
                state = null,
                authors = author.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet(),
                source = source,
            )
        }
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        val meta = ogMeta(doc)
        val title = meta["og:novel:book_name"]
            ?.takeIf { it.isNotEmpty() }
            ?: meta["og:title"]?.takeIf { it.isNotEmpty() }
            ?: manga.title
        // #intro 的结构化文本更干净（og:description 里带 <br />），两者内容一致
        val description = (parseIntro(doc) ?: meta["og:description"])?.let { cleanDescription(it) }
        val coverUrl = meta["og:image"]?.takeIf { it.isNotEmpty() }?.toAbsoluteUrl(domain) ?: manga.coverUrl
        val authors = meta["og:novel:author"]
            ?.takeIf { it.isNotEmpty() }
            ?.let { setOf(it) }
            ?: manga.authors
        val tags = meta["og:novel:category"]
            ?.let { tagForCategoryName(it) }
            ?.let { setOf(it) }
            ?: manga.tags
        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = authors,
            tags = tags,
            state = parseState(meta["og:novel:status"]) ?: manga.state,
        )
    }

    /** 目录页 `#list dd a`，详情页与 `/chapters/{id}/{n}.html` 通用 */
    internal fun parseChapterLinks(doc: Document): List<ContentChapter> {
        return doc.select("#list a[href]").mapNotNull { a ->
            val href = a.attrAsRelativeUrlOrNull("href")
                ?.takeIf { CHAPTER_URL_REGEX.matches(it) }
                ?: return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            ContentChapter(
                id = generateUid(href),
                title = title,
                number = 0f,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
    }

    /** 目录页 `<select>` 里给出的最大页码；只有一个目录页时不出现 select，返回 0 */
    internal fun parseChapterPageCount(doc: Document, bookId: String): Int {
        val regex = Regex("^/chapters/$bookId/(\\d+)\\.html$")
        return doc.select("select option[value]")
            .mapNotNull { regex.find(it.attr("value"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
    }

    /** 站点对不存在的书返回 200 + 纯文本「索引文件不存在！」，没有任何 HTML 结构 */
    internal fun isBookPage(doc: Document): Boolean {
        return doc.selectFirst("meta[property=og:novel:book_name]") != null || doc.selectFirst("#list") != null
    }

    /**
     * 取正文页的一段 HTML：优先解 `qsbs.bb()` 里的 base64 段落，否则回退到 `#content` 自身。
     * 占位页（「本章节内容正在手打中」）返回 null，交由调用方给出提示而不是把提示当成正文。
     */
    internal fun extractChapterPart(doc: Document, chapterTitle: String?): String? {
        val container = doc.selectFirst("#content") ?: return null
        val raw = container.html()
        val encoded = BB_SCRIPT_REGEX.findAll(raw).map { it.groupValues[1] }.toList()
        val html = if (encoded.isNotEmpty()) {
            buildString {
                for (chunk in encoded) {
                    append(decodeChunk(chunk) ?: continue)
                }
            }
        } else {
            raw.replace(SCRIPT_TAG_REGEX, "")
        }
        val body = Jsoup.parseBodyFragment(html, doc.baseUri()).body()
        body.select("script, style, ins, iframe, center").remove()
        val plainText = body.text()
        if (plainText.isBlank() || PLACEHOLDER_MARKERS.any { plainText.contains(it) }) {
            return null
        }
        stripRepeatedTitle(body, chapterTitle)
        removeAdParagraphs(body)
        return body.html()
            .replace(EMPTY_PARAGRAPH_REGEX, "")
            .trim()
            .ifEmpty { null }
    }

    private fun decodeChunk(chunk: String): String? = runCatching {
        Base64.getDecoder().decode(chunk).toString(Charsets.UTF_8)
    }.getOrNull()

    /**
     * 站点部分文本自带「第 N 章 标题（第 M 页）」前缀（上游抓取残留），
     * 去掉它避免与渲染出来的标题重复。
     */
    private fun stripRepeatedTitle(body: Element, chapterTitle: String?) {
        val title = chapterTitle?.normalized()?.takeIf { it.isNotEmpty() } ?: return
        val first = body.selectFirst("p") ?: return
        val text = first.text()
        if (text.normalized().length <= title.length || !text.normalized().startsWith(title)) {
            return
        }
        var index = 0
        var matched = 0
        while (index < text.length && matched < title.length) {
            if (text[index].isTitleChar()) {
                matched++
            }
            index++
        }
        var rest = text.substring(index).replaceFirst(PAGE_MARKER_PREFIX_REGEX, "")
        if (rest.isBlank()) {
            first.remove()
            return
        }
        rest = rest.trimStart()
        first.empty()
        first.appendText(rest)
    }

    private fun String.normalized(): String = filter { it.isTitleChar() }

    private fun Char.isTitleChar(): Boolean = !isWhitespace() && this !in TITLE_SKIP_CHARS

    private fun removeAdParagraphs(body: Element) {
        body.select("p").forEach { p ->
            val text = p.text()
            if (AD_MARKERS.any { text.contains(it) }) {
                p.remove()
                return@forEach
            }
            val cleaned = AD_BRACKET_REGEX.replace(text, "").trim()
            if (cleaned != text) {
                if (cleaned.isEmpty()) {
                    p.remove()
                } else {
                    p.empty()
                    p.appendText(cleaned)
                }
            }
        }
    }

    private fun buildChapterHtml(chapterTitle: String?, bodyHtml: String): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
        append("<style>body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;padding:16px;")
        append("padding-bottom:50px;margin:0;line-height:1.9;font-size:1.05rem;}")
        append("img{max-width:100%;height:auto;}p{margin:0 0 1rem;}p:last-child{margin-bottom:0;}")
        append("h1{font-size:1.3rem;margin-bottom:1rem;}</style></head><body>")
        if (!chapterTitle.isNullOrBlank()) {
            append("<h1>").append(chapterTitle.escapeHtml()).append("</h1>")
        }
        append(bodyHtml)
        append("</body></html>")
    }

    private fun renumber(chapters: List<ContentChapter>): List<ContentChapter> {
        return chapters.mapIndexed { index, chapter -> chapter.copy(number = index + 1f) }
    }

    private suspend fun fetchChapterPages(
        bookId: String,
        firstChapters: List<ContentChapter>,
    ): List<ContentChapter> {
        val seen = firstChapters.mapTo(HashSet(firstChapters.size * 2)) { it.url }
        val result = ArrayList<ContentChapter>()
        var maxPage = 0
        var page = 1
        while (page <= MAX_CHAPTER_PAGES) {
            val doc = runCatching {
                fetchDocument("https://$domain/chapters/$bookId/$page.html")
            }.getOrNull() ?: break
            if (maxPage == 0) {
                maxPage = parseChapterPageCount(doc, bookId)
            }
            val fresh = parseChapterLinks(doc).filter { seen.add(it.url) }
            if (fresh.isEmpty()) {
                // 末页的「下一页」会绕回第 1 章，这里就是终点
                break
            }
            result += fresh
            if (maxPage > 0 && page >= maxPage) {
                break
            }
            page++
        }
        return result
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url, getRequestHeaders()).parseHtml()

    private fun partSuffix(href: String, base: String): Int? {
        val match = PART_URL_REGEX(base).find(href) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * 部分作品的简介整段是站点 SEO 文案（「…由作者：X所著，落秋中文网免费提供…全文在线阅读。
     * 三秒记住本站：落秋中文网 网址：www.luoqiuzw.com」），把其中的广告句去掉，只保留作品介绍。
     */
    private fun cleanDescription(raw: String): String? {
        val sentences = raw.replace('\u00A0', ' ').split(SENTENCE_SPLIT_REGEX)
        val kept = sentences.filterNot { sentence -> DESCRIPTION_AD_MARKERS.any { sentence.contains(it) } }
        return kept.joinToString("")
            .replace(AD_BRACKET_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun parseIntro(doc: Document): String? {
        return doc.selectFirst("#intro > div")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun ogMeta(doc: Document): Map<String, String> = doc.select("meta[property]").associate {
        it.attr("property").lowercase() to it.attr("content").trim()
    }

    private fun parseState(status: String?): ContentState? = when {
        status == null || status.isEmpty() -> null
        status.contains("完") -> ContentState.FINISHED
        status.contains("暂停") -> ContentState.PAUSED
        status.contains("连载") || status.contains("中") -> ContentState.ONGOING
        else -> null
    }

    private fun bookIdOf(url: String): String? = BOOK_ID_REGEX.find(url)?.groupValues?.get(1)

    private fun coverUrl(bookId: String): String = "https://$domain/img/$bookId.jpg"

    private fun tagForCategoryName(name: String): ContentTag? {
        val title = name.trim()
        if (title.isEmpty()) {
            return null
        }
        val key = CATEGORIES.entries.firstOrNull { it.value == title }?.key
        return ContentTag(title, key ?: title, source)
    }

    private fun createMessagePage(message: String): ContentPage {
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<style>body{font-family:sans-serif;padding:16px;line-height:1.7;}</style>")
            append("</head><body><p>").append(message.escapeHtml()).append("</p></body></html>")
        }
        return ContentPage(
            id = generateUid(message),
            url = html.toDataUrl(),
            preview = null,
            source = source,
        )
    }

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {

        const val CATEGORY_ALL = "0"
        const val DETAIL_CHAPTER_LIMIT = 99
        const val MAX_CHAPTER_PAGES = 60
        const val MAX_CHAPTER_PART = 40

        val CATEGORIES: Map<String, String> = linkedMapOf(
            "1" to "玄幻小说",
            "2" to "修真小说",
            "3" to "都市小说",
            "4" to "历史小说",
            "5" to "网游小说",
            "6" to "科幻小说",
            "7" to "其他小说",
        )

        val BOOK_ID_REGEX = Regex("/book/(\\d+)/")
        val CHAPTER_URL_REGEX = Regex("^/book/\\d+/\\d+\\.html$")
        val BB_SCRIPT_REGEX = Regex("""qsbs\.bb\('([^']*)'\)""")
        val SCRIPT_TAG_REGEX = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val EMPTY_PARAGRAPH_REGEX = Regex("""<p>\s*</p>""")
        val PAGE_MARKER_PREFIX_REGEX = Regex("""^[（(]第\d+页[）)]""")
        val TITLE_SKIP_CHARS = charArrayOf('，', ',', '、', '：', ':', '。', '.')

        val AD_MARKERS = listOf(
            "本章节内容正在手打中",
            "天才一秒记住本站地址",
            "一秒记住本站地址",
        )

        val AD_BRACKET_REGEX = Regex(
            """\[[^\[\]]*\.[a-zA-Z]{2,6}[^\[\]]*]""",
            RegexOption.IGNORE_CASE,
        )

        val SENTENCE_SPLIT_REGEX = Regex("(?<=[。！？!?；;])")
        val WHITESPACE_REGEX = Regex("\\s+")

        val DESCRIPTION_AD_MARKERS = listOf(
            "记住本站",
            "网址：",
            "网址:",
            "luoqiuzw",
        )

        val PLACEHOLDER_MARKERS = listOf(
            "本章节内容正在手打中",
            "稍后刷新页面看是否已经更新",
        )

        fun PART_URL_REGEX(base: String): Regex = Regex("^${Regex.escape(base)}_(\\d+)\\.html$")
    }
}
