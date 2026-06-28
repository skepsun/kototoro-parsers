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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.ArrayList
import java.util.EnumSet
import java.util.LinkedHashSet
import okhttp3.Headers

@ContentSourceParser("NOVELARCHIVE", "NovelArchive", "en", type = ContentType.NOVEL)
internal class NovelArchive(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.NOVELARCHIVE, pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("novelarchive.cc")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.NOVEL),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .summary, .desc, .synopsis")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".novel-cover img, .book-cover img, img.cover")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val chapterLinks = doc.select("a[href*=/chapter/], a[href*=/read/], a[href*=/novel/]")
        val chapters = chapterLinks.mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapterUrl = href.toAbsoluteUrl(domain)
            ContentChapter(
                id = generateUid(chapterUrl),
                url = chapterUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                title = el.text().trim().ifEmpty { "Chapter" },
                number = 0f,
                uploadDate = 0L,
                volume = 0,
                branch = null,
                scanlator = null,
                source = source,
            )
        }

        if (chapters.isEmpty()) {
            val fallback = listOf(
                ContentChapter(
                    id = generateUid(manga.url),
                    url = manga.url,
                    title = "Read",
                    number = 1f,
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            )
            return manga.copy(
                title = title,
                description = description?.ifBlank { null },
                coverUrl = cover ?: manga.coverUrl,
                largeCoverUrl = cover ?: manga.largeCoverUrl,
                contentRating = ContentRating.SAFE,
                chapters = fallback,
            )
        }

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            contentRating = ContentRating.SAFE,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(chapterUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val contentEl = doc.selectFirst(".chapter-content, .novel-content, .entry-content, #content, .text, .reading-content")
        val text = contentEl?.text()?.trim() ?: doc.body()?.text()?.trim() ?: ""

        if (text.isBlank()) {
            val html = buildErrorHtml("Content not found")
            return listOf(
                ContentPage(
                    id = generateUid(chapter.url),
                    url = html.toDataUrl(),
                    preview = null,
                    source = source,
                )
            )
        }

        val html = buildChapterHtml(chapter.title ?: "", text)
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = html.toDataUrl(),
                preview = null,
                source = source,
            )
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?keyword=$q&page=$page"
        } else {
            "https://$domain/browse?page=$page"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()

        val cards = doc.select("a[href*=/novel/]").filter { el ->
            el.selectFirst("img") != null || el.selectFirst(".title, .novel-title") != null
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue

            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst(".title, .novel-title, .name")?.text()?.trim()
                ?: link.text().trim().ifEmpty { continue }

            val thumb = link.selectFirst("img[src]")?.let {
                val raw = it.attr("data-src").ifBlank { it.attr("src") }
                raw.toAbsoluteUrlOrNull(domain)
            }

            items.add(
                Content(
                    id = generateUid(absoluteUrl),
                    url = absoluteUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                    publicUrl = absoluteUrl,
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = thumb,
                    largeCoverUrl = thumb,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = null,
                    contentRating = ContentRating.SAFE,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
        }

        return items
    }

    private fun buildChapterHtml(title: String, content: String): String {
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n")
            append("<style>\n")
            append("body{font-family:Georgia,\"Times New Roman\",serif;")
            append("padding:16px;margin:0;line-height:1.9;font-size:1.05rem;}\n")
            append("h1{font-size:1.3rem;margin-bottom:1rem;}\n")
            append("p{margin:0 0 1rem;text-indent:2em;display:block;}\n")
            append("</style>\n</head>\n<body>\n")
            if (title.isNotBlank()) {
                append("<h1>").append(title).append("</h1>\n")
            }
            val paragraphs = content.split("\n")
            for (para in paragraphs) {
                val trimmed = para.trim()
                if (trimmed.isNotEmpty()) {
                    append("<p>").append(trimmed).append("</p>\n")
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

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
