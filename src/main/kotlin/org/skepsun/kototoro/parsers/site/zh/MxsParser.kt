@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
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
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 漫小肆（多域名）
 */
@ContentSourceParser("MXS_", "漫小肆", "zh", type=ContentType.HENTAI_MANGA)
internal class MxsParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.MXS_, pageSize = 20) {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("www.mxshm.top")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)

    private val recommendTags = listOf(
        ContentTag("最近更新", "${REC_PREFIX}recent", source),
        ContentTag("排行榜", "${REC_PREFIX}rank", source),
        ContentTag("全部漫画", "${REC_PREFIX}all", source),
    )

    private val categoryTags = listOf(
        "都市", "校园", "青春", "性感", "长腿", "多人", "御姐", "巨乳",
        "新婚", "媳妇", "暧昧", "清纯", "调教", "少妇", "风骚", "同居",
        "淫乱", "好友", "女神", "诱惑", "偷情", "出轨", "正妹", "家教",
    ).map { ContentTag(it, "${CAT_PREFIX}$it", source) }

    private val rankTags = listOf(
        ContentTag("新书榜", "${RANK_PREFIX}new", source),
        ContentTag("人气榜", "${RANK_PREFIX}popular", source),
        ContentTag("完结榜", "${RANK_PREFIX}end", source),
        ContentTag("推荐榜", "${RANK_PREFIX}recommend", source),
    )

    private val areaTags = listOf(
        ContentTag("全部", "${AREA_PREFIX}-1", source),
        ContentTag("韩国", "${AREA_PREFIX}1", source),
        ContentTag("日本", "${AREA_PREFIX}2", source),
        ContentTag("台湾", "${AREA_PREFIX}3", source),
    )

    private val statusTags = listOf(
        ContentTag("全部", "${STATUS_PREFIX}-1", source),
        ContentTag("连载", "${STATUS_PREFIX}0", source),
        ContentTag("完结", "${STATUS_PREFIX}1", source),
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        ContentListFilterOptions(
            availableTags = (recommendTags + categoryTags + rankTags + areaTags + statusTags).toSet(),
            tagGroups = listOf(
                ContentTagGroup("推荐", recommendTags.toSet()),
                ContentTagGroup("题材", categoryTags.toSet()),
                ContentTagGroup("排行榜", rankTags.toSet()),
                ContentTagGroup("地区", areaTags.toSet()),
                ContentTagGroup("状态", statusTags.toSet()),
            ),
            availableContentRating = emptySet(),
        )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .build()

    private fun baseUrl(): String = "https://${config[configKeyDomain].removeSuffix("/")}"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val selection = filter.toSelection()
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        val url = when {
            selection.recommend == "rank" -> {
                if (page > 1) return emptyList()
                "${baseUrl()}/rank"
            }
            selection.category.isNotEmpty() || selection.recommend == "all" ||
                selection.area != "-1" || selection.status != "-1" -> {
                val tagParam = selection.category.ifEmpty { "全部" }.urlEncoded()
                "${baseUrl()}/booklist?tag=$tagParam&area=${selection.area}&end=${selection.status}&page=$page"
            }
            else -> "${baseUrl()}/update?page=$page"
        }
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return if (selection.recommend == "rank") {
            parseRankList(resp.parseHtml(), selection.rank)
        } else {
            parseComicList(resp.parseHtml())
        }
    }

    private suspend fun search(keyword: String, page: Int): List<Content> {
        if (page > 1) return emptyList()
        val url = "${baseUrl()}/search?keyword=${keyword.urlEncoded()}"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseComicList(resp.parseHtml())
    }

    private fun parseComicList(doc: Document): List<Content> {
        val items = doc.select(".mh-item").ifEmpty {
            doc.select(".mh-list.col7 .mh-item")
        }.ifEmpty {
            doc.select(".index-manga .mh-item")
        }
        return items.mapNotNull { item -> parseComicItem(item) }
    }

    private fun parseRankList(doc: Document, rankKey: String): List<Content> {
        val rankTitle = when (rankKey) {
            "popular" -> "人气榜"
            "end" -> "完结榜"
            "recommend" -> "推荐榜"
            else -> "新书榜"
        }
        val listElements = doc.select(".mh-list.col3.top-cat li")
        val target = listElements.firstOrNull { li ->
            li.selectFirst(".title")?.text()?.trim() == rankTitle
        } ?: return emptyList()
        val items = target.select(".mh-item.horizontal, .mh-itme-top, .mh-item")
        return items.mapNotNull { item -> parseComicItem(item) }
    }

    private fun parseComicItem(item: Element): Content? {
        val linkElem = item.selectFirst("a[href^=/book/]") ?: item.selectFirst(".cover a[href^=/book/]")
        val href = linkElem?.attr("href").orEmpty()
        val id = href.substringAfterLast("/")
        val title = item.selectFirst("p.title > a")?.text()?.trim()
            ?: item.selectFirst(".info .title a")?.text()?.trim()
            ?: item.selectFirst(".title a")?.text()?.trim()
            ?: ""
        val cover = item.selectFirst(".mh-cover img")?.attr("data-src")
            ?: item.selectFirst(".mh-cover img")?.attr("src")
            ?: item.selectFirst(".cover img")?.attr("src")
            ?: item.selectFirst("img")?.attr("data-src")
            ?: item.selectFirst("img")?.attr("src")
            ?: "${baseUrl()}/static/upload/book/$id/cover.jpg"
        if (id.isEmpty() || title.isEmpty()) return null
        return Content(
            id = generateUid(id),
            url = id,
            publicUrl = "${baseUrl()}/book/$id",
            coverUrl = cover,
            title = title,
            altTitles = emptySet(),
            rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = "${baseUrl()}/book/${manga.url}"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return manga
        val doc = resp.parseHtml()

        val title = doc.selectFirst(".info h1")?.text()?.trim().orEmpty().ifEmpty { manga.title }
        val subTitleElems = doc.select(".info .subtitle")
        var author = ""
        var alias = ""
        subTitleElems.forEach { elem ->
            val text = elem.text()
            if (text.contains("别名：")) alias = text.replace("别名：", "").trim()
            if (text.contains("作者：")) author = text.replace("作者：", "").trim()
        }
        val authors = author.split("&").mapNotNull { it.trim().ifEmpty { null } }.toSet()

        var status = ""
        var area = ""
        var updateTime = ""
        var clickCount = ""
        doc.select(".info .tip span").forEach { span ->
            val text = span.text()
            when {
                text.contains("状态：") -> status = span.selectFirst("span")?.text()?.trim().orEmpty()
                text.contains("地区：") -> area = span.selectFirst("a")?.text()?.trim().orEmpty()
                text.contains("更新时间：") -> updateTime = text.replace("更新时间：", "").trim()
                text.contains("点击：") -> clickCount = text.replace("点击：", "").trim()
            }
        }
        val description = doc.selectFirst(".info .content")?.text()?.trim().orEmpty()

        val tagList = doc.select(".info .tip a[href*='tag=']").mapNotNull { it.text()?.trim() }

        val chapters = doc.select("#detail-list-select li a").mapIndexedNotNull { index, a ->
            val chapterUrl = a.attr("href")
            val chapterTitle = a.text().trim()
            if (chapterUrl.isEmpty() || chapterTitle.isEmpty()) return@mapIndexedNotNull null
            val chapterId = chapterUrl.substringAfterLast("/")
            ContentChapter(
                id = generateUid("$chapterId-${manga.id}"),
                url = chapterId,
                title = chapterTitle,
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        val tagSet = buildSet {
            if (authors.isNotEmpty()) addAll(authors.map { ContentTag(it, it, source) })
            tagList.forEach { add(ContentTag(it, it, source)) }
            if (area.isNotEmpty()) add(ContentTag(area, area, source))
            if (status.isNotEmpty()) add(ContentTag(status, status, source))
        }

        return manga.copy(
            title = title,
            coverUrl = "${baseUrl()}/static/upload/book/${manga.url}/cover.jpg",
            description = description.ifEmpty { manga.description },
            tags = if (tagSet.isNotEmpty()) tagSet else manga.tags,
            chapters = chapters,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = "${baseUrl()}/chapter/${chapter.url}"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        val doc = resp.parseHtml()
        val images = doc.select("img.lazy").mapIndexedNotNull { index, img ->
            val src = img.attr("data-original").ifEmpty { img.attr("src") }
            if (src.isEmpty()) null else {
                val image = src.replace(Regex("^https?://[^/]+")) { baseUrl() }
                ContentPage(
                    id = generateUid("$image-$index"),
                    url = image,
                    preview = image,
                    source = source,
                )
            }
        }
        return images
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private fun ContentListFilter.toSelection(): FilterSelection {
        var recommend = "recent"
        var category = ""
        var rank = "new"
        var area = "-1"
        var status = "-1"
        var rankSelected = false
        tags.forEach { tag ->
            when {
                tag.key.startsWith(REC_PREFIX) -> recommend = tag.key.removePrefix(REC_PREFIX)
                tag.key.startsWith(CAT_PREFIX) -> category = tag.key.removePrefix(CAT_PREFIX)
                tag.key.startsWith(RANK_PREFIX) -> {
                    rank = tag.key.removePrefix(RANK_PREFIX)
                    rankSelected = true
                }
                tag.key.startsWith(AREA_PREFIX) -> area = tag.key.removePrefix(AREA_PREFIX)
                tag.key.startsWith(STATUS_PREFIX) -> status = tag.key.removePrefix(STATUS_PREFIX)
            }
        }
        if (rankSelected) {
            recommend = "rank"
        }
        return FilterSelection(recommend, category, rank, area, status)
    }

    private data class FilterSelection(
        val recommend: String,
        val category: String,
        val rank: String,
        val area: String,
        val status: String,
    )

    private companion object {
        private const val REC_PREFIX = "rec:"
        private const val CAT_PREFIX = "cat:"
        private const val RANK_PREFIX = "rank:"
        private const val AREA_PREFIX = "area:"
        private const val STATUS_PREFIX = "status:"
    }
}
