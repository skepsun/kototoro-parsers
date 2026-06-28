package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

@ContentSourceParser("ANIMEEPISODESERIES", "AnimeEpisodeSeries", "en", type = ContentType.VIDEO)
internal class AnimeEpisodeSeries(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANIMEEPISODESERIES, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("animeepisodeseries.com")

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
        var cards: List<Element> = doc.select("a.film-poster-ahref[href], a.dynamic-name[href], a.item[href], .card a[href], .video-item a[href], .video-card a[href], article a[href], .post a[href]")
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
                ?: link.selectFirst("h3,h2,h4,.title,.name")?.text()?.trim()
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
            "https://$domain/search?keyword=$q&genre=$tagParam&page=$page"
        } else {
            "https://$domain/anime-seri-listesi?genre=$tagParam&page=$page"
        }
    }

    private companion object {
        val GENRES = setOf(
            ContentTag("Action", "action", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Adventure", "adventure", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Comedy", "comedy", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Drama", "drama", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Fantasy", "fantasy", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Horror", "horror", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Mecha", "mecha", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Music", "music", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Mystery", "mystery", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Romance", "romance", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Sci-Fi", "sci-fi", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Slice of Life", "slice-of-life", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Sports", "sports", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Supernatural", "supernatural", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Thriller", "thriller", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Shounen", "shounen", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Seinen", "seinen", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Ecchi", "ecchi", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Isekai", "isekai", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Magic", "magic", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Martial Arts", "martial-arts", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("School", "school", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Super Power", "super-power", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Game", "game", ContentParserSource.ANIMEEPISODESERIES),
            ContentTag("Historical", "historical", ContentParserSource.ANIMEEPISODESERIES),
        )
    }
}
