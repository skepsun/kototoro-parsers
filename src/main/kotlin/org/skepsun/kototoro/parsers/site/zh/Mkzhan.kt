package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

// 漫客栈（www.mkzhan.com）—— 国内原创漫画平台，页面服务端渲染，章节图片走公开 JSON 接口。
//
// 站点协议（2026-11 实测）：
// - 列表：/category/?order=1|2（可叠加 &theme_id=N、&page=N），30 部/页，共约 71 页 / 2116 条。
//   order=1 是「热门人气」（站点默认），order=2 是「更新时间」；题材是 theme_id=1..26（中间跳号）。
//   卡片是 div.cate-comic-list > div.common-comic-item：a.cover[href=/{id}/]、img.lazy（懒加载，
//   data-src 是真封面、src 是占位图）、p.comic__title > a（书名）、p.comic-count（人气）。
// - 搜索：/search/?keyword=xxx（可叠加 &page=N），30 条/页。没有命中时站点照样返回 12 部推荐作品，
//   标题链接是 href="#"、只有封面链接是真的，必须整页丢弃，否则推荐会被当成搜索结果。
// - 详情：/{id}/。书名 p.comic-title.j-comic-title，作者 .comic-author span.name a，
//   封面 .de-info__cover img[data-src]，连载状态是 .de-chapter__title 的第一个 span，
//   题材/收藏/人气在 .comic-status span.text（以「题材」开头的第一条），简介在 .comic-intro，
//   评分是 .rate-handle[data-score]（百分制，除以 100）。
//   目录 1000+ 话也一次性内联在 .chapter__list 里；条目 a.j-chapter-link **没有 href**，
//   地址只在 data-hreflink 上，所以要读属性而不是取链接；顺序是新章在前，这里倒序后返回。
// - 阅读页：/{id}/{章节id}.html 本身不含图片（#pages-tpl 由 JS 用接口返回的 JSON 填充），
//   图片来自 comic.mkzcdn.com/chapter/content/v1/ 的公开 JSON，实测不需要 uid/sign、不需要 Referer。
//   VIP/付费章节该接口直接拒绝（实测 code=302 / message=用户未登录，page 为空数组），这是站点自己的
//   合法结果，所以这里按错误处理；站点还留着旧接口 chapter/content/，它对付费章节也会吐图，
//   属于绕过付费墙，禁止使用。
//   JSON 里给的是 content.mkzcdn.com 的限时签名地址（xxx.jpg!page-800?auth_key=…），
//   这里改写成站点自己的原图域名 oss.mkzcdn.com（同一路径，实测无鉴权、无防盗链、不过期）。
@ContentSourceParser("MKZHAN", "漫客栈", "zh", type = ContentType.MANGA)
internal class Mkzhan(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.MKZHAN, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.mkzhan.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val themes = THEMES.map { (id, title) -> ContentTag(title, id, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = themes,
            tagGroups = listOf(ContentTagGroup("题材", themes)),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query
        if (!query.isNullOrBlank()) {
            return parseSearchPage(fetchDocument(searchUrl(page, query)))
        }
        val themeId = filter.tags.firstOrNull()?.key?.takeIf { key -> THEMES.any { it.first == key } }
        val url = categoryUrl(page, order, themeId)
        val items = parseListPage(fetchDocument(url))
        if (items.isEmpty() && page == 1) {
            throw ParseException("漫客栈：分类列表为空，站点结构可能已变化", url)
        }
        return items
    }

    private fun categoryUrl(page: Int, order: SortOrder, themeId: String?): String = buildString {
        append("https://").append(domain).append("/category/?order=")
        append(if (order == SortOrder.UPDATED) ORDER_TIME else ORDER_HOT)
        if (themeId != null) {
            append("&theme_id=").append(themeId)
        }
        if (page > 1) {
            append("&page=").append(page)
        }
    }

    private fun searchUrl(page: Int, query: String): String = buildString {
        append("https://").append(domain).append("/search/?keyword=").append(query.urlEncoded())
        if (page > 1) {
            append("&page=").append(page)
        }
    }

    // 解析分类页的卡片区，抽出来便于离线夹具测试
    internal fun parseListPage(doc: Document): List<Content> =
        doc.select("div.cate-comic-list div.common-comic-item").mapNotNull(::parseCard)

    // 解析搜索结果页；「很遗憾…」这种推荐页整页丢弃
    internal fun parseSearchPage(doc: Document): List<Content> {
        if (doc.selectFirst("div.search_head")?.textOrNull()?.contains(NO_RESULT_MARKER) == true) {
            return emptyList()
        }
        return doc.select("div.search-comic-list div.common-comic-item").mapNotNull(::parseCard)
    }

    private fun parseCard(item: Element): Content? {
        val href = item.selectFirst("p.comic__title a")?.attrOrNull("href")
            ?: item.selectFirst("a.cover")?.attrOrNull("href")
            ?: return null
        // 推荐位的标题链接是 href="#" 之类的占位，只有 /{id}/ 才是作品
        val path = href.takeIf { COMIC_PATH_REGEX.matches(it) } ?: return null
        val title = item.selectFirst("p.comic__title a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val cover = item.selectFirst("img.lazy")
            ?.let { it.attrOrNull("data-src") ?: it.attrOrNull("src") }
            ?.takeUnless { it.contains(PLACEHOLDER_IMAGE_MARKER) }
            ?.toAbsoluteUrl(domain)
        return Content(
            id = generateUid(path),
            title = title,
            altTitles = emptySet(),
            url = path,
            publicUrl = path.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val details = parseDetails(doc) ?: throw NotFoundException("漫客栈：作品不存在或已下架", url)
        val chapters = parseChapterList(doc)
        if (chapters.isEmpty()) {
            throw NotFoundException("漫客栈：目录为空，作品可能已下架", url)
        }
        return manga.copy(
            title = details.title ?: manga.title,
            authors = details.author?.let(::setOf) ?: manga.authors,
            description = details.description ?: manga.description,
            coverUrl = details.coverUrl ?: manga.coverUrl,
            tags = details.genres.mapTo(LinkedHashSet()) { ContentTag(it, it, source) },
            state = details.state ?: manga.state,
            rating = details.rating,
            chapters = chapters,
        )
    }

    // 详情页头部的结构化信息；页面不是作品详情（下架页 / 错误页）时返回 null
    internal fun parseDetails(doc: Document): MkzhanDetails? {
        val title = doc.selectFirst("p.comic-title.j-comic-title")
            ?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val author = doc.selectFirst("div.comic-author span.name a")
            ?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = doc.selectFirst("div.de-info__cover img")
            ?.let { it.attrOrNull("data-src") ?: it.attrOrNull("src") }
            ?.takeUnless { it.contains(PLACEHOLDER_IMAGE_MARKER) }
            ?.toAbsoluteUrl(domain)
        val state = when (doc.selectFirst("div.de-chapter__title > span")?.textOrNull()?.trim()) {
            STATE_ONGOING -> ContentState.ONGOING
            STATE_FINISHED -> ContentState.FINISHED
            else -> null
        }
        val genres = doc.select("div.comic-status span.text")
            .firstOrNull { it.textOrNull()?.startsWith(GENRE_LABEL) == true }
            ?.selectFirst("b")
            ?.textOrNull()
            ?.split(' ', '\u3000')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        // 简介全文在 p.intro-total 里（p.intro 是折叠态，长简介会被截断并带「展开」按钮）
        val description = doc.selectFirst("div.comic-intro p.intro-total")
            ?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("div.comic-intro p.intro")
                ?.textOrNull()?.substringBefore(EXPAND_LABEL)?.trim()?.takeIf { it.isNotEmpty() }
        val rating = doc.selectFirst("div.rate-handle[data-score]")
            ?.attrOrNull("data-score")
            ?.toFloatOrNull()
            ?.div(100f)
            ?: RATING_UNKNOWN
        return MkzhanDetails(
            title = title,
            author = author,
            coverUrl = cover,
            description = description,
            state = state,
            genres = genres,
            rating = rating,
        )
    }

    internal fun parseChapterList(doc: Document): List<ContentChapter> {
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<ContentChapter>()
        for (a in doc.select("a.j-chapter-link")) {
            // 章节链接没有 href，地址只在 data-hreflink 上
            val raw = a.attrOrNull("data-hreflink") ?: a.attrOrNull("href") ?: continue
            val path = toChapterPath(raw) ?: continue
            if (!seen.add(path)) {
                continue
            }
            // 条目里夹着 VIP / 更新标记的图标字形（U+E000..U+F8FF），必须剔掉
            val title = a.textOrNull()?.stripPrivateUseGlyphs()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            chapters += ContentChapter(
                id = generateUid(path),
                title = title,
                number = 0f,
                volume = 0,
                url = path,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
        // 站点顺序是新章在前，倒序成阅读顺序。话号写法不统一（「第537话1」「番外·行路难」「11月17日请假条」
        // 「4.26请假条」混在一起，1.8% 的条目根本没有话号），话号不可靠，所以按目录位置编号保证单调递增
        return chapters.asReversed().mapIndexed { index, chapter ->
            chapter.copy(number = (index + 1).toFloat())
        }
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val path = chapter.url
        val match = CHAPTER_PATH_REGEX.matchEntire(path) ?: throw ParseException(
            "漫客栈：章节地址无法识别",
            path.toAbsoluteUrl(domain),
        )
        val comicId = match.groupValues[1]
        val chapterId = match.groupValues[2]
        val apiUrl = buildString {
            append(CHAPTER_CONTENT_API)
            append("?chapter_id=").append(chapterId)
            append("&comic_id=").append(comicId)
            append("&quality=1&type=1&format=0")
        }
        val json = webClient.httpGet(apiUrl).parseRaw()
        val images = parseChapterPages(json)
        if (images.isEmpty()) {
            throw ParseException(
                "漫客栈：该话没有可阅读的图片（" + parseChapterFailure(json) + "），VIP/付费章节需要在站点购买",
                path.toAbsoluteUrl(domain),
            )
        }
        return images.mapIndexed { index, imageUrl ->
            ContentPage(
                id = generateUid(chapter.id.toString() + "_" + index),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    // 解析 chapter/content/v1/ 的响应；空列表表示这一话没有可读图片（VIP/付费章节就是这种响应）
    internal fun parseChapterPages(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        if (root.optString("code") != CODE_OK) {
            return emptyList()
        }
        val pages = root.optJSONObject("data")?.optJSONArray("page") ?: return emptyList()
        return (0 until pages.length()).mapNotNull { index ->
            pages.optJSONObject(index)
                ?.optString("image")
                ?.takeIf(String::isNotBlank)
                ?.let(::normalizeImageUrl)
        }
    }

    // 接口失败时把站点自己的 code/message 带进错误，方便区分「需要购买」和「接口改了」
    internal fun parseChapterFailure(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "响应不是合法 JSON"
        val code = root.optString("code")
        val message = root.optString("message")
        return if (code.isEmpty() && message.isEmpty()) "响应缺少 code/message" else "code=$code, message=$message"
    }

    internal fun normalizeImageUrl(raw: String): String {
        // 去掉 Thumbor 风格的转码后缀与限时签名：xxx.jpg!page-800?auth_key=…
        val path = raw.trim().substringBefore('!').substringBefore('?')
        val absolute = when {
            path.startsWith("//") -> "https:$path"
            path.startsWith("http://") -> "https://" + path.removePrefix("http://")
            else -> path
        }
        // content.mkzcdn.com 必须带签名，同路径的原图域名 oss.mkzcdn.com 不需要
        return absolute.replace("//content.mkzcdn.com/", "//oss.mkzcdn.com/")
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url.takeUnless { it.isBlank() }
        ?: throw ParseException("漫客栈：页面缺少图片地址", page.id.toString())

    // 详情页里的章节地址可能写成 /{id}/{章节id}.html、也可能写绝对地址，统一成站点内绝对路径
    private fun toChapterPath(raw: String): String? {
        val withoutScheme = when {
            raw.startsWith("//") -> raw.removePrefix("//")
            URL_PREFIX_REGEX.containsMatchIn(raw) -> raw.substringAfter("//")
            else -> null
        }
        val path = when {
            withoutScheme != null -> "/" + withoutScheme.substringAfter('/', "")
            raw.startsWith("/") -> raw
            else -> "/$raw"
        }
        return path.substringBefore('?').substringBefore('#').takeIf { CHAPTER_PATH_REGEX.matches(it) }
    }

    private fun String.stripPrivateUseGlyphs(): String = filterNot { it in '\uE000'..'\uF8FF' }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private companion object {
        const val LIST_PAGE_SIZE = 30

        const val ORDER_HOT = "1"
        const val ORDER_TIME = "2"
        const val STATE_ONGOING = "连载"
        const val STATE_FINISHED = "完结"
        const val GENRE_LABEL = "题材"
        const val NO_RESULT_MARKER = "没有找到"
        const val EXPAND_LABEL = "展开"
        const val CODE_OK = "200"
        const val PLACEHOLDER_IMAGE_MARKER = "bg_loadimg"
        const val CHAPTER_CONTENT_API = "https://comic.mkzcdn.com/chapter/content/v1/"

        val COMIC_PATH_REGEX = Regex("""^/\d+/$""")
        val CHAPTER_PATH_REGEX = Regex("""^/(\d+)/(\d+)\.html$""")
        val URL_PREFIX_REGEX = Regex("""^\w+://""")

        val THEMES = listOf(
            "1" to "霸总",
            "2" to "修真",
            "3" to "恋爱",
            "4" to "校园",
            "5" to "冒险",
            "6" to "搞笑",
            "7" to "生活",
            "8" to "热血",
            "9" to "架空",
            "10" to "后宫",
            "12" to "玄幻",
            "13" to "悬疑",
            "14" to "恐怖",
            "15" to "灵异",
            "16" to "动作",
            "17" to "科幻",
            "18" to "战争",
            "19" to "古风",
            "20" to "穿越",
            "21" to "竞技",
            "23" to "励志",
            "24" to "同人",
            "26" to "真人",
        )
    }
}

// 详情页解析结果，抽成数据类便于离线夹具逐字段断言
internal data class MkzhanDetails(
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val description: String?,
    val state: ContentState?,
    val genres: List<String>,
    val rating: Float,
)
