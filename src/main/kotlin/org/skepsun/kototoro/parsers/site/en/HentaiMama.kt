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

@ContentSourceParser("HENTAIMAMA", "HentaiMama", "en", type = ContentType.HENTAI_VIDEO)
internal class HentaiMama(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HENTAIMAMA, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("hentaimama.io")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = true)

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
        availableTags = HENTAI_GENRES,
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
        val tags = doc.select("a[href*=/tag/], a[href*=/category/], a[href*=/genre/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfterLast("/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key.lowercase(), source) else null
        }.toSet()
        return manga.copy(
            title = title, description = description ?: manga.description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            contentRating = ContentRating.ADULT,
            chapters = listOf(ContentChapter(
                id = generateUid(manga.url), url = manga.url, title = "Watch",
                number = 1f, uploadDate = 0L, volume = 0, branch = null, scanlator = null, source = source,
            )),
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
        for (link in doc.select("a[href*=/video/], a[href*=/hentai/], .hentai-item a[href], .video-item a[href]")) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val container = link.parents().firstOrNull { p ->
                p.hasClass("hentai-item") || p.hasClass("video-item") || p.hasClass("card")
            } ?: link.parent()!!
            val title = container.selectFirst("h3, .title, .hentai-title")?.text()?.trim()
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = container.selectFirst("img[src], img[data-src]")?.let {
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

    private fun buildListUrl(page: Int, filter: ContentListFilter): String {
        val tagParam = filter.tags.joinToString(",") { it.key }
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?q=$q&genre=$tagParam&page=$page"
        } else {
            "https://$domain/videos?genre=$tagParam&page=$page"
        }
    }

    private companion object {
        val HENTAI_GENRES = setOf(
            ContentTag("Ahegao", "ahegao", ContentParserSource.HENTAIMAMA),
            ContentTag("Anal", "anal", ContentParserSource.HENTAIMAMA),
            ContentTag("Big Boobs", "big-boobs", ContentParserSource.HENTAIMAMA),
            ContentTag("Blowjob", "blowjob", ContentParserSource.HENTAIMAMA),
            ContentTag("Bondage", "bondage", ContentParserSource.HENTAIMAMA),
            ContentTag("Creampie", "creampie", ContentParserSource.HENTAIMAMA),
            ContentTag("Futanari", "futanari", ContentParserSource.HENTAIMAMA),
            ContentTag("Gangbang", "gangbang", ContentParserSource.HENTAIMAMA),
            ContentTag("Harem", "harem", ContentParserSource.HENTAIMAMA),
            ContentTag("Incest", "incest", ContentParserSource.HENTAIMAMA),
            ContentTag("Loli", "loli", ContentParserSource.HENTAIMAMA),
            ContentTag("Maid", "maid", ContentParserSource.HENTAIMAMA),
            ContentTag("Masturbation", "masturbation", ContentParserSource.HENTAIMAMA),
            ContentTag("MILF", "milf", ContentParserSource.HENTAIMAMA),
            ContentTag("NTR", "ntr", ContentParserSource.HENTAIMAMA),
            ContentTag("Rape", "rape", ContentParserSource.HENTAIMAMA),
            ContentTag("Schoolgirl", "schoolgirl", ContentParserSource.HENTAIMAMA),
            ContentTag("Succubus", "succubus", ContentParserSource.HENTAIMAMA),
            ContentTag("Tentacles", "tentacles", ContentParserSource.HENTAIMAMA),
            ContentTag("Threesome", "threesome", ContentParserSource.HENTAIMAMA),
            ContentTag("Yaoi", "yaoi", ContentParserSource.HENTAIMAMA),
            ContentTag("Yuri", "yuri", ContentParserSource.HENTAIMAMA),
            ContentTag("Monster", "monster", ContentParserSource.HENTAIMAMA),
            ContentTag("Femdom", "femdom", ContentParserSource.HENTAIMAMA),
        )
    }
}
