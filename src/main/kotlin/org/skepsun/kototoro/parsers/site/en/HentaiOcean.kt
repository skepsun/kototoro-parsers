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

@ContentSourceParser("HENTAIOCEAN", "HentaiOcean", "en", type = ContentType.HENTAI_VIDEO)
internal class HentaiOcean(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HENTAIOCEAN, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("hentaiocean.com")

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genres = listOf(
            "3D" to "3d", "Ahegao" to "ahegao", "Anal" to "anal",
            "Big Boobs" to "big-boobs", "Blowjob" to "blowjob", "Creampie" to "creampie",
            "Double Penetration" to "double-penetration", "Footjob" to "footjob",
            "Futanari" to "futanari", "Gangbang" to "gangbang", "Handjob" to "handjob",
            "Harem" to "harem", "Loli" to "loli", "MILF" to "milf",
            "NTR" to "ntr", "Schoolgirl" to "schoolgirl", "Tentacles" to "tentacles",
            "Threesome" to "threesome", "Virgin" to "virgin", "Yuri" to "yuri",
            "Yaoi" to "yaoi", "Oppai" to "oppai", "Bdsm" to "bdsm",
            "Mind Control" to "mind-control",
        ).map { ContentTag(it.first, it.second, source) }.toSet()
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
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
            ?.removeSuffix(" - HentaiOcean")?.trim()
            ?: doc.selectFirst(".entry-title, h1, .video-title")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".entry-content, .desc, .synopsis")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst(".poster img, .thumb img, .cover img")?.let {
                (it.attr("data-src").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }

        val tags = doc.select("a[href*=/genre/], a[href*=/tag/], a[href*=/category/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfterLast("/").trim()
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val episodeLinks = doc.select("a[href*=/watch/]").filter {
            it.selectFirst("img") == null && it.text().trim().length < 80
        }
        val chapters = if (episodeLinks.size > 1) {
            episodeLinks.mapIndexed { index, el ->
                val epUrl = el.attr("abs:href").takeIf { it.isNotBlank() }
                    ?: el.attr("href").toAbsoluteUrl(domain)
                val text = el.text().trim()
                val epNum = text.filter { it.isDigit() }.ifEmpty { (index + 1).toString() }
                ContentChapter(
                    id = generateUid(epUrl),
                    url = epUrl.removePrefix("https://$domain").removePrefix("http://$domain"),
                    title = text.ifBlank { "Episode $epNum" }, number = index + 1f,
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
            contentRating = ContentRating.ADULT,
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

        val serverButtons = doc.select("[data-video], [data-url]")
        if (serverButtons.isNotEmpty()) {
            return serverButtons.mapIndexed { index, btn ->
                val videoUrl = btn.attr("data-video").ifBlank { btn.attr("data-url") }
                ContentPage(
                    id = generateUid("${chapter.id}|$index"),
                    url = videoUrl, preview = "Server $index", source = source,
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
            "https://$domain/?s=$q&page=$page"
        } else {
            val tag = filter.tags.firstOrNull()?.key ?: ""
            val genreParam = if (tag.isNotEmpty()) "&genre=$tag" else ""
            "https://$domain/watch/?page=$page$genreParam"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
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
        }
        return items
    }
}
