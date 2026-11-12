@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.InternalParsersApi
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
import org.skepsun.kototoro.parsers.util.parseSafe
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.skepsun.kototoro.parsers.util.json.getIntOrDefault
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSONIndexed
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.parsers.util.json.toJSONObjectOrNull
import org.skepsun.kototoro.parsers.util.json.unescapeJson
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * 香香腐宅（BoyLove）
 *
 * 台版繁体的 BL/耽美漫画汉化站，站内混有韩漫、日漫与国漫。列表与搜索**不是 SSR**：
 * 页面 DOM 里的卡片由前端 JS 渲染，因此这里直接解析它们调用的同源 JSON 接口。
 *
 * - 列表：`/home/api/cate/tp/{cate}-{tag}-{progress}-{order}-{page}-{is18}-1-{vip}?mt={mt}`
 *   `progress` 0=连载中 1=已完结 2=全部（对应站点 `/home/book/cate/progress/{n}`）；
 *   `order` 1=最新更新 2=人气（≥2 的取值服务端行为一致）；`is18` 0=全部 1=清水 2=有肉；
 *   `vip` 0=免费 2=全部；`mt` 0=全部 1=韩漫 2=日漫 3=国漫（站点 **路径** 里的 `m_type`，
 *   但前端只把它拼进查询串，因此要从 `mt` 传）。`cate` 恒为 1，`tag` 站点未公开 id 表故恒为 0。
 *   响应 `{result:{list[], listCount, lastPage}}`，`lastPage` 是**布尔**而非页码，翻页以列表长度收敛。
 *   每页 24 条，搜索每页 18 条。
 * - 搜索：`/home/api/searchk?keyword={关键词}&type=0&pageNo={page}`（`type=0` 限定漫画，`type=2` 是小说）。
 * - 详情：`/home/book/index/id/{id}`。正文信息在 SSR 的 `p.data` 行与 `<h1>` 里；
 *   章节目录是一段 JS 字符串里的 JSON：`function getChapterList(asc) { let data = JSON.parse("…") }`，
 *   需先按 JS 字符串语义反转义再当 JSON 解析。目录本身就是阅读顺序（旧→新），不要反转。
 * - 阅读：`/home/book/capter/id/{chapterId}`（站点把 "chapter" 拼成了 "capter"），
 *   图片是 `div.reader-cartoon-image` 容器内 `img[data-original]`，托管在独立图床 `img.boylove.cc`。
 *
 * 付费章节（`isvip == "1"`）不返回，也不提供「仅 VIP」筛选。
 */
@ContentSourceParser("BOYLOVE", "香香腐宅", "zh", type = ContentType.HENTAI_MANGA)
internal class BoyloveParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.BOYLOVE, pageSize = 24, searchPageSize = 18) {

