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
import java.util.Base64
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("NOVELHI", "NovelHi", "en", type = ContentType.NOVEL)
internal class NovelHi(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.NOVELHI, pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("novelhi.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.NOVEL),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val q = filter.query?.urlEncoded() ?: ""
        val url = if (q.isNotEmpty()) "https://$domain/?s=$q&page=$page"
        else "https://$domain/?page=$page"
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val chapters = doc.select("a[href*=/chapter/], li a[href*=/novel/]").mapIndexed { index, el ->
            val chUrl = el.attr("abs:href").takeIf { it.isNotBlank() } ?: el.attr("href").toAbsoluteUrl(domain)
            ContentChapter(
                id = generateUid(chUrl), url = chUrl.removePrefix("https://$domain"),
                title = el.text().trim().ifEmpty { "Chapter ${index + 1}" },
                number = index + 1f, uploadDate = 0L, volume = 0,
                branch = null, scanlator = null, source = source,
            )
        }.toList()
        return manga.copy(
            title = title, description = description ?: manga.description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
            contentRating = ContentRating.SAFE,
            chapters = chapters.ifEmpty {
                listOf(ContentChapter(
                    id = generateUid(manga.url), url = manga.url, title = "Chapter 1",
                    number = 1f, uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                ))
            },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()
        val elements = listOf(".chapter-content", ".txt", ".content", ".entry-content", "#content", "body")
        val contentEl = elements.firstNotNullOfOrNull { doc.selectFirst(it) } ?: return emptyList()
        val title = chapter.title ?: doc.selectFirst("h1, h2")?.text()?.trim() ?: ""
        val text = contentEl.text().replace(Regex("\\n{3,}"), "\n\n").trim()
        val html = "<html><head><meta charset=utf-8></head><body>" +
                "<h2>$title</h2>" +
                text.split("\n").joinToString("") { "<p>$it</p>" } +
                "</body></html>"
        return listOf(ContentPage(
            id = generateUid(chapter.id),
            url = "data:text/html;charset=utf-8;base64,${Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))}",
            preview = null, source = source,
        ))
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent()).build()

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        for (link in doc.select("a[href*=/novel/]")) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = link.selectFirst("img[src]")?.attr("src")?.toAbsoluteUrlOrNull(domain)
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.SAFE, source = source, rating = RATING_UNKNOWN,
            ))
        }
        return items
    }
}
