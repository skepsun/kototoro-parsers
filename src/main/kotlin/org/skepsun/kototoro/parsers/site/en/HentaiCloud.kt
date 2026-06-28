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

@ContentSourceParser("HENTAICLOUD", "HentaiCloud", "en", type = ContentType.HENTAI_VIDEO)
internal class HentaiCloud(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HENTAICLOUD, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("www.hentaicloud.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableTags = getDefaultTags(),
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)

        val tags = doc.select("a[href*=/genre/], a[href*=/tag/], a[href*=/category/]").mapNotNull {
            val text = it.text().trim()
            val href = it.attr("href")
            if (text.isNotEmpty() && href.isNotEmpty()) {
                ContentTag(text, href.substringAfterLast('/'), source)
            } else null
        }.toSet()

        val chapter = ContentChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()

        val streams = extractStreams(doc)
        if (streams.isNotEmpty()) {
            return streams.map { src ->
                ContentPage(
                    id = generateUid(src),
                    url = src,
                    preview = null,
                    source = source,
                )
            }
        }

        val iframe = doc.selectFirst("iframe[src]")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src").toAbsoluteUrl(domain)
            val iframeDoc = webClient.httpGet(iframeSrc, getRequestHeaders()).parseHtml()
            val iframeStreams = extractStreams(iframeDoc)
            if (iframeStreams.isNotEmpty()) {
                return iframeStreams.map { src ->
                    ContentPage(
                        id = generateUid(src),
                        url = src,
                        preview = null,
                        source = source,
                    )
                }
            }
        }

        context.requestBrowserAction(this, url)
        return emptyList()
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildListUrl(page: Int, filter: ContentListFilter): String {
        val base = "https://$domain"
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "$base/search?q=$q&page=$page"
        } else if (filter.tags.isNotEmpty()) {
            val tag = filter.tags.first().key.urlEncoded()
            "$base/genre/$tag?page=$page"
        } else {
            "$base/popular?page=$page"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()
        val cards = doc.select(".video-item a[href], .hentai-item a[href], .card a[href], article a[href], .post a[href]")
        val links = if (cards.isNotEmpty()) cards else doc.select("a[href]").filter { a ->
            val h = a.attr("href")
            val hasImg = a.selectFirst("img") != null
            val notNav = !h.contains("genre") && !h.contains("category") && !h.contains("tag") &&
                !h.contains("login") && !h.contains("signup") &&
                !h.contains("cdn") && !h.contains("static") && !h.contains("assets") &&
                !h.contains("javascript") && h.startsWith("/") && h.count { it == '/' } >= 2
            hasImg && notNav
        }
        for (link in links) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst("h3, h2, .title, .name")?.text()?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = link.selectFirst("img[src], img[data-src]")?.let {
                (it.attr("data-src").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
            ))
            if (items.size >= pageSize) break
        }
        return items
    }

    private fun extractStreams(doc: Document): List<String> {
        val streams = LinkedHashSet<String>()

        doc.select("video source[src]").forEach { src ->
            src.attr("src").takeIf { it.isNotBlank() }?.let { streams.add(it.toAbsoluteUrl(domain)) }
        }
        doc.select("video[src]").forEach { v ->
            v.attr("src").takeIf { it.isNotBlank() }?.let { streams.add(it.toAbsoluteUrl(domain)) }
        }
        doc.select("meta[property=og:video]").forEach { meta ->
            meta.attr("content").takeIf { it.isNotBlank() }?.let { streams.add(it.toAbsoluteUrl(domain)) }
        }

        val html = doc.outerHtml()
        Regex("https?://[^\"'\\s>]+\\.(?:m3u8|mp4)", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            streams.add(m.value)
        }

        return streams.toList()
    }

    private fun getDefaultTags(): Set<ContentTag> = linkedSetOf(
        ContentTag("Big Boobs", "big-boobs", source),
        ContentTag("Blowjob", "blowjob", source),
        ContentTag("Creampie", "creampie", source),
        ContentTag("Futanari", "futanari", source),
        ContentTag("Harem", "harem", source),
        ContentTag("Loli", "loli", source),
        ContentTag("MILF", "milf", source),
        ContentTag("NTR", "ntr", source),
        ContentTag("Tentacles", "tentacles", source),
        ContentTag("Yuri", "yuri", source),
        ContentTag("3D", "3d", source),
        ContentTag("Anal", "anal", source),
        ContentTag("Ahegao", "ahegao", source),
        ContentTag("BDSM", "bdsm", source),
        ContentTag("Schoolgirl", "schoolgirl", source),
    )
}
