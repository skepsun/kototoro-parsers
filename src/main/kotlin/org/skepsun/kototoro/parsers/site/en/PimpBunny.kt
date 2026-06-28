package org.skepsun.kototoro.parsers.site.en

import org.json.JSONObject
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

@ContentSourceParser("PIMPBUNNY", "PimpBunny", "en", type = ContentType.HENTAI_VIDEO)
internal class PimpBunny(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.PIMPBUNNY, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("pimpbunny.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val cats = listOf(
            "Exclusive" to "exclusive", "BBC" to "bbc", "Anal" to "anal",
            "Deep Throat" to "deep-throat", "BDSM" to "bdsm", "Bizarre Porn" to "bizarre-porn",
            "Double Penetration" to "double-penetration", "Feet" to "feet", "Fetish" to "fetish",
            "Gang Bang" to "gang-bang", "Lesbian" to "lesbian", "Outdoor" to "outdoor",
            "Blowjob" to "blowjob", "Creampie" to "creampie", "Threesome" to "threesome",
            "Solo" to "solo", "MILF" to "milf", "Teen" to "teen",
            "Cosplay" to "cosplay", "Massage" to "massage", "POV" to "pov",
            "Big Boobs" to "big-boobs", "Interracial" to "interracial", "Public" to "public",
        ).map { ContentTag(it.first, it.second, source) }.toSet()
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = cats,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        logDebug("list url=$url page=$page order=$order query=${filter.query}")
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        logDebug("details url=${manga.publicUrl}")
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("div.pages-view-video-video-title__9lYVyi")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("div.pages-view-video-description__CuSQws")?.text()?.trim()

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst("img.pages-view-video-thumb, img[data-original]")?.let {
                (it.attr("data-original").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }

        val ldJson = doc.selectFirst("script[type=\"application/ld+json\"]")?.data()
        val contentUrl = ldJson?.let {
            runCatching { JSONObject(it).optString("contentUrl", "") }.getOrElse { "" }
        }.orEmpty()

        val videoId = doc.selectFirst("script:containsData(pageContext)")?.data()
            ?.let { Regex("videoId:\\s*'([^']+)'").find(it)?.groupValues?.get(1) }
            ?: manga.url.substringAfterLast("-").ifBlank { manga.url }

        val authors = mutableSetOf<String>()
        doc.select("div.pages-view-video-model-title__jPOPZM a").forEach { a ->
            val name = a.text().trim().ifEmpty { a.attr("href").substringAfterLast("/").replace("-", " ").trim() }
            if (name.isNotBlank()) authors.add(name)
        }

        val categories = mutableSetOf<ContentTag>()
        val tagSection = doc.select("ul.pages-view-video-categories__OWVJKQ").firstOrNull {
            it.selectFirst("span.ui-text-bold__NmZm1L")?.text() == "Categories"
        }
        tagSection?.select("a[href*=/categories/]")?.forEach { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/categories/").substringBefore("/").trim()
            if (text.isNotEmpty() && key.isNotEmpty()) {
                categories.add(ContentTag(text, key, source))
            }
        }

        val tags = mutableSetOf<ContentTag>()
        val tagSectionTags = doc.select("ul.pages-view-video-categories__OWVJKQ").firstOrNull {
            it.selectFirst("span.ui-text-bold__NmZm1L")?.text() == "Tags"
        }
        tagSectionTags?.select("a[href*=/tags/]")?.forEach { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/tags/").substringBefore("/").trim()
            if (text.isNotEmpty() && key.isNotEmpty()) {
                tags.add(ContentTag(text, key, source))
            }
        }

        if (categories.isEmpty()) {
            doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.forEach { kw ->
                val trimmed = kw.trim()
                if (trimmed.isNotBlank()) {
                    categories.add(ContentTag(trimmed, trimmed.lowercase().replace(" ", "-"), source))
                }
            }
        }

        val allTags = (categories + tags).toSet()

        val chapter = ContentChapter(
            id = generateUid("${manga.url}|$videoId"),
            url = contentUrl.ifBlank { manga.url },
            title = if (contentUrl.isNotBlank()) "Video" else "Watch",
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
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            tags = if (allTags.isNotEmpty()) allTags else manga.tags,
            contentRating = ContentRating.ADULT,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val videoUrl = chapter.url.takeIf { it.startsWith("http") }
        if (videoUrl != null) {
            return listOf(
                ContentPage(
                    id = generateUid("page:${chapter.id}"),
                    url = videoUrl,
                    preview = null,
                    source = source,
                )
            )
        }

        logDebug("pages fallback to detail page url=${chapter.url}")
        val detailUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(detailUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val ldJson = doc.selectFirst("script[type=\"application/ld+json\"]")?.data()
        val contentUrl = ldJson?.let {
            runCatching { JSONObject(it).optString("contentUrl", "") }.getOrElse { "" }
        }.orEmpty()

        if (contentUrl.isBlank()) {
            val videoSources = doc.select("video source[src], video[src]")
            videoSources.forEach { el ->
                val src = el.attr("src").ifBlank { el.attr("data-src") }
                if (src.startsWith("http")) {
                    return listOf(
                        ContentPage(
                            id = generateUid("page:${chapter.id}"),
                            url = src,
                            preview = null,
                            source = source,
                        )
                    )
                }
            }
            return emptyList()
        }

        return listOf(
            ContentPage(
                id = generateUid("page:${chapter.id}"),
                url = contentUrl,
                preview = null,
                source = source,
            )
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val tag = filter.tags.firstOrNull()?.key
        return when {
            !filter.query.isNullOrBlank() -> {
                val q = filter.query.urlEncoded()
                "https://$domain/search/?q=$q&page=$page"
            }
            tag != null -> "https://$domain/categories/$tag/?page=$page"
            order == SortOrder.POPULARITY -> "https://$domain/videos/?sort_by=video_viewed&page=$page"
            order == SortOrder.RATING -> "https://$domain/videos/?sort_by=rating&page=$page"
            else -> "https://$domain/?page=$page"
        }
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()

        val cards = doc.select("a.ui-card-link__KxRw6l[href*=/videos/]")
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            if (!href.contains("/videos/")) continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue

            val title = link.selectFirst("div.ui-card-title__igirYJ")?.text()?.trim()
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: "Untitled"

            val thumb = link.selectFirst("img[data-original], img[src]")?.let {
                val raw = it.attr("data-original").ifBlank { it.attr("src") }
                if (raw.startsWith("data:")) null
                else raw.toAbsoluteUrlOrNull(domain)
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
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
        }

        logDebug("parsed ${items.size} items")
        return items
    }

    private fun logDebug(message: String) {
        runCatching { println("[PimpBunny] $message") }
    }
}
