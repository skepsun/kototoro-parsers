package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.textOrNull
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

// 看漫网（www.kanman.com）—— 早期的「看漫画」平台，页面服务端渲染，列表和搜索走公开 JSON 接口。
//
// 站点协议（2026-11 实测）：
// - 列表：HTML 的 /sort/ 只有第一页能用（48 部/页），站点自己的页码链接 /sort/all/index_p2.html 实测 500、
//   /sort/index_p2.html 实测回落到第一页，也就是 PC 分页在服务端已经坏了。
//   实际可用的是移动端用的 /api/getsortlist/：orderby=click(人气)|date(更新)，comic_sort=题材或进度 slug，
//   search_key=搜索词，page=页码，size=每页条数。站点自己的客户端固定 size=30，这里照抄。
//   该接口会过滤掉未上线/不开放的作品，所以返回条数常小于 30（实测首页 25 条、第二页 14 条，
//   全站能翻出约 100 部可读作品后返回空列表），pageSize 按站点的请求值 30 声明。
// - 搜索：同一个接口的 search_key 参数（站内的 /api/serachcomic/ 只给 10 条联想，不能分页）。
//   search_key 只做标题模糊匹配，实测「凤」→ 4 部、「斗罗」→ 0 部（授权下架），不支持与题材叠加。
// - 详情：/{comic_id}/。书名 h1.title，封面 div.acgn-model-detail-frontcover img.frontcover[data-src]，
//   大图 .mhjj .img-box img[data-src]（-300x400 后缀是 CDN 的缩略图约定，去掉后缀就是原图），
//   题材 p.tags a（注意站点把 class 属性写了两遍，不能按 class 选，href 是 /sort/{slug}.html），
//   连载状态 .update-info p.status（「连载中·」/「完结·」），简介 .introduce .desc-box .content，
//   作者在 .zzjs 的 swiper 里，头像 img.pic-cover[alt] 是作者名，同一容器里还混着角色（/juese/）卡片，
//   只有链接指向 /zuozhe/ 的那几张才是作者。
// - 目录：ol#j_chapter_list 一次性内联全部话（实测 609 话），条目 li.item > a[href=/id/xxx.html]，
//   标题在 a[title]（p.name 里的 <i> 是空图标，但 a[title] 更干净），顺序已经是阅读顺序。
//   未购买的话带 li.item.lock，但实际能读到图片（见下），所以不静默丢弃。
// - 阅读页：/{comic_id}/{章节}.html 把图片列表内联在 window.comicInfo 的 current_chapter.chapter_img_list 里，
//   不需要执行 JS；地址是 hw-chapter2.kaimanhua.com 的限时签名链接（?auth_key=），实测无 Referer 也能直接取，
//   Content-Type 是 image 类型。该 JS 对象里嵌套着对象、后面还接着别的赋值语句，
//   所以只按 chapter_img_list 定位数组，不尝试整体解析这个对象。
//   注意签名只有 15~60 分钟有效期，而这一页会被 CDN 缓存 12 小时，命中旧缓存时拿到的签名已经过期、
//   图片全部 403，所以取阅读页时强制加一个随机参数回源（见 readerUrl）。
@ContentSourceParser("KANMAN", "看漫网", "zh", type = ContentType.MANGA)
internal class Kanman(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.KANMAN, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.kanman.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchWithFiltersSupported = false,
        )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableTags = genreTags(),
        tagGroups = listOf(ContentTagGroup(TAG_GROUP_GENRE, genreTags())),
        // 站点把「连载/完结」也做成 comic_sort 的一个取值，实测 comic_sort=lianzai|wanjie 有效
        availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = listUrl(page, order, filter)
        val json = webClient.httpGet(url).parseRaw()
        val items = parseListJson(json, url)
        if (items.isEmpty() && page == 1) {
            throw ParseException("看漫网：列表接口返回为空，站点结构或接口参数可能已变化", url)
        }
        return items
    }

    private fun listUrl(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
        append("https://").append(domain).append(API_LIST)
        append("?orderby=").append(if (order == SortOrder.UPDATED) ORDER_DATE else ORDER_CLICK)
        append("&page=").append(page)
        append("&size=").append(LIST_PAGE_SIZE)
        append("&search_key=").append(filter.query?.trim()?.takeIf { it.isNotEmpty() }?.urlEncoded().orEmpty())
        append("&comic_sort=").append(sortSlug(filter))
    }

    // comic_sort 只接受一个取值：题材优先，其次是连载状态。实测不支持逗号叠加、也不认地区/专题/字母分组
    private fun sortSlug(filter: ContentListFilter): String {
        val tag = filter.tags.firstOrNull()?.key
        if (!tag.isNullOrEmpty() && GENRES.any { it.first == tag }) {
            return tag
        }
        return when (filter.states.firstOrNull()) {
            ContentState.ONGOING -> STATE_ONGOING
            ContentState.FINISHED -> STATE_FINISHED
            else -> ""
        }
    }

    // 解析 items 接口的响应；结构不对就报错，不把「接口改了」和「这一页确实没内容」混为一谈
    internal fun parseListJson(json: String, requestUrl: String): List<Content> {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw ParseException("看漫网：列表接口返回的不是 JSON", requestUrl)
        }
        val payload = root.optJSONObject("data")
            ?: throw ParseException("看漫网：列表接口响应缺少 data 字段", requestUrl)
        val items = payload.optJSONArray("data")
            ?: throw ParseException("看漫网：列表接口响应缺少 data.data 字段", requestUrl)
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let(::parseListItem)
        }
    }

    private fun parseListItem(item: JSONObject): Content? {
        val comicId = item.optLong("comic_id", 0L).takeIf { it > 0L } ?: return null
        val title = item.optString("comic_name").trim().takeIf { it.isNotEmpty() } ?: return null
        val path = "/$comicId/"
        return Content(
            id = generateUid(path),
            title = title,
            altTitles = emptySet(),
            url = path,
            publicUrl = path.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = item.optString("cover_img").takeIf { it.isNotEmpty() }?.let(::normalizeImageUrl),
            tags = parseGenres(item.optString("comic_type")),
            state = null,
            authors = item.optString("comic_author").trim().takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty(),
            source = source,
        )
    }

    // comic_type 形如 "rexue,热血|maoxian,冒险"，前半是 slug，后半是中文名
    internal fun parseGenres(raw: String): Set<ContentTag> {
        if (raw.isEmpty()) {
            return emptySet()
        }
        return raw.split('|').mapNotNullTo(LinkedHashSet()) { part ->
            val slug = part.substringBefore(',').trim()
            val name = part.substringAfter(',', "").trim()
            if (slug.isEmpty() || name.isEmpty() || slug == STATE_ONGOING || slug == STATE_FINISHED) {
                null
            } else {
                ContentTag(name, slug, source)
            }
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val details = parseDetails(doc) ?: throw NotFoundException("看漫网：作品不存在或已下架", url)
        val chapters = parseChapterList(doc)
        // 详情页把目录整个内联在页面里，解析出 0 章说明结构变了，不能当成「这部作品没有章节」放过去
        if (chapters.isEmpty()) {
            throw ParseException("看漫网：详情页没有解析到任何章节，站点结构可能已变化", url)
        }
        return manga.copy(
            title = details.title,
            authors = details.authors.ifEmpty { manga.authors },
            description = details.description ?: manga.description,
            coverUrl = details.coverUrl ?: manga.coverUrl,
            largeCoverUrl = details.largeCoverUrl ?: manga.largeCoverUrl,
            tags = details.tags.ifEmpty { manga.tags },
            state = details.state ?: manga.state,
            chapters = chapters,
        )
    }

    // 详情页顶部的结构化信息；页面不是作品详情（下架跳转 / 错误页）时返回 null
    internal fun parseDetails(doc: Document): KanmanDetails? {
        val title = doc.selectFirst("h1.title")
            ?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        // 网格缩略图（-300x400 后缀）与详情页大封面是两个不同尺寸的文件
        val thumbCover = doc.selectFirst("div.mhjj div.img-box img")
            ?.attrOrNull("data-src")
            ?.let(::normalizeImageUrl)
        val largeCover = doc.selectFirst("div.acgn-model-detail-frontcover img.frontcover")
            ?.attrOrNull("data-src")
            ?.let(::normalizeImageUrl)
        // 站点的 class 属性写了两遍（class="tag" ... class="normal"），只能按标签和 href 选
        val tags = doc.select("p.tags a[href^='/sort/']").mapNotNullTo(LinkedHashSet()) { a ->
            val name = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNullTo null
            val slug = a.attrOrNull("href")?.substringAfterLast('/')?.substringBefore('.')?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNullTo null
            ContentTag(name, slug, source)
        }
        val status = doc.selectFirst("div.update-info p.status")?.textOrNull()
        val state = when {
            status == null -> null
            status.contains(STATE_LABEL_FINISHED) -> ContentState.FINISHED
            status.contains(STATE_LABEL_ONGOING) -> ContentState.ONGOING
            else -> null
        }
        val description = doc.selectFirst("div.introduce div.desc-box div.content")
            ?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        // 作者头像在 .zzjs 的 swiper 里，同一容器还混着角色卡片，只有 /zuozhe/ 链接的那几张是作者
        val authors = doc.select("div.zzjs div.swiper-slide:has(a[href^='/zuozhe/']) img.pic-cover").mapNotNullTo(
            LinkedHashSet(),
        ) { img ->
            img.attrOrNull("alt")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return KanmanDetails(
            title = title,
            authors = authors,
            coverUrl = thumbCover,
            largeCoverUrl = largeCover,
            description = description,
            state = state,
            tags = tags,
        )
    }

    internal fun parseChapterList(doc: Document): List<ContentChapter> {
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<ContentChapter>()
        for (a in doc.select("ol#j_chapter_list li.item > a[href]")) {
            val path = toChapterPath(a.attrOrNull("href") ?: continue) ?: continue
            if (!seen.add(path)) {
                continue
            }
            // a[title] 是干净的话名；p.name 里夹着空的 <i> 图标节点
            val title = a.attrOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.selectFirst("p.name")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
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
        // 目录本身是阅读顺序。话号写法不统一（「第1话 …」「第2季355话 …」「番外2 …」「停更通知」），
        // 按目录位置编号，保证 number 单调递增
        return chapters.mapIndexed { index, chapter ->
            chapter.copy(number = (index + 1).toFloat())
        }
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = readerUrl(chapter.url.toAbsoluteUrl(domain))
        val raw = webClient.httpGet(url).parseRaw()
        val images = parseReaderPages(raw)
        if (images.isEmpty()) {
            throw ParseException("看漫网：阅读页没有解析到图片，章节可能已下架或站点结构已变化", url)
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

    // 阅读页 HTML 会被 CDN 缓存 12 小时（x-ccdn-cachettl: 43200），而页内内联的图片签名
    // （auth_key）只活 15~60 分钟，命中旧缓存就会拿到过期签名、图片一律 403。
    // 带上一个随机参数强制回源，才能拿到当场签发的地址
    internal fun readerUrl(pageUrl: String, nonce: Long = System.currentTimeMillis()): String {
        val separator = if (pageUrl.contains('?')) '&' else '?'
        return pageUrl + separator + "cachebust=" + nonce
    }

    // 阅读页把图片内联在 window.comicInfo.current_chapter.chapter_img_list 里，
    // 只定位这个数组；整个对象是 JS 字面量（键没有引号），不能当 JSON 解析
    internal fun parseReaderPages(raw: String): List<String> {
        val array = CHAPTER_IMG_LIST_REGEX.find(raw)?.groupValues?.get(1) ?: return emptyList()
        return QUOTED_URL_REGEX.findAll(array)
            .map { it.groupValues[1] }
            .filter(String::isNotBlank)
            .map(::normalizeImageUrl)
            .toList()
    }

    // 站点的图片地址：http:// 与 // 混用，统一升到 https，单斜杠化转义
    internal fun normalizeImageUrl(raw: String): String {
        val url = raw.trim().replace("\\/", "/")
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
    }

    // 只接受 /{comic_id}/{章节}.html 这种章节地址，其它（站内推荐位等）一律丢弃
    internal fun toChapterPath(raw: String): String? {
        val path = raw.substringBefore('?').substringBefore('#')
        val normalized = if (path.startsWith("/")) path else "/$path"
        return normalized.takeIf { CHAPTER_PATH_REGEX.matches(it) }
    }

    private fun genreTags(): Set<ContentTag> = GENRES.mapTo(LinkedHashSet()) { (slug, name) ->
        ContentTag(name, slug, source)
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    internal data class KanmanDetails(
        val title: String,
        val authors: Set<String>,
        val coverUrl: String?,
        val largeCoverUrl: String?,
        val description: String?,
        val state: ContentState?,
        val tags: Set<ContentTag>,
    )

    private companion object {

        const val API_LIST = "/api/getsortlist/"
        const val LIST_PAGE_SIZE = 30

        const val ORDER_CLICK = "click"
        const val ORDER_DATE = "date"
        const val STATE_ONGOING = "lianzai"
        const val STATE_FINISHED = "wanjie"
        const val STATE_LABEL_ONGOING = "连载"
        const val STATE_LABEL_FINISHED = "完结"
        const val TAG_GROUP_GENRE = "题材"

        val CHAPTER_PATH_REGEX = Regex("""^/\d+/[\w\-]+\.html$""")
        val CHAPTER_IMG_LIST_REGEX = Regex("""chapter_img_list\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val QUOTED_URL_REGEX = Regex("\"(https?://[^\"]+)\"")

        // /sort/ 页面「题材」分组的全部取值（slug 与中文名），实测全部是接口接受的 comic_sort 取值
        val GENRES = listOf(
            "rexue" to "热血",
            "jizhan" to "机战",
            "yundong" to "运动",
            "tuili" to "推理",
            "maoxian" to "冒险",
            "gaoxiao" to "搞笑",
            "zhanzhen" to "战争",
            "shenmo" to "神魔",
            "renzhe" to "忍者",
            "jingji" to "竞技",
            "xuanyi" to "悬疑",
            "shehui" to "社会",
            "lianai" to "恋爱",
            "chongwu" to "宠物",
            "xixue" to "吸血",
            "luoli" to "萝莉",
            "yujie" to "御姐",
            "bazong" to "霸总",
            "xuanhuan" to "玄幻",
            "gufeng" to "古风",
            "lishi" to "历史",
            "mangai" to "漫改",
            "youxi" to "游戏",
            "chuanyue" to "穿越",
            "kongbu" to "恐怖",
            "zhenren" to "真人",
            "kehuan" to "科幻",
            "dushi" to "都市",
            "wuxia" to "武侠",
            "xiuzhen" to "修真",
            "shenghuo" to "生活",
            "dongzuo" to "动作",
        )
    }
}
