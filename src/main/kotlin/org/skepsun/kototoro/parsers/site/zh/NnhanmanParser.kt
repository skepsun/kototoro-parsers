@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.InternalParsersApi
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
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 鸟鸟韩漫（nnhanman）
 *
 * 台版繁体界面的韩漫汉化站，正文全 SSR，无签名接口、无加密混淆，翻页走路径段。
 * 站点会在页脚公告轮换域名，因此域名一律通过 [configKeyDomain] 提供：
 * `nnhanman.xyz`（默认）与公告中的 `nnhanman66.com / nnhanman88.com / nnhm81.com / nnhm92.com / nnhm91.com`。
 *
 * 列表与搜索都是同一份 `ul.col_3_1 > li` 卡片结构：
 * - 分类列表：`/comics/{题材}/ob/{排序}/st/{状态}`，第 2 页起追加 `/page/{n}`，每页 18 条；
 *   题材为 `all` 或站点中文题材（需 URL 编码后作为路径段），排序 `time` / `hits`，状态 `all` / `serialized` / `completed`。
 * - 搜索：`/catalog.php?key={关键词}&page={n}`（站点另有等价的 `/search/{关键词}/page/{n}` 路径形式）。
 *
 * 详情页的章节目录是**倒序**（最新章在最前），[parseChapters] 会反转成阅读顺序。
 * 阅读页图片是 `img[data-src]`（懒加载属性，不是 `data-original`），托管在独立图床 `img.nnpic.xyz`，
 * 与站点域名无关，无需改写。
 */
@ContentSourceParser("NNHANMAN", "鸟鸟韩漫", "zh", type = ContentType.HENTAI_MANGA)
internal class NnhanmanParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.NNHANMAN, pageSize = 18) {

    override val configKeyDomain = ConfigKey.Domain(
        "nnhanman.xyz",
        "nnhanman66.com",
        "nnhanman88.com",
        "nnhm81.com",
        "nnhm92.com",
        "nnhm91.com",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)

    /** 列表页默认就是「所有最新更新漫画」，不要落到枚举顺序第一位的热度排序上。 */
    override val defaultSortOrder: SortOrder = SortOrder.NEWEST

    private val genreTags = listOf(
        "正妹", "恋爱", "出版漫画", "肉慾", "浪漫", "大尺度", "巨乳", "有夫之婦",
        "女大生", "狗血劇", "同居", "好友", "調教", "动作", "後宮", "不倫",
        "3D", "校園", "耽美", "日漫",
    ).map { ContentTag(it, "$GENRE_PREFIX$it", source) }

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            // 站点只接受一个题材 + 一个状态，多选无对应语义
            isMultipleTagsSupported = false,
            isSearchSupported = true,
            // 站内搜索接口 /catalog.php 不接受题材与状态参数
            isSearchWithFiltersSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableTags = genreTags.toSet(),
        tagGroups = listOf(ContentTagGroup("题材", genreTags.toSet())),
        availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
        availableContentRating = emptySet(),
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .build()

    private fun baseUrl(): String = "https://${config[configKeyDomain].removeSuffix("/")}"

    private fun contentUrl(slug: String): String = "${baseUrl()}/comic/$slug.html"

