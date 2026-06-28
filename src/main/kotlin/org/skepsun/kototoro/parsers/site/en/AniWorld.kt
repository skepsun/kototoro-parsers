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

@ContentSourceParser("ANIWORLD", "AniWorld", "en", type = ContentType.VIDEO)
internal class AniWorld(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ANIWORLD, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("aniworld.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        try {
            val doc = webClient.httpGet("https://$domain/", getRequestHeaders()).parseHtml()
            val genres = doc.select("a[href*=/genre/]").mapNotNull { a ->
                val text = a.text().trim()
                val key = a.attr("href").substringAfter("/genre/").trimEnd('/')
                if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
            }.toSet()
            return ContentListFilterOptions(
                availableContentTypes = EnumSet.of(ContentType.VIDEO),
                availableTags = genres,
            )
        } catch (e: Exception) {
            return ContentListFilterOptions(
                availableContentTypes = EnumSet.of(ContentType.VIDEO),
            )
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val chapters = doc.select("a[href*=/episode-]").mapIndexed { index, el ->
            val epUrl = el.attr("abs:href").takeIf { it.isNotBlank() }
                ?: el.attr("href").toAbsoluteUrl(domain)
            val epNum = el.text().trim().filter { it.isDigit() }.ifEmpty { (index + 1).toString() }
            ContentChapter(
                id = generateUid(epUrl),
                url = epUrl.removePrefix("https://$domain"),
                title = "Episode $epNum", number = index + 1f,
                uploadDate = 0L, volume = 0, branch = null, scanlator = null, source = source,
            )
        }.toList()

        return manga.copy(
            title = title, description = description ?: manga.description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
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
        val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()

        val pages = mutableListOf<ContentPage>()

        val serverLinks = doc.select("[data-link-target]")
        serverLinks.forEach { li ->
            val redirectPath = li.attr("data-link-target")
            val langKey = li.attr("data-lang-key")
            val langName = when (langKey) {
                "1" -> "GerSub"
                "2" -> "EngSub"
                "3" -> "Dub"
                else -> "Source"
            }
            if (redirectPath.isNotBlank()) {
                val redirectUrl = redirectPath.toAbsoluteUrl(domain)
                try {
                    val redirectDoc = webClient.httpGet(redirectUrl, getRequestHeaders()).parseHtml()
                    val sources = extractVideoSources(redirectDoc)
                    if (sources.isNotEmpty()) {
                        sources.forEach { src ->
                            pages.add(ContentPage(
                                id = generateUid("${chapter.id}|$langKey|${src.hashCode()}"),
                                url = src,
                                preview = langName,
                                source = source,
                            ))
                        }
                    } else {
                        pages.add(ContentPage(
                            id = generateUid("${chapter.id}|$langKey"),
                            url = redirectUrl,
                            preview = langName,
                            source = source,
                        ))
                    }
                } catch (_: Exception) {
                    pages.add(ContentPage(
                        id = generateUid("${chapter.id}|$langKey"),
                        url = redirectUrl,
                        preview = langName,
                        source = source,
                    ))
                }
            }
        }

        if (pages.isNotEmpty()) return pages

        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
        if (iframeSrc != null && iframeSrc.startsWith("http")) {
            return listOf(ContentPage(
                id = generateUid(chapter.id), url = iframeSrc,
                preview = null, source = source,
            ))
        }

        context.requestBrowserAction(this, chapterUrl)
        return emptyList()
    }

    private fun extractVideoSources(doc: Document): List<String> {
        val sources = LinkedHashSet<String>()

        doc.select("iframe[src]").forEach { src ->
            src.attr("src").takeIf { it.isNotBlank() }?.let { sources.add(it.toAbsoluteUrl(domain)) }
        }
        doc.select("source[src]").forEach { src ->
            src.attr("src").takeIf { it.isNotBlank() }?.let { sources.add(it.toAbsoluteUrl(domain)) }
        }
        doc.select("video source[src]").forEach { src ->
            src.attr("src").takeIf { it.isNotBlank() }?.let { sources.add(it.toAbsoluteUrl(domain)) }
        }
        doc.select("video[src]").forEach { v ->
            v.attr("src").takeIf { it.isNotBlank() }?.let { sources.add(it.toAbsoluteUrl(domain)) }
        }

        val html = doc.outerHtml()
        Regex("https?://[^\"'\\s>]+\\.(?:m3u8|mp4)", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            sources.add(m.value)
        }

        return sources.toList()
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent()).build()

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "https://$domain/search?q=$q&page=$page"
        } else {
            val tag = filter.tags.firstOrNull()?.key ?: ""
            val base = if (tag.isNotEmpty()) "https://$domain/genre/$tag" else "https://$domain/"
            "$base?page=$page"
        }
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
