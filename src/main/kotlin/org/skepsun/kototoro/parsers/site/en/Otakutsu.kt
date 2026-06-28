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

@ContentSourceParser("OTAKUTSU", "Otakutsu", "en", type = ContentType.VIDEO)
internal class Otakutsu(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.OTAKUTSU, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("otakutsu.cc")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.NEWEST)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = true)

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
        availableTags = GENRES,
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = doc.select("a[href*=/genre/], a[href*=/category/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfterLast("/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key.lowercase(), source) else null
        }.toSet()
        val chapters = doc.select("a[href*=/watch/]").mapIndexed { index, el ->
            val epUrl = el.attr("abs:href").takeIf { it.isNotBlank() } ?: el.attr("href").toAbsoluteUrl(domain)
            ContentChapter(
                id = generateUid(epUrl), url = epUrl.removePrefix("https://$domain"),
                title = el.text().trim().ifEmpty { "Episode ${index + 1}" },
                number = index + 1f, uploadDate = 0L, volume = 0,
                branch = null, scanlator = null, source = source,
            )
        }
        return manga.copy(
            title = title, description = description ?: manga.description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            contentRating = ContentRating.SAFE,
            chapters = chapters.ifEmpty { listOf(ContentChapter(
                id = generateUid(manga.url), url = manga.url, title = "Watch",
                number = 1f, uploadDate = 0L, volume = 0, branch = null, scanlator = null, source = source,
            )) },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        context.requestBrowserAction(this, chapter.url.toAbsoluteUrl(domain))
        return emptyList()
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent()).build()

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        var cards = doc.select("a.film-poster-ahref[href], a.item[href], .card a[href], .video-item a[href], .video-card a[href], article a[href], .post a[href]").toList()
        if (cards.isEmpty()) {
            cards = doc.select("a[href]").filter { a ->
                val h = a.attr("href")
                val hasContent = a.selectFirst("img") != null || a.selectFirst("h3,h2,h4,.title,.name") != null
                val notNav = !h.contains("genre") && !h.contains("category") && !h.contains("tag") &&
                    !h.contains("login") && !h.contains("signup") && !h.contains("random") &&
                    !h.contains("cdn") && !h.contains("static") && !h.contains("assets") &&
                    !h.contains("javascript") && !h.contains("facebook") && !h.contains("twitter") &&
                    h.startsWith("/") && h.count { it == '/' } >= 2 && h.length > 5
                hasContent || notNav
            }
        }
        for (link in cards) {
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
        }
        return items
    }

    private fun buildListUrl(page: Int, filter: ContentListFilter): String {
        val tagParam = filter.tags.joinToString(",") { it.key }
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?q=$q&genre=$tagParam&page=$page"
        } else {
            "https://$domain/?genre=$tagParam&page=$page"
        }
    }

    private companion object {
        val GENRES = setOf(
            ContentTag("Action", "action", ContentParserSource.OTAKUTSU),
            ContentTag("Adventure", "adventure", ContentParserSource.OTAKUTSU),
            ContentTag("Comedy", "comedy", ContentParserSource.OTAKUTSU),
            ContentTag("Drama", "drama", ContentParserSource.OTAKUTSU),
            ContentTag("Fantasy", "fantasy", ContentParserSource.OTAKUTSU),
            ContentTag("Horror", "horror", ContentParserSource.OTAKUTSU),
            ContentTag("Mecha", "mecha", ContentParserSource.OTAKUTSU),
            ContentTag("Music", "music", ContentParserSource.OTAKUTSU),
            ContentTag("Mystery", "mystery", ContentParserSource.OTAKUTSU),
            ContentTag("Romance", "romance", ContentParserSource.OTAKUTSU),
            ContentTag("Sci-Fi", "sci-fi", ContentParserSource.OTAKUTSU),
            ContentTag("Slice of Life", "slice-of-life", ContentParserSource.OTAKUTSU),
            ContentTag("Sports", "sports", ContentParserSource.OTAKUTSU),
            ContentTag("Supernatural", "supernatural", ContentParserSource.OTAKUTSU),
            ContentTag("Thriller", "thriller", ContentParserSource.OTAKUTSU),
            ContentTag("Shounen", "shounen", ContentParserSource.OTAKUTSU),
            ContentTag("Seinen", "seinen", ContentParserSource.OTAKUTSU),
            ContentTag("Ecchi", "ecchi", ContentParserSource.OTAKUTSU),
            ContentTag("Isekai", "isekai", ContentParserSource.OTAKUTSU),
            ContentTag("Magic", "magic", ContentParserSource.OTAKUTSU),
            ContentTag("Martial Arts", "martial-arts", ContentParserSource.OTAKUTSU),
            ContentTag("School", "school", ContentParserSource.OTAKUTSU),
            ContentTag("Super Power", "super-power", ContentParserSource.OTAKUTSU),
            ContentTag("Game", "game", ContentParserSource.OTAKUTSU),
            ContentTag("Historical", "historical", ContentParserSource.OTAKUTSU),
        )
    }
}
