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
 * 笔趣阁55（www.biquge55.net）—— 免费网络小说站，服务端渲染的经典「笔趣阁」模板。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/xiaoshuo/{分类}_{页码}/`（60 部/页），分类为 xuanhuan / wuxia / doushi / chuanyue / kehuan / qita；
 *   卡片是 `div.item`：`div.image img[src]` 封面、`dl dt span` 作者、`dl dt a` 书名，默认按更新时间排序。
 *   完本列表 `/quanben/xiaoshuo/{页码}/` 结构相同，这里不单独暴露成排序。
 * - 搜索：`/search/?q=` 返回一张没有任何结果的空页（`/search.php` 直接 404），所以不声明搜索能力。
 * - 详情：`/book/info{id}/`，书名 / 作者 / 分类 / 连载状态 / 封面都在 `og:novel:*` 里，简介在 `div.desc`；
 *   完整目录在 `#list`（一次性给全，3400+ 章也不分页），**新章在前**，倒序后返回。
 * - 正文：`/book/info{id}/{章节id}.html`，正文在 `#content > p`；长章节被切成多页
 *   （`{章节id}_2.html`、`_3.html`，页内「下一页」链接串联），这里按「下一页」一路拼成完整一章。
 *   同页另有一个没有 id 的 `div.content` 只放「本站最新网址」广告，不能误当正文。
 */
