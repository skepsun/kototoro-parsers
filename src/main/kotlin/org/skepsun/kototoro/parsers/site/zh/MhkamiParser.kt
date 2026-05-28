package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
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
@ContentSourceParser(name = "MHKAMI", title = "漫神", locale = "zh", type = ContentType.HENTAI_MANGA)
internal class MhkamiParser(
    context: ContentLoaderContext,
) : PagedContentParser(
    context = context,
    source = ContentParserSource.MHKAMI,
    pageSize = 30,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("m.mhkami.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities =
        ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val plotTags = PLOT_TAGS.mapTo(linkedSetOf()) { (title, value) -> ContentTag(title, "tag:$value", source) }
        val areaTags = AREA_TAGS.mapTo(linkedSetOf()) { (title, value) -> ContentTag(title, "area:$value", source) }
        val fullTags = FULL_TAGS.mapTo(linkedSetOf()) { (title, value) -> ContentTag(title, "full:$value", source) }
        val updateTags = UPDATE_TAGS.mapTo(linkedSetOf()) { (title, value) -> ContentTag(title, "update:$value", source) }
        return ContentListFilterOptions(
            availableTags = (plotTags + areaTags + fullTags + updateTags).toSet(),
            tagGroups = listOf(
                ContentTagGroup("按剧情", plotTags, isExclusive = true),
                ContentTagGroup("按地区", areaTags, isExclusive = true),
                ContentTagGroup("按进度", fullTags, isExclusive = true),
                ContentTagGroup("独立列表：按天更新", updateTags, isExclusive = true),
            ),
            availableContentRating = EnumSet.of(ContentRating.ADULT),
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", MOBILE_UA)
        .add("Referer", "https://$domain/")
        .build()

    private fun baseUrl(): String = "https://$domain"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim()
        if (!query.isNullOrEmpty()) {
            if (page > 1) return emptyList()
            val url = "${baseUrl()}/search?searchkey=${query.urlEncoded()}"
            val doc = webClient.httpGet(url, searchHeaders()).parseHtml()
            return parseList(doc, isSearch = true)
        }

        val tagMap = filter.tags.associate { it.key.substringBefore(":") to it.key.substringAfter(":") }
        val updateDay = tagMap["update"]?.toIntOrNull() ?: 0
        val url = if (updateDay in 1..7) {
            // 按天更新是站点单独列表，不与常规筛选组合。
            if (page > 1) return emptyList()
            "${baseUrl()}/update/$updateDay.html"
        } else {
            val area = tagMap["area"]?.takeIf { it.isNotBlank() } ?: DEFAULT_AREA
            val plot = tagMap["tag"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PLOT_TAG
            val full = tagMap["full"]?.takeIf { it.isNotBlank() } ?: DEFAULT_FULL
            "${baseUrl()}/mangalists/$area/${plot.urlEncoded()}/$full/$page.html"
        }
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc, isSearch = false)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst(".book-name .name, .acgn-container .content .title, .content .title")
            ?.ownText()
            ?.trim()
            ?.ifEmpty { null }
            ?: manga.title

        val cover = parseCover(doc) ?: manga.coverUrl
        val description = doc.selectFirst("#js_desc_content, .intro-text-wrapper, .acgn-container .content .desc, .content .desc")
            ?.text()
            ?.trim()
            ?.ifEmpty { null }
            ?: manga.description

        val tags = doc.select(".comic-info-detail .types a.type, .types a.type, .acgn-container .content .tags a, .acgn-container .content .sort a")
            .mapNotNull { it.text().trim().ifEmpty { null } }
            .toSet()
            .map { ContentTag(it, it, source) }
            .toSet()

        val chapters = doc.select("#js_chapter_list li a, #js_chapters li a, #j_chapter_list li a, #J_chapter_list li a")
            .mapIndexedNotNull { index, a ->
                val href = a.attr("href").trim()
                val chapterUrl = href.takeIf { it.isNotEmpty() }?.toAbsoluteUrl(domain) ?: return@mapIndexedNotNull null
                val chapterTitle = a.attr("title").trim().ifEmpty { a.text().trim() }.ifEmpty { "第${index + 1}话" }
                ContentChapter(
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

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
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
        val pageHeaders = imageHeaderMap()
        return imageUrls.mapIndexed { index, full ->
            ContentPage(
                id = generateUid("$full#$index"),
                url = full,
                preview = full,
                headers = pageHeaders,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private fun parseList(doc: Document, isSearch: Boolean): List<Content> {
        val items = if (isSearch) {
            doc.select("#js_comicSortList li, #J_comicSortList li")
        } else {
            doc.select("#js_comicSortList li, #J_comicSortList li, .update-list li.item, .comic-sort li.item")
        }

        return items.mapNotNull { li ->
            val anchor = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            val title = li.selectFirst(".acgn-title, .title")?.text()?.trim().orEmpty()
                .ifEmpty { anchor.text().trim() }
            val cover = li.selectFirst("img")?.attr("src")?.trim()?.ifEmpty { null }
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
            val publicUrl = href.toAbsoluteUrl(domain)
            Content(
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
        val style = doc.selectFirst(".comic-header, .acgn-container .detail-cover img, .detail-cover img")
            ?.attr("style")
            ?.trim()
            ?.ifEmpty { null }
        val fromStyle = style
            ?.let { COVER_STYLE_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?.toAbsoluteUrl(domain)
        if (!fromStyle.isNullOrEmpty()) return fromStyle

        return doc.selectFirst(".book-cover img, .acgn-container .detail-cover img, .detail-cover img")
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
            .mapNotNull(::extractImageUrl)
    }

    private fun extractImageUrl(img: Element): String? {
        val candidates = listOf(
            "data-src",
            "data-original",
            "data-echo",
            "data-lazy-src",
            "data-url",
            "src",
        ).mapNotNull { attr ->
            img.attr(attr)
                .trim()
                .ifEmpty { null }
                ?.toAbsoluteUrl(domain)
        }
        return candidates.firstOrNull { !isPlaceholderImage(it) }
            ?: candidates.firstOrNull()
    }

    private fun isPlaceholderImage(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("/static/boodo/img/load.gif") ||
            normalized.endsWith("/load.gif") ||
            normalized.endsWith("/loading.gif") ||
            normalized.contains("/placeholder/")
    }

    private fun toChapterIndexUrl(chapterUrl: String): String {
        val absolute = chapterUrl.toAbsoluteUrl(domain)
        if (INDEX_PAGE_REGEX.containsMatchIn(absolute)) return absolute
        return absolute.replace(".html", "_10.html")
    }

    private fun searchHeaders(): Headers = Headers.Builder()
        .add("User-Agent", MOBILE_UA)
        .add("Referer", "https://$domain/")
        .build()

    private fun imageHeaders(): Headers = Headers.Builder()
        .add("User-Agent", IMAGE_UA)
        .add("Referer", "https://$domain/")
        .build()

    private fun imageHeaderMap(): Map<String, String> = mapOf(
        "User-Agent" to IMAGE_UA,
        "Referer" to "https://$domain/",
    )

    private companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; HarmonyOS; SCM-W09; HMSCore 6.6.0.332) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.93 HuaweiBrowser/11.1.2.332 Mobile Safari/537.36"
        private const val IMAGE_UA =
            "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Mobile Safari/537.36"

        private const val DEFAULT_AREA = "9"
        private const val DEFAULT_PLOT_TAG = "全部"
        private const val DEFAULT_FULL = "3"
        private const val MAX_PAGE_SCAN = 200
        private val INDEX_PAGE_REGEX = Regex("""_\d+\.html$""")
        private val COVER_STYLE_REGEX = Regex("""url\(['"]?([^'")]+)['"]?\)""")

        private val PLOT_TAGS = listOf(
            "全部" to "全部",
            "长条" to "长条",
            "大女主" to "大女主",
            "百合" to "百合",
            "耽美" to "耽美",
            "纯爱" to "纯爱",
            "後宫" to "後宫",
            "韩漫" to "韩漫",
            "奇幻" to "奇幻",
            "轻小说" to "轻小说",
            "生活" to "生活",
            "悬疑" to "悬疑",
            "格斗" to "格斗",
            "搞笑" to "搞笑",
            "伪娘" to "伪娘",
            "竞技" to "竞技",
            "职场" to "职场",
            "萌系" to "萌系",
            "冒险" to "冒险",
            "治愈" to "治愈",
            "都市" to "都市",
            "霸总" to "霸总",
            "神鬼" to "神鬼",
            "侦探" to "侦探",
            "爱情" to "爱情",
            "古风" to "古风",
            "欢乐向" to "欢乐向",
            "科幻" to "科幻",
            "穿越" to "穿越",
            "性转换" to "性转换",
            "校园" to "校园",
            "美食" to "美食",
            "剧情" to "剧情",
            "热血" to "热血",
            "节操" to "节操",
            "励志" to "励志",
            "异世界" to "异世界",
            "历史" to "历史",
            "战争" to "战争",
            "恐怖" to "恐怖",
        )
        private val AREA_TAGS = listOf(
            "全部" to "9",
            "日漫" to "1",
            "港台" to "2",
            "美漫" to "3",
            "国漫" to "4",
            "韩漫" to "5",
            "未分类" to "6",
        )
        private val FULL_TAGS = listOf(
            "全部" to "3",
            "连载中" to "4",
            "已完结" to "1",
        )
        private val UPDATE_TAGS = listOf(
            "周一更新" to "1",
            "周二更新" to "2",
            "周三更新" to "3",
            "周四更新" to "4",
            "周五更新" to "5",
            "周六更新" to "6",
            "周日更新" to "7",
        )
    }
}
