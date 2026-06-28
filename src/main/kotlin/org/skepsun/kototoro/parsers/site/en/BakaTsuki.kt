package org.skepsun.kototoro.parsers.site.en

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
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("BAKATSUKI", "BakaTsuki", "en", type = ContentType.NOVEL)
internal class BakaTsuki(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.valueOf("BAKATSUKI"), pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("www.baka-tsuki.org")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableTags = buildFilterTags(),
    )

    private fun buildFilterTags(): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        tags += ContentTag("Light Novel", "light-novel", source)
        tags += ContentTag("Web Novel", "web-novel", source)
        tags += ContentTag("Teasers", "teasers", source)
        tags += ContentTag("Alternative Language", "alt-language", source)
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val q = filter.query?.urlEncoded() ?: ""
        if (q.isNotEmpty()) {
            val offset = (page - 1) * pageSize
            return "https://$domain/project/index.php?title=Special:Search&search=$q&offset=$offset"
        }
        val tag = filter.tags.firstOrNull()?.key ?: "light-novel"
        val category = when (tag) {
            "light-novel" -> "Light_novel_(English)"
            "web-novel" -> "Web_novel"
            "teasers" -> "Teasers_(English)"
            "alt-language" -> "Alternative_Language"
            else -> "Light_novel_(English)"
        }
        return "https://$domain/project/index.php?title=Category:$category"
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        val container = doc.selectFirst("#mw-content-text, .mw-search-results, .mw-category") ?: return items
        for (a in container.select("a[href*=/project/]")) {
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrlOrNull(domain) ?: continue
            if (absoluteUrl.contains("Special:") || absoluteUrl.contains("Category:") ||
                absoluteUrl.contains("Talk:") || absoluteUrl.contains("User:") ||
                absoluteUrl.contains("File:") || absoluteUrl.contains("Help:")) continue
            if (!seen.add(absoluteUrl)) continue
            val title = a.attr("title").trim().ifEmpty { a.text().trim() }.ifEmpty { continue }
            val img = a.parent()?.selectFirst("img") ?: a.selectFirst("img")
            val thumb = img?.attr("src")?.toAbsoluteUrlOrNull(domain)
                ?: img?.attr("data-src")?.toAbsoluteUrlOrNull(domain)
            items += Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain"),
                publicUrl = absoluteUrl,
                title = title,
                altTitles = emptySet(),
                coverUrl = thumb,
                largeCoverUrl = thumb,
                authors = emptySet(),
                tags = emptySet(),
                description = null,
                rating = RATING_UNKNOWN,
                contentRating = null,
                state = null,
                source = source,
            )
        }
        return items
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=og:title]")?.attr("content")?.trim()
            ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("#mw-content-text .infobox img, #mw-content-text .thumb img, #mw-content-text img")?.attr("src")
            ?: manga.coverUrl
        val coverUrl = cover?.toAbsoluteUrlOrNull(domain)
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=og:description]")?.attr("content")?.trim()
        val contentEl = doc.selectFirst("#mw-content-text") ?: doc.body()
        val chapters = parseChapters(contentEl)
        return manga.copy(
            title = title,
            description = desc ?: manga.description,
            coverUrl = coverUrl ?: manga.coverUrl,
            largeCoverUrl = coverUrl ?: manga.largeCoverUrl,
            chapters = chapters,
        )
    }

    private fun parseChapters(contentEl: org.jsoup.nodes.Element): List<ContentChapter> {
        val chapters = ArrayList<ContentChapter>()
        for (a in contentEl.select("a[href*=/project/]")) {
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
            if (href.contains("Special:") || href.contains("Category:") ||
                href.contains("Talk:") || href.contains("User:") ||
                href.contains("File:") || href.contains("Help:") ||
                href.contains("action=") || href.contains("redlink=1")) continue
            val chapterUrl = href.toAbsoluteUrlOrNull(domain) ?: continue
            val title = a.text().trim().ifEmpty { continue }
            chapters += ContentChapter(
                id = generateUid(chapterUrl),
                title = title,
                number = (chapters.size + 1).toFloat(),
                volume = 0,
                url = chapterUrl.removePrefix("https://$domain"),
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }
        return chapters
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val contentEl = doc.selectFirst("#mw-content-text .mw-parser-output, #mw-content-text, .chapter-content, .text, .entry-content, #content")
            ?: return listOf(createErrorPage("Content not found"))
        contentEl.select(".mw-editsection, .toc, #toc, .navbox, script, style").remove()
        val contentHtml = wrapChapterHtml(chapter.title ?: "", contentEl.html())
        val dataUrl = toDataUrl(contentHtml)
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = dataUrl,
                preview = null,
                source = source,
            )
        )
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    override fun getRequestHeaders() = Headers.Builder()
        .add("User-Agent", context.getDefaultUserAgent())
        .add("Referer", "https://$domain/")
        .build()

    private fun wrapChapterHtml(title: String, contentHtml: String): String {
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n")
            append("<style>\n")
            append("body{font-family:\"Noto Serif\",Georgia,sans-serif;")
            append("padding:16px;margin:0;line-height:1.9;font-size:1.05rem;}\n")
            append("h1{font-size:1.3rem;margin-bottom:1rem;}\n")
            append("p{margin:0 0 1rem;}\n")
            append("</style>\n</head>\n<body>\n")
            if (title.isNotEmpty()) {
                append("<h1>").append(title).append("</h1>\n")
            }
            append(contentHtml)
            append("</body>\n</html>")
        }
    }

    private fun createErrorPage(message: String): ContentPage {
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<style>body{font-family:sans-serif;padding:16px;}</style>")
            append("</head><body><h1>Error</h1><p>$message</p></body></html>")
        }
        return ContentPage(
            id = generateUid(message),
            url = toDataUrl(html),
            preview = null,
            source = source,
        )
    }

    private fun toDataUrl(html: String): String {
        val encoded = context.encodeBase64(html.toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun String.toAbsoluteUrl(domain: String): String {
        return if (startsWith("http")) this else "https://$domain${if (startsWith("/")) this else "/$this"}"
    }
}
