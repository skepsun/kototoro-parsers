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

@ContentSourceParser("AKIH", "Aki-H", "en", type = ContentType.HENTAI_VIDEO)
internal class AkiH(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.AKIH, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("aki-h.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = true)

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genres = runCatching {
            val doc = webClient.httpGet("https://$domain/", getRequestHeaders()).parseHtml()
            doc.select("a[href*=/genre/]").mapNotNull { a ->
                val text = a.text().trim().replaceFirstChar { it.uppercase() }
                val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
                if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
            }.toSet()
        }.getOrDefault(HENTAI_GENRES)
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = genres,
        )
    }

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
            val text = a.text().trim().replaceFirstChar { it.uppercase() }
            val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val epLinks = doc.select("a[href*=/episode/], a.film-poster-ahref")
        val chapters = if (epLinks.isNotEmpty()) {
            epLinks.mapIndexed { idx, el ->
                val href = el.attr("abs:href").takeIf { it.isNotBlank() }
                    ?: el.attr("href").toAbsoluteUrl(domain)
                ContentChapter(
                    id = generateUid(href), url = href.removePrefix("https://$domain"),
                    title = el.text().trim().ifEmpty { "Episode ${idx + 1}" },
                    number = idx + 1f, uploadDate = 0L, volume = 0,
                    branch = null, scanlator = null, source = source,
                )
            }.toList()
        } else {
            emptyList()
        }

        return manga.copy(
            title = title, description = description ?: manga.description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
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
        val cards = doc.select("a.film-poster-ahref[href], a.dynamic-name[href]")
        if (cards.isEmpty()) {
            val fallback = doc.select("a[href]").filter { a ->
                val h = a.attr("href")
                h.startsWith("/") && h.count { it == '/' } == 1 && h.length > 3 &&
                    !h.contains("random") && !h.contains("popular") && !h.contains("genre") &&
                    !h.contains("filter") && !h.contains("search") && !h.contains("login") &&
                    !h.contains("cdn.") && !h.contains("static") && !h.contains("assets")
            }
            for (link in fallback) {
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
                    contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
                ))
            }
            return items
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.text().trim().ifEmpty {
                link.selectFirst("img[alt]")?.attr("alt")?.trim()
            }?.ifBlank { continue } ?: continue
            val thumb = link.selectFirst("img[src]")?.attr("src")?.toAbsoluteUrlOrNull(domain)
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

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val tag = filter.tags.firstOrNull()?.key
        return when {
            !filter.query.isNullOrBlank() -> "https://$domain/?s=${filter.query.urlEncoded()}&page=$page"
            tag != null -> "https://$domain/genre/$tag/"
            order == SortOrder.POPULARITY -> "https://$domain/popular/"
            else -> "https://$domain/"
        }
    }

    private companion object {
        val HENTAI_GENRES = setOf(
            ContentTag("3D", "3d", ContentParserSource.AKIH),
            ContentTag("Ahegao", "ahegao", ContentParserSource.AKIH),
            ContentTag("Anal", "anal", ContentParserSource.AKIH),
            ContentTag("BDSM", "bdsm", ContentParserSource.AKIH),
            ContentTag("Big Boobs", "big-boobs", ContentParserSource.AKIH),
            ContentTag("Blowjob", "blow-job", ContentParserSource.AKIH),
            ContentTag("Bondage", "bondage", ContentParserSource.AKIH),
            ContentTag("Creampie", "creampie", ContentParserSource.AKIH),
            ContentTag("Futanari", "futanari", ContentParserSource.AKIH),
            ContentTag("Gangbang", "gangbang", ContentParserSource.AKIH),
            ContentTag("Harem", "harem", ContentParserSource.AKIH),
            ContentTag("Incest", "incest", ContentParserSource.AKIH),
            ContentTag("Loli", "loli", ContentParserSource.AKIH),
            ContentTag("Maid", "maid", ContentParserSource.AKIH),
            ContentTag("Masturbation", "masturbation", ContentParserSource.AKIH),
            ContentTag("MILF", "milf", ContentParserSource.AKIH),
            ContentTag("NTR", "ntr", ContentParserSource.AKIH),
            ContentTag("Rape", "rape", ContentParserSource.AKIH),
            ContentTag("Schoolgirl", "schoolgirl", ContentParserSource.AKIH),
            ContentTag("Succubus", "succubus", ContentParserSource.AKIH),
            ContentTag("Tentacles", "tentacles", ContentParserSource.AKIH),
            ContentTag("Threesome", "threesome", ContentParserSource.AKIH),
            ContentTag("Yaoi", "yaoi", ContentParserSource.AKIH),
            ContentTag("Yuri", "yuri", ContentParserSource.AKIH),
        )
    }
}
