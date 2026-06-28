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
import java.util.EnumSet
import okhttp3.Headers

@ContentSourceParser("YUMEZONE", "YumeZone", "en", type = ContentType.VIDEO)
internal class YumeZone(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.YUMEZONE, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("yumezone.live")

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
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
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
            ?.removeSuffix(" - YumeZone")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .synopsis, .desc")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".poster img, .cover img")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val episodeLinks = doc.select("a[href*=/watch/]")
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

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?q=$q&page=$page"
        } else {
            val sortParam = when (order) {
                SortOrder.POPULARITY -> "?sort=popular&page=$page"
                else -> "?page=$page"
            }
            "https://$domain/$sortParam"
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
