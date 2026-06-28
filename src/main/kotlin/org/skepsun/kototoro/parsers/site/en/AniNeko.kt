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

@ContentSourceParser("ANINEKO", "AniNeko", "en", type = ContentType.VIDEO)
internal class AniNeko(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANINEKO, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("anineko.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genres = listOf(
            "Action" to "action", "Adventure" to "adventure", "Cars" to "cars",
            "Comedy" to "comedy", "Dementia" to "dementia", "Demons" to "demons",
            "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy",
            "Game" to "game", "Harem" to "harem", "Historical" to "historical",
            "Horror" to "horror", "Isekai" to "isekai", "Josei" to "josei",
            "Kids" to "kids", "Magic" to "magic", "Mahou Shoujo" to "mahou-shoujo",
            "Martial Arts" to "martial-arts", "Mecha" to "mecha", "Military" to "military",
            "Music" to "music", "Mystery" to "mystery", "Parody" to "parody",
            "Police" to "police", "Psychological" to "psychological", "Romance" to "romance",
            "Samurai" to "samurai", "School" to "school", "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen", "Shoujo" to "shoujo", "Shoujo Ai" to "shoujo-ai",
            "Shounen" to "shounen", "Shounen Ai" to "shounen-ai",
            "Slice of Life" to "slice-of-life", "Space" to "space", "Sports" to "sports",
            "Super Power" to "super-power", "Supernatural" to "supernatural",
            "Thriller" to "thriller", "Vampire" to "vampire",
        ).map { ContentTag(it.first, it.second, source) }.toSet()
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
            availableTags = genres,
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
            ?.removeSuffix(" - AniNeko")?.trim()
            ?: doc.selectFirst(".nv-info-main h1, .nv-info-main .title")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".nv-info-desc")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".nv-info-poster img")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val tags = doc.select(".nv-info-tags a[href*=/genre/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfterLast("/").trim()
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val episodeLinks = doc.select("a[href*=/ep-]")
        val chapters = if (episodeLinks.isNotEmpty()) {
            episodeLinks.mapIndexed { index, el ->
                val epUrl = el.attr("abs:href").takeIf { it.isNotBlank() }
                    ?: el.attr("href").toAbsoluteUrl(domain)
                val epNum = el.selectFirst("[data-episode]")?.attr("data-episode")
                    ?: el.text().trim().filter { it.isDigit() }.ifEmpty { (index + 1).toString() }
                ContentChapter(
                    id = generateUid(epUrl),
                    url = epUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                    title = "Episode $epNum", number = index + 1f,
                    uploadDate = 0L, volume = 0, branch = null, scanlator = null, source = source,
                )
            }.toList()
        } else {
            emptyList()
        }

        return manga.copy(
            title = title, description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover ?: manga.largeCoverUrl,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            contentRating = ContentRating.SAFE,
            chapters = chapters.ifEmpty {
                listOf(ContentChapter(
                    id = generateUid(manga.url), url = manga.url, title = "Watch",
                    number = 1f, uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                ))
            },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(chapterUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val serverButtons = doc.select("[data-video]")
        if (serverButtons.isNotEmpty()) {
            return serverButtons.mapIndexed { index, btn ->
                val videoUrl = btn.attr("data-video")
                val tabName = btn.closest(".tab-pane")?.attr("id") ?: "Server"
                ContentPage(
                    id = generateUid("${chapter.id}|$index"),
                    url = videoUrl,
                    preview = "Server $index",
                    source = source,
                )
            }
        }

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

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?keyword=$q&page=$page"
        } else {
            val tag = filter.tags.firstOrNull()?.key ?: ""
            val sortParam = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.NEWEST -> "newest"
                SortOrder.ALPHABETICAL -> "az"
                else -> "latest"
            }
            val genreParam = if (tag.isNotEmpty()) "&genre=$tag" else ""
            "https://$domain/browse?sort=$sortParam$genreParam&page=$page"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        val cards = doc.select("a[href*=/watch/]").filter { el ->
            el.selectFirst("img[alt]") != null || el.selectFirst(".title, .name") != null
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            if (href.count { it == '/' } < 3) continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst(".title, .name")?.text()?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = link.selectFirst("img[src]")?.let {
                val raw = it.attr("data-src").ifBlank { it.attr("src") }
                raw.toAbsoluteUrlOrNull(domain)
            }
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.SAFE, source = source, rating = RATING_UNKNOWN,
            ))
        }
        return items
    }
}
