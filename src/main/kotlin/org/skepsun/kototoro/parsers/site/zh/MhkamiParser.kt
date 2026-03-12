package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
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
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * m.mhkami.com
 * 规则映射参考用户提供的 resolver（列表、搜索、详情、章节分页）
 */
@MangaSourceParser(name = "MHKAMI", title = "MHKami", locale = "zh", type = ContentType.HENTAI_MANGA)
internal class MhkamiParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.MHKAMI,
    pageSize = 30,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("m.mhkami.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LIST_TYPES.map { (title, id) -> MangaTag(title, "list:$id", source) }.toSet()
        return MangaListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(MangaTagGroup("列表", tags)),
            availableContentRating = EnumSet.of(ContentRating.ADULT),
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", LIST_UA)
        .add("Referer", "https://$domain/")
        .build()

    private fun baseUrl(): String = "https://$domain"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()
        if (!query.isNullOrEmpty()) {
            if (page > 1) return emptyList()
            val url = "${baseUrl()}/search?searchkey=${query.urlEncoded()}"
            val doc = webClient.httpGet(url, searchHeaders()).parseHtml()
            return parseList(doc, isSearch = true)
        }

        val listType = filter.tags.firstOrNull { it.key.startsWith("list:") }
            ?.key?.substringAfter("list:")
            ?.takeIf { it.isNotBlank() }
            ?: order.toDefaultListType()
        val url = "${baseUrl()}/mangalists/9/全部/$listType/$page.html"
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc, isSearch = false)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst(".acgn-container .content .title, .content .title")
            ?.text()
            ?.trim()
            ?.ifEmpty { null }
            ?: manga.title

        val cover = parseCover(doc) ?: manga.coverUrl
        val description = doc.selectFirst(".acgn-container .content .desc, .content .desc")
            ?.text()
            ?.trim()
            ?.ifEmpty { null }
            ?: manga.description

        val tags = doc.select(".acgn-container .content .tags a, .acgn-container .content .sort a")
            .mapNotNull { it.text().trim().ifEmpty { null } }
            .toSet()
            .map { MangaTag(it, it, source) }
            .toSet()

        val chapters = doc.select("#j_chapter_list li a, #J_chapter_list li a")
            .mapIndexedNotNull { index, a ->
                val href = a.attr("href").trim()
                val chapterUrl = href.takeIf { it.isNotEmpty() }?.toAbsoluteUrl(domain) ?: return@mapIndexedNotNull null
                val chapterTitle = a.text().trim().ifEmpty { "第${index + 1}话" }
                MangaChapter(
                    id = generateUid("${manga.id}|$chapterUrl"),
                    url = chapterUrl,
                    title = chapterTitle,
                    number = (index + 1).toFloat(),
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val start = toChapterIndexUrl(chapter.url)
        val pageUrls = traverseChapterPages(start)
        if (pageUrls.isEmpty()) return emptyList()

        val imageUrls = LinkedHashSet<String>(pageUrls.size * 2)
        pageUrls.forEach { pageUrl ->
            val doc = webClient.httpGet(pageUrl, imageHeaders()).parseHtml()
            imageUrls.addAll(extractChapterImages(doc))
        }
        if (imageUrls.size <= 1) {
            val fallbackDoc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), imageHeaders()).parseHtml()
            imageUrls.addAll(extractChapterImages(fallbackDoc))
        }
        return imageUrls.mapIndexed { index, full ->
            MangaPage(
                id = generateUid("$full#$index"),
                url = "$IMAGE_PROXY?url=${full.urlEncoded()}",
                preview = full,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private fun parseList(doc: Document, isSearch: Boolean): List<Manga> {
        val items = if (isSearch) {
            doc.select("#js_comicSortList li, #J_comicSortList li")
        } else {
            doc.select("#J_comicListBox ul li, #J_comicListBox li")
        }

        return items.mapNotNull { li ->
            val anchor = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            val title = li.selectFirst(".acgn-title, .title")?.text()?.trim().orEmpty()
                .ifEmpty { anchor.text().trim() }
            val cover = li.selectFirst("img")?.attr("src")?.trim()?.ifEmpty { null }
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
            val publicUrl = href.toAbsoluteUrl(domain)
            Manga(
                id = generateUid(publicUrl),
                url = publicUrl,
                publicUrl = publicUrl,
                title = title,
                coverUrl = cover?.toAbsoluteUrl(domain),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                chapters = null,
                source = source,
            )
        }
    }

    private fun parseCover(doc: Document): String? {
        val style = doc.selectFirst(".acgn-container .detail-cover img, .detail-cover img")
            ?.attr("style")
            ?.trim()
            ?.ifEmpty { null }
        val fromStyle = style
            ?.let { COVER_STYLE_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?.toAbsoluteUrl(domain)
        if (!fromStyle.isNullOrEmpty()) return fromStyle

        return doc.selectFirst(".acgn-container .detail-cover img, .detail-cover img")
            ?.attr("src")
            ?.trim()
            ?.ifEmpty { null }
            ?.toAbsoluteUrl(domain)
    }

    private suspend fun traverseChapterPages(startUrl: String): List<String> {
        val visited = LinkedHashSet<String>()
        val ordered = ArrayList<String>()
        var current = startUrl
        var guard = 0
        while (guard < MAX_PAGE_SCAN && visited.add(current)) {
            ordered.add(current)
            val doc = webClient.httpGet(current, imageHeaders()).parseHtml()
            val prevHref = doc.selectFirst(".tooltip-bar.bottomMenu .tooltip-bar__row .prev a, .bottomMenu .prev a, .prev a")
                ?.attr("href")
                ?.trim()
                ?.ifEmpty { null }
                ?.toAbsoluteUrl(domain)
                ?: break
            current = prevHref
            guard++
        }
        ordered.reverse()
        return ordered
    }

    private fun extractChapterImages(doc: Document): List<String> {
        return doc.select(".acgn-reader-chapter__item-box .item img, .item img")
            .mapNotNull { img ->
                img.attr("data-src")
                    .trim()
                    .ifEmpty { img.attr("src").trim() }
                    .ifEmpty { null }
                    ?.toAbsoluteUrl(domain)
            }
    }

    private fun toChapterIndexUrl(chapterUrl: String): String {
        val absolute = chapterUrl.toAbsoluteUrl(domain)
        if (INDEX_PAGE_REGEX.containsMatchIn(absolute)) return absolute
        return absolute.replace(".html", "_10.html")
    }

    private fun SortOrder.toDefaultListType(): String = when (this) {
        SortOrder.NEWEST -> "4"
        else -> "3"
    }

    private fun searchHeaders(): Headers = Headers.Builder()
        .add("User-Agent", SEARCH_UA)
        .add("Referer", "https://$domain/")
        .build()

    private fun imageHeaders(): Headers = Headers.Builder()
        .add("User-Agent", IMAGE_UA)
        .add("Referer", "https://$domain/")
        .build()

    private companion object {
        private const val IMAGE_PROXY = "https://image.44422444.xyz/"
        private const val LIST_UA = "PostmanRuntime/7.37.3"
        private const val SEARCH_UA =
            "Mozilla/5.0 (Linux; Android 10; HarmonyOS; SCM-W09; HMSCore 6.6.0.332) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.93 HuaweiBrowser/11.1.2.332 Mobile Safari/537.36"
        private const val IMAGE_UA =
            "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Mobile Safari/537.36"

        private const val MAX_PAGE_SCAN = 200
        private val INDEX_PAGE_REGEX = Regex("""_\d+\.html$""")
        private val COVER_STYLE_REGEX = Regex("""url\(['"]?([^'")]+)['"]?\)""")

        private val LIST_TYPES = listOf(
            "更新" to "3",
            "连载" to "4",
            "完结" to "1",
        )
    }
}