@ContentSourceParser("BIQUGE55", "笔趣阁55", "zh", type = ContentType.NOVEL)
internal class Biquge55(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.BIQUGE55, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.biquge55.net")

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
        val url = "https://$domain/xiaoshuo/$category" + if (page > 1) "_$page/" else "_1/"
        val doc = fetchDocument(url)
        val items = parseListPage(doc)
        if (items.isEmpty() && page == 1) {
            throw NotFoundException("笔趣阁55：分类列表为空，站点结构可能已变化", url)
        }
        return items
    }

    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("div.item").mapNotNull { item ->
            val titleLink = item.selectFirst("dl dt a[href*=/book/info]") ?: return@mapNotNull null
            val href = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = titleLink.text().trim().ifEmpty { return@mapNotNull null }
            val cover = item.selectFirst("div.image img[src]")
                ?.attrOrNull("src")
                ?.toAbsoluteUrl(domain)
                ?.takeUnless { coverUrl -> coverUrl.contains("nocover") }
            val author: String = item.selectFirst("dl dt span")?.textOrNull()?.trim().orEmpty()
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
                authors = if (author.isEmpty()) emptySet() else setOf(author),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        if (doc.selectFirst("meta[property=og:novel:book_name]") == null) {
            throw NotFoundException("笔趣阁55：作品不存在或已下架", url)
        }
        val meta = doc.ogMeta()
        val chapters = parseChapterLinks(doc)
        if (chapters.isEmpty()) {
            throw NotFoundException("笔趣阁55：目录为空，作品可能已下架", url)
        }
        val category = meta["og:novel:category"]?.takeIf { it.isNotBlank() }
        return manga.copy(
            title = meta["og:novel:book_name"]?.takeIf { it.isNotBlank() } ?: manga.title,
            description = parseDescription(doc) ?: meta["og:description"],
            coverUrl = meta["og:image"]?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain) ?: manga.coverUrl,
            authors = meta["og:novel:author"]?.takeIf { it.isNotBlank() }?.let(::setOf) ?: manga.authors,
            tags = category?.let { setOf(ContentTag(it, it, source)) } ?: manga.tags,
            state = when (meta["og:novel:status"]?.trim()) {
                "连载", "连载中" -> ContentState.ONGOING
                "完结", "已完结", "完本" -> ContentState.FINISHED
                else -> manga.state
            },
            chapters = chapters,
        )
    }

    /** 简介在 `div.desc`（PC 隐藏、移动端显示，HTML 里是全文），末尾常带省略号占位。 */
    internal fun parseDescription(doc: Document): String? =
        doc.selectFirst("div.desc")
            ?.textOrNull()
            ?.trim()
            ?.trimEnd('…', '.', '。', ' ')
            ?.takeIf { it.isNotEmpty() }

    /** `#list` 里一次性给全目录（3400+ 章也不分页），顺序是从第 1 章到最新章，按原顺序返回。 */
    internal fun parseChapterLinks(doc: Document): List<ContentChapter> {
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<ContentChapter>()
        for (a in doc.select("#list a[href]")) {
            val href = a.attrAsRelativeUrlOrNull("href")
                ?.takeIf { CHAPTER_REGEX.matches(it) }
                ?: continue
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

    /** 正文是 data URL，不能像图片那样用域名补全（会变成 `https://域名/data:text/...`）。 */
    override suspend fun getPageUrl(page: ContentPage): String = page.url
        ?: throw ParseException("笔趣阁55：正文页缺少内容", page.id.toString())

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        val base = chapter.url.removeSuffix(".html")
        if (base == chapter.url) {
            return null
        }
        val parts = ArrayList<String>()
        var suffix = 0
        while (suffix < MAX_CHAPTER_PARTS) {
            val doc = runCatching { fetchDocument(chapterPartUrl(base, suffix)) }.getOrNull() ?: break
            parts += extractChapterPart(doc) ?: break
            suffix = nextPartSuffix(doc, base) ?: break
        }
        if (parts.isEmpty()) {
            return null
        }
        return NovelChapterContent(html = buildChapterHtml(chapter.title, parts.joinToString("")))
    }

    /** 第 1 页是 `{章节id}.html`，之后是 `{章节id}_2.html`、`_3.html`…… */
    internal fun chapterPartUrl(base: String, suffix: Int): String =
        "https://$domain" + if (suffix == 0) "$base.html" else "${base}_${suffix + 1}.html"

    /** 页内「下一页」指向同一章的下一分页时返回下一个 suffix，否则返回 null 表示本章结束。 */
    internal fun nextPartSuffix(doc: Document, base: String): Int? {
        val next = doc.selectFirst("a:containsOwn(下一页)")?.attrOrNull("href")?.toAbsoluteUrl(domain)
            ?: return null
        val chapterId = base.substringAfterLast('/')
        val match = Regex("""/$chapterId(?:_(\d+))?\.html$""").find(next) ?: return null
        // `_2.html` 是第 2 页，对应 suffix 1；第 1 页没有下标，只有本书下一章才会命中不到
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.minus(1) ?: return null
    }

    /** 正文容器是 `#content`（没有 id 的 `div.content` 是广告），占位提示返回 null。 */
    internal fun extractChapterPart(doc: Document): String? {
        val container = doc.selectFirst("div#content") ?: return null
        val body = Jsoup.parseBodyFragment(container.html(), doc.baseUri()).body()
        body.select("script, style, ins, iframe, a").remove()
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
        append("p{margin:0 0 1rem;}p:last-child{margin-bottom:0;}")
        append("h1{font-size:1.3rem;margin-bottom:1rem;}</style></head><body>")
        if (!chapterTitle.isNullOrBlank()) {
            append("<h1>").append(chapterTitle.escapeHtml()).append("</h1>")
        }
        append(bodyHtml)
        append("</body></html>")
    }

    private fun createMessagePage(message: String): ContentPage = ContentPage(
        id = generateUid(message),
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
        const val LIST_PAGE_SIZE = 60
        const val DEFAULT_CATEGORY = "xuanhuan"
        const val MAX_CHAPTER_PARTS = 10

        val CATEGORIES = listOf(
            "xuanhuan" to "玄幻",
            "wuxia" to "武侠",
            "doushi" to "都市",
            "chuanyue" to "穿越",
            "kehuan" to "科幻",
            "qita" to "其它",
        )

        val CHAPTER_REGEX = Regex("""^/book/info\d+/\d+(?:_\d+)?\.html$""")
        val CHAPTER_NUMBER_REGEX = Regex("""第\s*(\d+(?:\.\d+)?)\s*[章節节回]""")
        val PLACEHOLDER_MARKERS = listOf("正在手打中", "本章节内容正在手打中", "内容加载中")
    }
}