    override val configKeyDomain = ConfigKey.Domain("boylove.cc", "boylove4.xyz")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)

    /** 站点默认进分类页就是「最新更新」，不要落到枚举顺序第一位的热度排序上。 */
    override val defaultSortOrder: SortOrder = SortOrder.NEWEST

    private val originTags = listOf(
        ContentTag("韩漫", "$ORIGIN_PREFIX$ORIGIN_KR", source),
        ContentTag("日漫", "$ORIGIN_PREFIX$ORIGIN_JP", source),
        ContentTag("国漫", "$ORIGIN_PREFIX$ORIGIN_CN", source),
    )

    private val ratingTags = listOf(
        ContentTag("清水", "$IS18_PREFIX$IS18_SAFE", source),
        ContentTag("有肉", "$IS18_PREFIX$IS18_ADULT", source),
    )

    private val accessTags = listOf(
        ContentTag("免费", "$VIP_PREFIX$VIP_FREE", source),
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            // 接口每个维度只吃一个取值，多选没有对应语义
            isMultipleTagsSupported = false,
            isSearchSupported = true,
            // 站内搜索接口不接受来源/尺度/进度参数
            isSearchWithFiltersSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableTags = (originTags + ratingTags + accessTags).toSet(),
        tagGroups = listOf(
            ContentTagGroup("来源", originTags.toSet()),
            ContentTagGroup("尺度", ratingTags.toSet()),
            ContentTagGroup("付费", accessTags.toSet()),
        ),
        availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        // 接口是站内 fetch 调用，缺 Referer 会被当成外链
        .add("Referer", baseUrl())
        .build()

    private fun baseUrl(): String = "https://${config[configKeyDomain].removeSuffix("/")}"

    private fun detailsUrl(bookId: String): String = "${baseUrl()}/home/book/index/id/$bookId"

    private fun readerUrl(chapter: ContentChapter): String = "${baseUrl()}/home/book/capter/id/${chapter.url}"

    /** 封面在接口里是相对路径，站点模板固定拼到图床域名 `https://img.boylove.cc` 后面。 */
    private fun String.toCoverUrl(): String = trim().let {
        when {
            it.isEmpty() -> ""
            it.startsWith("http://") || it.startsWith("https://") -> it
            else -> IMAGE_CDN + it
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = filter.query?.takeIf { it.isNotBlank() }?.let { searchUrl(it, page) }
            ?: buildListUrl(page, order, filter)
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseContentList(resp.body?.string().orEmpty().toJSONObjectOrNull())
    }

    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val origin = filter.tags.firstOrNull { it.key.startsWith(ORIGIN_PREFIX) }
            ?.key?.removePrefix(ORIGIN_PREFIX) ?: ORIGIN_ALL
        val adult = filter.tags.firstOrNull { it.key.startsWith(IS18_PREFIX) }
            ?.key?.removePrefix(IS18_PREFIX) ?: ALL
        val vip = filter.tags.firstOrNull { it.key.startsWith(VIP_PREFIX) }
            ?.key?.removePrefix(VIP_PREFIX) ?: VIP_ALL
        val progress = if (filter.states.size == 1) {
            when (filter.states.first()) {
                ContentState.FINISHED -> PROGRESS_FINISHED
                ContentState.ONGOING -> PROGRESS_ONGOING
                else -> ALL
            }
        } else {
            ALL
        }
        val sort = if (order == SortOrder.NEWEST) SORT_UPDATED else SORT_POPULARITY
        return "${baseUrl()}/home/api/cate/tp/$CATE-$NO_TAG-$progress-$sort-$page-$adult-1-$vip?mt=$origin"
    }

    internal fun searchUrl(query: String, page: Int): String =
        "${baseUrl()}/home/api/searchk?keyword=${query.urlEncoded()}&type=$TYPE_MANGA&pageNo=$page"

    internal fun parseContentList(json: JSONObject?): List<Content> {
        val list = json?.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
        return list.mapJSONNotNull { parseContentItem(it) }
    }

    private fun parseContentItem(item: JSONObject): Content? {
        val bookId = item.getIntOrDefault("id", 0)
        if (bookId <= 0) return null
        val title = item.getStringOrNull("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        return Content(
            id = generateUid(bookId.toString()),
            url = bookId.toString(),
            publicUrl = detailsUrl(bookId.toString()),
            coverUrl = item.getStringOrNull("image").orEmpty().toCoverUrl().ifEmpty { null },
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = item.getStringOrNull("auther").orEmpty()
                .split(AUTHOR_SEPARATORS)
                .map { it.trim() }
                .filterTo(LinkedHashSet()) { it.isNotEmpty() },
            state = when (item.getIntOrDefault("mhstatus", ONGOING_FLAG)) {
                ONGOING_FLAG -> ContentState.ONGOING
                else -> ContentState.FINISHED
            },
            source = source,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val resp = webClient.httpGet(detailsUrl(manga.url), getRequestHeaders())
        if (!resp.isSuccessful) return manga
        return parseDetails(resp.parseHtml(), manga)
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        // 页头有一份被注释掉的旧作者行，Jsoup 会把注释当文本节点而非元素，select 自然跳过它
        val dataLines = doc.select("p.data")
        val title = (doc.selectFirst("div.stui-content__detail h1") ?: doc.selectFirst("h1"))
            ?.text()?.trim()?.trim('《', '》').orEmpty()
            .ifEmpty { manga.title }
        val stateText = dataLines.firstOrNull { it.selectFirst("i") == null }?.text()?.trim().orEmpty()
        val state = when {
            stateText.contains("完結") || stateText.contains("完结") -> ContentState.FINISHED
            stateText.contains("連載") || stateText.contains("连载") -> ContentState.ONGOING
            else -> null
        }
        val authors = dataLines
            .firstOrNull { it.selectFirst("i")?.text()?.contains("作者") == true }
            ?.let { line ->
                val links = line.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                // 作者没有链接时退化成整行文本，去掉前面的「作者：」标签
                links.ifEmpty {
                    line.text().replace(line.selectFirst("i")?.text().orEmpty(), "").trim()
                        .split(AUTHOR_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            ?.toSet()
            .orEmpty()
        val tags = dataLines
            .firstOrNull { it.selectFirst("i")?.text()?.contains("标签") == true }
            ?.select("a.taga")
            ?.mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }
            ?.mapTo(LinkedHashSet()) { ContentTag(it, it, source) }
            .orEmpty()
        val description = doc.selectFirst("p.desc.detail .detail-text")?.text()?.trim().orEmpty()
            .ifEmpty {
                doc.selectFirst("p.desc.detail")?.text()?.trim()
                    ?.replaceFirst(DESCRIPTION_PREFIX, "")?.trim().orEmpty()
            }
        val cover = doc.selectFirst("a.play[data-original]")?.attr("data-original").orEmpty()
            .toCoverUrl()
            .ifEmpty { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty().toCoverUrl() }

        return manga.copy(
            title = title,
            coverUrl = cover.ifEmpty { manga.coverUrl },
            description = description.ifEmpty { manga.description },
            tags = tags.ifEmpty { manga.tags },
            authors = authors.ifEmpty { manga.authors },
            state = state ?: manga.state,
            chapters = parseChapters(doc),
        )
    }

    /** 目录藏在 `getChapterList` 的 JS 字符串字面量里，先按 JS 转义规则还原成 JSON 再解析。 */
    internal fun parseChapters(doc: Document): List<ContentChapter> {
        val scripts = doc.select("script").filter { it.data().contains(CHAPTER_LIST_MARKER) }
        var raw: String? = null
        for (script in scripts) {
            raw = extractChapterJson(script.data())
            if (!raw.isNullOrEmpty()) break
        }
        if (raw.isNullOrEmpty()) return emptyList()
        val list = runCatching {
            raw.unescapeJson().toJSONObjectOrNull()?.optJSONArray("list")
        }.getOrNull() ?: return emptyList()
        return list.mapJSONIndexed<ContentChapter?> { index, item ->
            val chapterId = item.getIntOrDefault("id", 0)
            // 付费章节一律不返回：既不能读，也不该出现在目录里
            if (chapterId <= 0 || item.getStringOrNull("isvip") == VIP_CHAPTER) return@mapJSONIndexed null
            ContentChapter(
                id = generateUid(chapterId.toString()),
                url = chapterId.toString(),
                title = item.getStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() },
                // 目录本身就是阅读顺序：页面里的 asc 参数只透传给渲染函数，没有参与排序
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = DATE_FORMAT.parseSafe(item.getStringOrNull("create_time")),
                branch = null,
                source = source,
            )
        }.filterNotNull()
    }

    /**
     * 提取 `getChapterList` 内传给 `JSON.parse(...)` 的字符串字面量内容。
     *
     * 避免使用带回溯/交替的正则（如 `((?:[^"\\]|\\.)*)`）匹配长字符串：
     * OpenJDK 的 `java.util.regex` 在处理量词内的分支时通过递归实现，
     * 面对数十乃至上百话、长度数千到数万字符的目录字符串会耗尽调用栈，抛出 [StackOverflowError]。
     * 该现象在 iOS 嵌入式 Zero 解释器 JVM 环境（栈空间较小）中必然触发，
     * 而 Android 端因 libcore 使用非递归的 C++ ICU 正则引擎因而表现正常。
     * 这里采用 O(N) 线性字符扫描提取字面量。
     */
    internal fun extractChapterJson(script: String): String? {
        val markerIndex = script.indexOf(CHAPTER_LIST_MARKER)
        val scope = if (markerIndex >= 0) script.substring(markerIndex) else script
        val parseIndex = scope.indexOf("JSON.parse")
        if (parseIndex < 0) return null

        var startQuote = -1
        var quoteChar = '"'
        for (i in parseIndex + "JSON.parse".length until scope.length) {
            val c = scope[i]
            if (c == '"' || c == '\'') {
                startQuote = i
                quoteChar = c
                break
            }
        }
        if (startQuote < 0) return null

        var escaped = false
        var endQuote = -1
        for (i in startQuote + 1 until scope.length) {
            val c = scope[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == quoteChar) {
                endQuote = i
                break
            }
        }
        if (endQuote < 0) return null
        return scope.substring(startQuote + 1, endQuote)
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val resp = webClient.httpGet(readerUrl(chapter), getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parsePages(resp.parseHtml())
    }

    /**
     * 正文图统一在 `div.reader-cartoon-image` 容器里，用懒加载的 `data-original` 指向真实地址；
     * 该属性的 `src` 只是占位图。容器末尾还有一个 `data-original` 为空的页脚按钮，按空值过滤掉。
     */
    internal fun parsePages(doc: Document): List<ContentPage> {
        val sections = doc.select("div.reader-cartoon-image img[data-original]")
        val candidates: Elements = if (sections.isEmpty()) doc.select("img[data-original]") else sections
        return candidates.mapIndexedNotNull { index, img ->
            val src = img.attr("data-original").trim()
            if (src.isEmpty()) null else ContentPage(
                id = generateUid("$src-$index"),
                url = src,
                preview = src,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private companion object {
        private const val IMAGE_CDN = "https://img.boylove.cc"
        private const val ORIGIN_PREFIX = "origin:"
        private const val IS18_PREFIX = "is18:"
        private const val VIP_PREFIX = "vip:"
        private const val ORIGIN_KR = "1"
        private const val ORIGIN_JP = "2"
        private const val ORIGIN_CN = "3"
        private const val ORIGIN_ALL = "0"
        private const val IS18_SAFE = "1"
        private const val IS18_ADULT = "2"
        private const val VIP_FREE = "0"
        private const val VIP_ALL = "2"
        private const val ALL = "2"
        private const val CATE = "1"
        private const val NO_TAG = "0"
        private const val PROGRESS_ONGOING = "0"
        private const val PROGRESS_FINISHED = "1"
        private const val SORT_UPDATED = "1"
        private const val SORT_POPULARITY = "2"
        private const val TYPE_MANGA = "0"
        private const val ONGOING_FLAG = 0
        private const val VIP_CHAPTER = "1"
        private const val CHAPTER_LIST_MARKER = "getChapterList"
        private val AUTHOR_SEPARATORS = Regex("[,，、/&]")
        private val DESCRIPTION_PREFIX = Regex("^简[介介][:：]\\s*")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    }
}
