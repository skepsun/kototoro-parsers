package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

/**
 * Erome - 成人视频/相册聚合站
 *
 * 站点: https://www.erome.com/
 *
 * 支持:
 * - 搜索: /search?q=keyword&page=n
 * - 探索: /explore?page=n
 * - 详情页提取视频 source/og:video
 */
@MangaSourceParser("EROME", "Erome", "en", type = ContentType.HENTAI_VIDEO)
internal class Erome(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.EROME, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("www.erome.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, filter)
        logDebug("Erome list url=$url page=$page query=${filter.query}")
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        logDebug("Erome details url=${manga.publicUrl}")
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("p.description, .desc, .description")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst("video[poster]")?.attr("poster")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst("img.album-thumbnail, img.img-front, img[data-src]")?.let {
                (it.attr("data-src").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }

        val tags = doc.select("a.album-tag").mapNotNull {
            val text = it.text().trim().removePrefix("#").trim()
            val key = it.attr("href").substringAfter("search?q=").substringBefore("&").trim()
            if (text.isNotEmpty() && key.isNotEmpty()) MangaTag(text, key, source) else null
        }.toSet()

        val chapter = MangaChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            contentRating = ContentRating.ADULT,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        logDebug("Erome pages url=$chapterUrl")
        val response = webClient.httpGet(chapterUrl, getRequestHeaders())
        val doc = response.parseHtml()
        val media = extractMedia(doc)
        if (media.isEmpty()) {
            context.requestBrowserAction(this, chapterUrl)
            return emptyList()
        }
        return media.mapIndexed { index, mediaItem ->
            MangaPage(
                id = generateUid("${chapterUrl}#${index}"),
                url = mediaItem.url,
                preview = mediaItem.preview,
                source = source,
            )
        }
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildListUrl(page: Int, filter: MangaListFilter): String {
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?q=$q&page=$page"
        } else {
            "https://$domain/explore?page=$page"
        }
    }

    private fun parseList(doc: Document): List<Manga> {
        val items = ArrayList<Manga>()
        val seen = LinkedHashSet<String>()

        // 相册卡片：a.album-card 或 .album-thumbnail 的父级链接，href 包含 /a/{id}
        val cards = doc.select("a.album-card[href*=/a/], a[href*=/a/] img.album-thumbnail").mapNotNull {
            if (it.hasAttr("href")) it else it.parent()
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            if (!href.contains("/a/")) continue
            val absoluteUrl = href.toAbsoluteUrl(domain)
            if (!seen.add(absoluteUrl)) continue

            val titleFromAttr = link.attr("title").takeIf { it.isNotBlank() }
            val titleFromImgAlt = link.selectFirst("img[alt]")?.attr("alt")?.let { alt ->
                alt.substringBefore("#").trim().ifEmpty { null }
            }
            val title = titleFromAttr
                ?: titleFromImgAlt
                ?: link.text().trim().ifEmpty { "Untitled" }

            val thumb = link.selectFirst("img[data-src], img[src]")?.let {
                (it.attr("data-src").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }

            items.add(
                Manga(
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
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
        }

        logDebug("Erome parsed ${items.size} items")
        return items
    }

    private data class Media(val url: String, val preview: String?)

    private fun extractMedia(doc: Document): List<Media> {
        val items = linkedSetOf<Media>()

        // 视频 source
        doc.select("video source[src]").forEach { source ->
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val preview = source.parent()?.closest("video")?.attr("poster")?.toAbsoluteUrlOrNull(domain)
            items += Media(src.toAbsoluteUrl(domain), preview)
        }

        doc.select("meta[property=og:video], meta[property=og:video:secure_url]").forEach { meta ->
            val src = meta.attr("content").takeIf { it.isNotBlank() } ?: return@forEach
            items += Media(src.toAbsoluteUrl(domain), null)
        }

        // 图片
        doc.select("div.media-group .img[data-src], div.media-group img[data-src], div.media-group img[src]").forEach { img ->
            val raw = img.attr("data-src").ifBlank { img.attr("src") }
            val src = raw.takeIf { it.isNotBlank() } ?: return@forEach
            items += Media(src.toAbsoluteUrl(domain), src.toAbsoluteUrl(domain))
        }

        return items.toList()
    }

    private fun logDebug(message: String) {
        runCatching { println("[Erome] $message") }
    }
}
