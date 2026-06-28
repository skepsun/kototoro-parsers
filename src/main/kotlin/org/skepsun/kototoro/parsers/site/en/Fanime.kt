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

@ContentSourceParser("FANIME", "Fanime", "en", type = ContentType.VIDEO)
internal class Fanime(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.FANIME, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("fanime.tv")

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
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
            availableTags = buildFilterTags(),
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
            ?.removeSuffix(" - Fanime")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .synopsis, .desc")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".poster img, .cover img")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val tags = doc.select("a[href*=/genre/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val episodeLinks = doc.select("a[href*=/episode/], a[href*=/watch/]")
        val chapters = episodeLinks.mapIndexed { index, el ->
            val epUrl = el.attr("abs:href").takeIf { it.isNotBlank() }
                ?: el.attr("href").toAbsoluteUrl(domain)
            ContentChapter(
                id = generateUid(epUrl),
                url = epUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                title = el.text().trim().ifEmpty { "Episode ${index + 1}" },
                number = index + 1f,
                uploadDate = 0L,
                volume = 0,
                branch = null,
                scanlator = null,
                source = source,
            )
        }.toList()

        val fallbackChapters = if (chapters.isEmpty()) {
            listOf(
                ContentChapter(
                    id = generateUid(manga.url),
                    url = manga.url,
                    title = "Watch",
                    number = 1f,
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            )
        } else {
            chapters
        }

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            contentRating = ContentRating.SAFE,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = fallbackChapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(chapterUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val videoEl = doc.selectFirst("video source[src], video[src], iframe[src]")
        val videoUrl = videoEl?.attr("src")?.takeIf { it.isNotBlank() }
            ?: videoEl?.attr("data-src")?.takeIf { it.isNotBlank() }

        if (videoUrl != null && videoUrl.startsWith("http")) {
            return listOf(
                ContentPage(
                    id = generateUid("video:${chapter.id}"),
                    url = videoUrl,
                    preview = null,
                    source = source,
                )
            )
        }

        context.requestBrowserAction(this, chapterUrl)
        return emptyList()
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
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
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            val tagParam = filter.tags.joinToString(",") { it.key }
            val genreParam = if (tagParam.isNotEmpty()) "&genre=$tagParam" else ""
            "https://$domain/search?q=$q&page=$page$genreParam"
        } else {
            val listPath = when (order) {
                SortOrder.POPULARITY -> "anime/popular"
                else -> "anime/newest"
            }
            val tagParam = filter.tags.joinToString(",") { it.key }
            val genreParam = if (tagParam.isNotEmpty()) "&genre=$tagParam" else ""
            "https://$domain/$listPath?page=$page$genreParam"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()

        val cards = doc.select("a[href*=/anime/]").filter { el ->
            el.selectFirst("img[alt]") != null || el.selectFirst(".title, .name") != null
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue

            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst(".title, .name")?.text()?.trim()
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
}
