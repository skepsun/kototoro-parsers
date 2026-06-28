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
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.ArrayList
import java.util.Base64
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("ROYALROAD", "RoyalRoad", "en", type = ContentType.NOVEL)
internal class RoyalRoad(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ROYALROAD, pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("www.royalroad.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableTags = buildFilterTags(),
        )
    }

    private fun buildFilterTags(): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        tags += ContentTag("Best Rated", "best-rated", source)
        tags += ContentTag("Trending", "trending", source)
        tags += ContentTag("Popular This Week", "popular-week", source)
        tags += ContentTag("Active Only", "active-only", source)
        tags += ContentTag("Complete", "complete", source)
        tags += ContentTag("Latest Updates", "latest-updates", source)
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query.orEmpty()
        val tag = filter.tags.firstOrNull()?.key ?: "latest-updates"

        val url = if (query.isNotBlank()) {
            "https://$domain/fictions/search?page=$page&q=${query.urlEncoded()}"
        } else {
            "https://$domain/fictions/$tag?page=$page"
        }

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    private fun parseList(doc: Document): List<Content> {
        val result = ArrayList<Content>()
        val items = doc.select(".fiction-list-item")
        for (item in items) {
            val link = item.selectFirst("h2.fiction-title a") ?: continue
            val href = link.attr("href")
            val title = link.text().trim()
            if (title.isEmpty()) continue

            val coverEl = item.selectFirst("img")
            val coverUrl = coverEl?.attr("src")?.toAbsoluteUrlOrNull(domain)

            val authorEl = item.selectFirst(".author a")
            val author = authorEl?.text()?.trim() ?: ""

            val descEl = item.selectFirst(".description")
            val description = descEl?.text()?.trim()

            val tagsEl = item.select(".tags .tag")
            val tags = tagsEl.mapNotNull { t ->
                val tagName = t.text().trim()
                if (tagName.isNotEmpty()) ContentTag(tagName, tagName.lowercase(), source) else null
            }.toSet()

            result += Content(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
                tags = tags,
                description = description,
                rating = RATING_UNKNOWN,
                contentRating = null,
                state = null,
                source = source,
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title

        val coverEl = doc.selectFirst("img.fiction-cover")
        val coverUrl = coverEl?.attr("src")?.toAbsoluteUrlOrNull(domain) ?: manga.coverUrl

        val authorEl = doc.selectFirst(".author a")
        val author = authorEl?.text()?.trim() ?: ""

        val descEl = doc.selectFirst(".description")
        val description = descEl?.text()?.trim() ?: manga.description

        val tagsEl = doc.select(".tags .tag")
        val tags = tagsEl.mapNotNull { t ->
            val tagName = t.text().trim()
            if (tagName.isNotEmpty()) ContentTag(tagName, tagName.lowercase(), source) else null
        }.toSet()

        val chapters = ArrayList<ContentChapter>()
        val chapterRows = doc.select("#chapters tbody tr")
        var chapterNumber = 1f
        for (row in chapterRows) {
            val link = row.selectFirst("a") ?: continue
            val chapterUrl = link.attr("href")
            val chapterTitle = link.text().trim()
            if (chapterTitle.isEmpty()) continue

            chapters += ContentChapter(
                id = generateUid(chapterUrl),
                title = chapterTitle,
                number = chapterNumber++,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            title = title,
            authors = if (author.isNotEmpty()) setOf(author) else manga.authors,
            description = description,
            tags = tags.ifEmpty { manga.tags },
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val content = getChapterContent(chapter) ?: return listOf(createErrorPage("Content is empty"))
        val dataUrl = content.html.toDataUrl()
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = dataUrl,
                preview = null,
                source = source,
            )
        )
    }

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        val url = chapter.url.toAbsoluteUrl(domain)
        return try {
            val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
            val contentEl = doc.selectFirst(".chapter-inner") ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst(".entry-content") ?: doc.selectFirst("#content")
                ?: doc.selectFirst("body")
            val title = chapter.title ?: doc.selectFirst("h1")?.text()?.trim() ?: ""
            val html = buildChapterHtml(title, contentEl ?: return null)
            NovelChapterContent(html = html, images = emptyList())
        } catch (e: Exception) {
            NovelChapterContent(
                html = buildErrorHtml("Failed to load: ${e.message}"),
                images = emptyList()
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        return page.url
    }

    private fun buildChapterHtml(title: String, contentEl: org.jsoup.nodes.Element): String {
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n")
            append("<style>\n")
            append("body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;")
            append("padding:16px;margin:0;line-height:1.9;font-size:1.05rem;}\n")
            append("h1{font-size:1.3rem;margin-bottom:1rem;}\n")
            append("p{margin:0 0 1rem;text-indent:2em;display:block;}\n")
            append("</style>\n</head>\n<body>\n")
            append("<h1>").append(title).append("</h1>\n")
            val paragraphs = contentEl.select("p")
            if (paragraphs.isNotEmpty()) {
                for (p in paragraphs) {
                    val text = p.text().trim()
                    if (text.isNotEmpty()) {
                        append("<p>").append(text).append("</p>\n")
                    }
                }
            } else {
                val text = contentEl.text().trim()
                for (line in text.split("\n")) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        append("<p>").append(trimmed).append("</p>\n")
                    }
                }
            }
            append("</body>\n</html>")
        }
    }

    private fun buildErrorHtml(message: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8"/>
        <style>body{font-family:sans-serif;padding:16px;}</style>
        </head><body><h1>Error</h1><p>$message</p></body></html>
    """.trimIndent()

    private fun createErrorPage(message: String): ContentPage {
        val html = buildErrorHtml(message)
        return ContentPage(
            id = generateUid(message),
            url = html.toDataUrl(),
            preview = null,
            source = source,
        )
    }

    private fun String.toDataUrl(): String {
        val encoded = Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
