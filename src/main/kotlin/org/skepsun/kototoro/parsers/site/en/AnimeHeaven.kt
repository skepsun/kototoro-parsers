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

@ContentSourceParser("ANIMEHEAVEN", "AnimeHeaven", "en", type = ContentType.VIDEO)
internal class AnimeHeaven(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANIMEHEAVEN, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("animeheaven.me")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
        availableTags = buildFilterTags(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select("a[href*=/genre/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val chapterLinks = doc.select(
            "a[href*=/episode/], a[href*=/watch/], a[href*=/view/], a[href*=/stream/]",
        )
        val chapters = chapterLinks.filter { link ->
            val href = link.attr("href")
            href.contains("/episode/") || href.contains("/watch/") ||
                href.contains("/view/") || href.contains("/stream/")
        }.mapIndexed { index, link ->
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexed null
            val chTitle = link.text().trim().ifBlank { "Episode ${index + 1}" }
            val absoluteUrl = href.toAbsoluteUrl(domain)
            ContentChapter(
                id = generateUid(absoluteUrl),
                title = chTitle,
                number = (index + 1).toFloat(),
                volume = 0,
                url = absoluteUrl.removePrefix("https://$domain"),
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.filterNotNull()

        return manga.copy(
            title = title, description = description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
            contentRating = ContentRating.SAFE,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters.ifEmpty { manga.chapters },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()

        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
            ?: doc.selectFirst("iframe[data-src]")?.attr("data-src")
        if (iframeSrc != null) {
            return listOf(ContentPage(
                id = generateUid(chapterUrl),
                url = iframeSrc.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            ))
        }

        val videoSrc = doc.selectFirst("video source[src]")?.attr("src")
            ?: doc.selectFirst("video source[data-src]")?.attr("data-src")
            ?: doc.selectFirst("video[src]")?.attr("src")
            ?: doc.selectFirst("video[data-src]")?.attr("data-src")
        if (videoSrc != null) {
            return listOf(ContentPage(
                id = generateUid(chapterUrl),
                url = videoSrc.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            ))
        }

        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
        if (ogVideo != null) {
            return listOf(ContentPage(
                id = generateUid(chapterUrl),
                url = ogVideo.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            ))
        }

        context.requestBrowserAction(this, chapterUrl)
        return emptyList()
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildFilterTags(): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        tags += ContentTag("Action", "action", source)
        tags += ContentTag("Adventure", "adventure", source)
        tags += ContentTag("Comedy", "comedy", source)
        tags += ContentTag("Drama", "drama", source)
        tags += ContentTag("Fantasy", "fantasy", source)
        tags += ContentTag("Horror", "horror", source)
        tags += ContentTag("Mecha", "mecha", source)
        tags += ContentTag("Music", "music", source)
        tags += ContentTag("Mystery", "mystery", source)
        tags += ContentTag("Romance", "romance", source)
        tags += ContentTag("Sci-Fi", "sci-fi", source)
        tags += ContentTag("Slice of Life", "slice-of-life", source)
        tags += ContentTag("Sports", "sports", source)
        tags += ContentTag("Supernatural", "supernatural", source)
        tags += ContentTag("Thriller", "thriller", source)
        tags += ContentTag("Shounen", "shounen", source)
        tags += ContentTag("Seinen", "seinen", source)
        tags += ContentTag("Shoujo", "shoujo", source)
        tags += ContentTag("Josei", "josei", source)
        tags += ContentTag("Ecchi", "ecchi", source)
        tags += ContentTag("Harem", "harem", source)
        tags += ContentTag("Isekai", "isekai", source)
        tags += ContentTag("Magic", "magic", source)
        tags += ContentTag("Martial Arts", "martial-arts", source)
        tags += ContentTag("Military", "military", source)
        tags += ContentTag("School", "school", source)
        tags += ContentTag("Super Power", "super-power", source)
        tags += ContentTag("Vampire", "vampire", source)
        tags += ContentTag("Game", "game", source)
        tags += ContentTag("Historical", "historical", source)
        tags += ContentTag("Kids", "kids", source)
        tags += ContentTag("Parody", "parody", source)
        tags += ContentTag("Samurai", "samurai", source)
        tags += ContentTag("Psychological", "psychological", source)
        tags += ContentTag("Demons", "demons", source)
        tags += ContentTag("Space", "space", source)
        tags += ContentTag("Cars", "cars", source)
        tags += ContentTag("Dementia", "dementia", source)
        tags += ContentTag("Police", "police", source)
        tags += ContentTag("Mahou Shoujo", "mahou-shoujo", source)
        tags += ContentTag("Shoujo Ai", "shoujo-ai", source)
        tags += ContentTag("Shounen Ai", "shounen-ai", source)
        return tags
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val q = filter.query?.urlEncoded() ?: ""
        if (q.isNotEmpty()) {
            return "https://$domain/search.php?q=$q&page=$page"
        }
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "popular"
            else -> "latest"
        }
        val tagParam = filter.tags.joinToString(",") { it.key }
        val genreParam = if (tagParam.isNotEmpty()) "&genre=$tagParam" else ""
        return "https://$domain/?sort=$sortParam&page=$page$genreParam"
    }

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
}
