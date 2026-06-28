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

@ContentSourceParser("PIMPANIME", "PimpAnime", "en", type = ContentType.VIDEO)
internal class PimpAnime(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.PIMPANIME, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("pimpanime.nl")

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
        for (link in doc.select("a[href*=/anime/]")) {
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
            ContentTag("Action", "action", ContentParserSource.PIMPANIME),
            ContentTag("Adventure", "adventure", ContentParserSource.PIMPANIME),
            ContentTag("Comedy", "comedy", ContentParserSource.PIMPANIME),
            ContentTag("Drama", "drama", ContentParserSource.PIMPANIME),
            ContentTag("Fantasy", "fantasy", ContentParserSource.PIMPANIME),
            ContentTag("Horror", "horror", ContentParserSource.PIMPANIME),
            ContentTag("Mecha", "mecha", ContentParserSource.PIMPANIME),
            ContentTag("Music", "music", ContentParserSource.PIMPANIME),
            ContentTag("Mystery", "mystery", ContentParserSource.PIMPANIME),
            ContentTag("Romance", "romance", ContentParserSource.PIMPANIME),
            ContentTag("Sci-Fi", "sci-fi", ContentParserSource.PIMPANIME),
            ContentTag("Slice of Life", "slice-of-life", ContentParserSource.PIMPANIME),
            ContentTag("Sports", "sports", ContentParserSource.PIMPANIME),
            ContentTag("Supernatural", "supernatural", ContentParserSource.PIMPANIME),
            ContentTag("Thriller", "thriller", ContentParserSource.PIMPANIME),
            ContentTag("Shounen", "shounen", ContentParserSource.PIMPANIME),
            ContentTag("Seinen", "seinen", ContentParserSource.PIMPANIME),
            ContentTag("Ecchi", "ecchi", ContentParserSource.PIMPANIME),
            ContentTag("Isekai", "isekai", ContentParserSource.PIMPANIME),
            ContentTag("Magic", "magic", ContentParserSource.PIMPANIME),
            ContentTag("Martial Arts", "martial-arts", ContentParserSource.PIMPANIME),
            ContentTag("School", "school", ContentParserSource.PIMPANIME),
            ContentTag("Super Power", "super-power", ContentParserSource.PIMPANIME),
            ContentTag("Game", "game", ContentParserSource.PIMPANIME),
            ContentTag("Historical", "historical", ContentParserSource.PIMPANIME),
        )
    }
}
