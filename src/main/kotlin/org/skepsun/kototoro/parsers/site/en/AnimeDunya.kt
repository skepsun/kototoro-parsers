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

@ContentSourceParser("ANIMEDUNYA", "AnimeDunya", "en", type = ContentType.VIDEO)
internal class AnimeDunya(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANIMEDUNYA, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("anime-dunya.com")

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
        val chapters = doc.select("a[href*=/episode/], a[href*=/watch/]").mapIndexed { index, el ->
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
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()
        val videoEl = doc.selectFirst("video source[src], video[src], iframe[src]")
        val videoUrl = videoEl?.attr("src")?.takeIf { it.isNotBlank() }
            ?: videoEl?.attr("data-src")?.takeIf { it.isNotBlank() }
        if (videoUrl != null && videoUrl.startsWith("http")) {
            return listOf(ContentPage(
                id = generateUid("video:${chapter.id}"),
                url = videoUrl, preview = null, source = source,
            ))
        }
        context.requestBrowserAction(this, chapterUrl)
        return emptyList()
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        for (link in doc.select("a[href*=/anime/], a[href*=/watch/]")) {
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
            "https://$domain/arama?q=$q&genre=$tagParam&page=$page"
        } else {
            "https://$domain/anime.html?genre=$tagParam&page=$page"
        }
    }

    private companion object {
        val GENRES = setOf(
            ContentTag("Action", "action", ContentParserSource.ANIMEDUNYA),
            ContentTag("Adventure", "adventure", ContentParserSource.ANIMEDUNYA),
            ContentTag("Comedy", "comedy", ContentParserSource.ANIMEDUNYA),
            ContentTag("Drama", "drama", ContentParserSource.ANIMEDUNYA),
            ContentTag("Fantasy", "fantasy", ContentParserSource.ANIMEDUNYA),
            ContentTag("Horror", "horror", ContentParserSource.ANIMEDUNYA),
            ContentTag("Mecha", "mecha", ContentParserSource.ANIMEDUNYA),
            ContentTag("Music", "music", ContentParserSource.ANIMEDUNYA),
            ContentTag("Mystery", "mystery", ContentParserSource.ANIMEDUNYA),
            ContentTag("Romance", "romance", ContentParserSource.ANIMEDUNYA),
            ContentTag("Sci-Fi", "sci-fi", ContentParserSource.ANIMEDUNYA),
            ContentTag("Slice of Life", "slice-of-life", ContentParserSource.ANIMEDUNYA),
            ContentTag("Sports", "sports", ContentParserSource.ANIMEDUNYA),
            ContentTag("Supernatural", "supernatural", ContentParserSource.ANIMEDUNYA),
            ContentTag("Thriller", "thriller", ContentParserSource.ANIMEDUNYA),
            ContentTag("Shounen", "shounen", ContentParserSource.ANIMEDUNYA),
            ContentTag("Seinen", "seinen", ContentParserSource.ANIMEDUNYA),
            ContentTag("Ecchi", "ecchi", ContentParserSource.ANIMEDUNYA),
            ContentTag("Isekai", "isekai", ContentParserSource.ANIMEDUNYA),
            ContentTag("Magic", "magic", ContentParserSource.ANIMEDUNYA),
            ContentTag("Martial Arts", "martial-arts", ContentParserSource.ANIMEDUNYA),
            ContentTag("School", "school", ContentParserSource.ANIMEDUNYA),
            ContentTag("Super Power", "super-power", ContentParserSource.ANIMEDUNYA),
            ContentTag("Game", "game", ContentParserSource.ANIMEDUNYA),
            ContentTag("Historical", "historical", ContentParserSource.ANIMEDUNYA),
        )
    }
}