    private fun readerUrl(chapter: ContentChapter): String = "${baseUrl()}/${chapter.url}"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = filter.query?.takeIf { it.isNotBlank() }?.let { searchUrl(it, page) }
            ?: buildListUrl(page, order, filter)
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseComicList(resp.parseHtml())
    }

    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val genre = filter.tags.firstOrNull { it.key.startsWith(GENRE_PREFIX) }
            ?.key?.removePrefix(GENRE_PREFIX)
            ?.takeIf { it.isNotEmpty() }
            ?: ALL_GENRE
        val sort = when (order) {
            SortOrder.POPULARITY, SortOrder.POPULARITY_ASC, SortOrder.POPULARITY_WEEK -> SORT_HITS
            else -> SORT_TIME
        }
        val status = when {
            filter.states.size != 1 -> STATUS_ALL
            filter.states.first() == ContentState.FINISHED -> STATUS_COMPLETED
            filter.states.first() == ContentState.ONGOING -> STATUS_SERIALIZED
            else -> STATUS_ALL
        }
        val path = "${baseUrl()}/comics/${genre.toPathSegment()}/ob/$sort/st/$status"
        return if (page <= 1) path else "$path/page/$page"
    }

    internal fun searchUrl(query: String, page: Int): String {
        val url = "${baseUrl()}/catalog.php?key=${query.urlEncoded()}"
        return if (page <= 1) url else "$url&page=$page"
    }

    /** 题材是中文，作为路径段需要百分号编码；`urlEncoded` 会把空格编成 `+`，路径里必须换回 `%20`。 */
    private fun String.toPathSegment(): String = urlEncoded().replace("+", "%20")

    internal fun parseComicList(doc: Document): List<Content> =
        doc.select("ul.col_3_1 > li").mapNotNull { parseComicItem(it) }

    private fun parseComicItem(item: Element): Content? {
        val link = item.selectFirst("a.txtA[href^=/comic/]") ?: item.selectFirst("a[href^=/comic/]")
        val href = link?.attr("href").orEmpty()
        val slug = href.substringAfter("/comic/", "").substringBefore(".html").trim('/')
        val title = link?.attr("title")?.trim().orEmpty().ifEmpty { link?.text()?.trim().orEmpty() }
        if (slug.isEmpty() || title.isEmpty()) return null
        val cover = item.selectFirst("img[src]")?.attr("src")?.trim().orEmpty()
            .ifEmpty { item.selectFirst("source[srcset]")?.attr("srcset")?.trim().orEmpty() }
        return Content(
            id = generateUid(slug),
            url = slug,
            publicUrl = contentUrl(slug),
            coverUrl = cover,
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val resp = webClient.httpGet(contentUrl(manga.url), getRequestHeaders())
        if (!resp.isSuccessful) return manga
        return parseDetails(resp.parseHtml(), manga)
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        // 站点的 <h1> 被塞在 <p class="txtItme2"> 里，HTML 解析器会把 <p> 提前闭合，
        // 因此 h1 实际是 .txtItme2 的兄弟节点，必须从外层容器取。
        val title = (doc.selectFirst("div.sub_r h1") ?: doc.selectFirst(".txtItme2 h1") ?: doc.selectFirst("h1"))
            ?.text()?.trim()?.trim('《', '》').orEmpty()
            .ifEmpty { manga.title }
        // 作者行是没有链接、也没有 date 的 p.txtItme，多人以 & 分隔；题材行则全是 /comics/ 链接
        val authors = doc.select("p.txtItme")
            .firstOrNull { it.select("a").isEmpty() && it.select("span.date").isEmpty() && it.text().isNotBlank() }
            ?.text().orEmpty()
            .split("&", "、", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val genreNames = doc.select("p.txtItme a[href^=/comics/]").mapNotNull { it.text()?.trim() }
        val statusText = doc.selectFirst("span.date")?.text()?.trim().orEmpty()
        val state = when {
            statusText.contains("完结") -> ContentState.FINISHED
            statusText.contains("连载") -> ContentState.ONGOING
            else -> null
        }
        val description = doc.selectFirst("p.txtDesc")?.text()?.trim()
            ?.replaceFirst(DESCRIPTION_PREFIX, "")?.trim().orEmpty()
        val cover = doc.selectFirst("#Cover img[src]")?.attr("src")?.trim().orEmpty()

        val tags = buildSet {
            genreNames.forEach { add(ContentTag(it, it, source)) }
        }

        return manga.copy(
            title = title,
            coverUrl = cover.ifEmpty { manga.coverUrl },
            description = description.ifEmpty { manga.description },
            tags = tags.ifEmpty { manga.tags },
            authors = authors.ifEmpty { manga.authors },
            state = state ?: manga.state,
            chapters = parseChapters(doc, manga),
            contentRating = manga.contentRating ?: ContentRating.ADULT,
        )
    }

    /** 目录整段 SSR 在详情页，但网站按最新章在前输出，这里反转成阅读顺序后再编号。 */
    internal fun parseChapters(doc: Document, manga: Content): List<ContentChapter> =
        doc.select("#mh-chapter-list-ol-0 > li > a[href]").reversed().mapIndexedNotNull { index, a ->
            val path = a.attr("href").trim().removePrefix("/")
            val chapterTitle = a.text().trim()
            if (!path.startsWith("comic/") || chapterTitle.isEmpty()) return@mapIndexedNotNull null
            ContentChapter(
                id = generateUid(path),
                url = path,
                title = chapterTitle,
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val resp = webClient.httpGet(readerUrl(chapter), getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parsePages(resp.parseHtml())
    }

    /** 阅读页图片带 `data-src` + `data-index`，DOM 顺序即页序；该属性只出现在正文图，不会混入站标与统计像素。 */
    internal fun parsePages(doc: Document): List<ContentPage> =
        doc.select("img[data-src]").mapIndexedNotNull { index, img ->
            val src = img.attr("data-src").trim()
            if (src.isEmpty()) null else ContentPage(
                id = generateUid("$src-$index"),
                url = src,
                preview = src,
                source = source,
            )
        }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private companion object {
        private const val PAGE_SIZE = 18
        private const val GENRE_PREFIX = "genre:"
        private const val ALL_GENRE = "all"
        private const val SORT_TIME = "time"
        private const val SORT_HITS = "hits"
        private const val STATUS_ALL = "all"
        private const val STATUS_SERIALIZED = "serialized"
        private const val STATUS_COMPLETED = "completed"
        private val DESCRIPTION_PREFIX = Regex("^介绍[:：]")
    }
}
