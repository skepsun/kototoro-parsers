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

@ContentSourceParser("ANIKOTO", "Anikoto", "en", type = ContentType.VIDEO)
internal class Anikoto(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANIKOTO, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("anikototv.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.NEWEST,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val doc = webClient.httpGet("https://$domain/home", getRequestHeaders()).parseHtml()
        val genres = doc.select("#menu ul.c4 a[href*=/genre/]").mapNotNull { a ->
            val title = a.attr("title").ifBlank { a.text().trim() }
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (title.isNotBlank() && key.isNotBlank()) ContentTag(title, key, source) else null
        }
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
            availableTags = genres.toSet(),
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
            ?.removeSuffix(" - Anikoto")?.trim()
            ?: doc.selectFirst(".binfo h1.title")?.text()?.trim() ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".binfo .synopsis .content")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".binfo .poster img")?.attr("src")?.toAbsoluteUrlOrNull(domain)

        val mangaId = doc.selectFirst("script:containsData(mangaId)")?.data()
            ?.let { Regex("mangaId\\s*=\\s*(\\d+)").find(it)?.groupValues?.get(1) }

        val authors = doc.select(".binfo .info .names")?.text()
            ?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        val tags = doc.select(".binfo .bmeta a[href*=/genre/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val chapters = if (mangaId != null) {
            fetchEpisodeList(mangaId)
        } else {
            emptyList()
        }

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
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

        val videoEl = doc.selectFirst("video source[src], video[src], iframe[src]")
        val videoUrl = videoEl?.attr("src")?.takeIf { it.startsWith("http") }
            ?: videoEl?.attr("data-src")?.takeIf { it.startsWith("http") }

        if (videoUrl != null) {
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
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun fetchEpisodeList(animeId: String): List<ContentChapter> {
        return try {
            val url = "https://$domain/ajax/episode/list/$animeId"
            val response = webClient.httpGet(url, getRequestHeaders())
            val doc = response.parseHtml()
            val episodes = doc.select("li a[data-num]")
            episodes.map { el ->
                val num = el.attr("data-num")
                val epId = el.attr("data-id")
                ContentChapter(
                    id = generateUid("$animeId|$epId"),
                    url = "-episode-$num",
                    title = "Episode $num",
                    number = num.toFloatOrNull() ?: 0f,
                    uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val tagParam = filter.tags.joinToString(",") { it.key }
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            val sortParam = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.ALPHABETICAL -> "az"
                SortOrder.NEWEST -> "newest"
                else -> "default"
            }
            "https://$domain/filter?keyword=$q&type=&status=&season=&language=&genre=$tagParam&sort=$sortParam&page=$page"
        } else {
            "https://$domain/home"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        val cards = doc.select("a.item[href*=/watch/]")
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst(".name.d-title")?.text()?.trim()
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim() ?: continue
            val thumb = link.selectFirst(".poster img[src]")?.attr("src")?.toAbsoluteUrlOrNull(domain)
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
