package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.delay
import okhttp3.Headers
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * 九二漫画（www.92mh.com）—— 自研 CMS（SinMH 模板），全站服务端渲染，图片走自家 CDN。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/list/?page=N` 是默认（按发布倒序），36 部/页；排序是路径段而不是 query 参数——
 *   更新 `/list/update/`、人气 `/list/-click/`（带减号前缀才是「按点击」）。
 * - 筛选：分类标签拼接成 `/list/{标签1}-{标签2}-/?page=N`（末尾必须有 `-`，否则标签被忽略）；
 *   排序与筛选同时用时是 `/list/shaonian-/update/`、`/list/shaonian-/click/`。
 *   注意站点只有「类型」组是顶层标签，剧情/地区/受众组必须带分类前缀（`/list/shaonian-danmei/`），
 *   直接访问 `/list/huanlexiang-/` 会是空列表，所以这里只暴露「类型」组。
 * - 搜索：`/search/?keywords={关键字}&page={页码}`（参数名是 keywords，用 keyword 会被 302 回首页）。
 * - 详情：`/manhua/{漫画id}/`，书名 `h1`、封面在页面内联脚本 `var pageImage`、
 *   作者/状态/类别/类型在 `ul.comic_deCon_liO` 的 `li` 文本里、简介 `p.comic_deCon_d`、
 *   目录是若干段 `ul#chapter-list-N li a`——短篇只有 chapter-list-1（正序、几百话内联），
 *   长篇会拆成「最新几十话 + 公告 + 开头全量」，各段按段内顺序拼起来不是阅读顺序，要按章节 id 升序重排。
 * - 阅读页：`/manhua/{漫画id}/{章节id}.html`，图片地址由内联脚本 `var chapterImages = [...]`
 *   给出（根路径形式，斜杠被转义成 `\/`），需要拼上 CDN 域名；CDN 域名写在 `/js/config.js`
 *   的 `resHost[0].domain`，会变，所以运行时取并缓存。
 * - 章节标题不保证是纯数字：`第01话`、`第4.2话`、`9话番外`、`单行本01`、`元旦祝贺` 都存在，
 *   能解析出数字就用数字，解析不出来按目录顺序编号。
 * - 部分漫画详情页只给出 1 话（例如 7702 只列「第93话」，`/list/`、`/all/`、`?page=2` 都一样），
 *   这是站点自己给的内容，解析器照实反映，不做补全。
 */
@ContentSourceParser("JIUERMANHUA", "九二漫画", "zh", type = ContentType.MANGA)
internal class Jiuermanhua(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.JIUERMANHUA, pageSize = 36) {

    override val configKeyDomain = ConfigKey.Domain("www.92mh.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = runCatching { parseCategories(fetchDocument("https://$domain/list/")) }
            .getOrDefault(emptyList())
        return ContentListFilterOptions(
            availableTags = tags.toSet(),
            tagGroups = if (tags.isEmpty()) emptyList() else listOf(ContentTagGroup("类型", tags.toSet())),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        val url = if (query.isNotEmpty()) {
            "https://$domain/search/?keywords=${query.urlEncoded()}&page=$page"
        } else {
            buildListUrl(page, order, filter.tags)
        }
        val doc = fetchDocument(url)
        return parseListPage(doc).also {
            if (it.isEmpty() && doc.selectFirst("ul.list_con_li, .list-view") == null) {
                throw ParseException(shortMessage = "九二漫画：列表页结构缺失", url = url)
            }
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val info = doc.select("ul.comic_deCon_liO li").mapNotNull { it.textOrNull() }
        val title = doc.selectFirst("h1")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.title
        val cover = pageVar(doc, "pageImage")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toAbsoluteUrl(domain)
            ?: manga.coverUrl
        val authors = info.firstOrNull { it.startsWith("作者") }
            ?.substringAfter("作者")
            ?.trimStart('：', ':', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { setOf(it) }
            ?: manga.authors
        val genreNames = info.firstOrNull { it.startsWith("类型") }
            ?.substringAfter("类型")
            ?.let(::parseGenreNames)
            .orEmpty()
        val tags = genreNames.mapTo(HashSet()) { ContentTag(it, it, source) }.ifEmpty { manga.tags }
        val state = info.firstOrNull { it.startsWith("状态") }
            ?.let { parseState(it) }
            ?: manga.state
        val description = doc.selectFirst("p.comic_deCon_d")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.description
        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            largeCoverUrl = cover,
            authors = authors,
            tags = tags,
            state = state,
            chapters = parseChapters(doc),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val images = parsePageImages(doc, fetchImageHost())
        if (images.isEmpty()) {
            throw ParseException(shortMessage = "九二漫画：阅读页没有解析到图片（可能是付费/下架章节）", url = url)
        }
        return images.map { imageUrl ->
            ContentPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = imageUrl,
                source = source,
            )
        }
    }

    internal fun buildListUrl(page: Int, order: SortOrder, tags: Set<ContentTag>): String {
        val keys = tags.mapNotNull { it.key?.trim()?.takeIf(String::isNotEmpty) }
        val prefix = if (keys.isEmpty()) "" else keys.joinToString("-") + "-"
        // 站点把排序放在路径里；无标签时人气排序要写成 `-click`（带减号），有标签时写成 `click`
        val sortSegment = when (order) {
            SortOrder.UPDATED -> if (keys.isEmpty()) "update/" else "/update/"
            SortOrder.POPULARITY -> if (keys.isEmpty()) "-click/" else "/click/"
            else -> if (keys.isEmpty()) "" else "/"
        }
        return "https://$domain/list/$prefix$sortSegment?page=$page"
    }

    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("li.list-comic").mapNotNull { item ->
            // 列表页卡片是 a.comic_img + h3，搜索结果卡片是 a.image-link + p > a，两种都要认
            val link = item.selectFirst("h3 a[href]")
                ?: item.selectFirst("a.comic_img[href]")
                ?: item.selectFirst("p a[href]")
                ?: item.selectFirst("a.image-link[href]")
                ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = item.selectFirst("h3 a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.selectFirst("p a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: link.attrOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.selectFirst("img")?.attrOrNull("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val cover = item.selectFirst("a.comic_img img")
                ?.attrAsRelativeUrlOrNull("src")
                ?.toAbsoluteUrl(domain)
                ?: item.selectFirst("a.image-link img")
                    ?.attrAsRelativeUrlOrNull("src")
                    ?.toAbsoluteUrl(domain)
            val paragraphs = item.select("span.comic_list_det p")
                .ifEmpty { item.select("p") }
                .mapNotNull { it.textOrNull() }
            val author = paragraphs.firstOrNull { it.startsWith("作者") }
                ?.substringAfter("作者")
                ?.trimStart('：', ':', ' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: item.selectFirst("p.auth")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val genres = paragraphs.firstOrNull { it.startsWith("类型") }
                ?.substringAfter("类型")
                ?.let(::parseGenreNames)
                .orEmpty()
            val status = paragraphs.firstOrNull { it.startsWith("状态") }
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = genres.mapTo(HashSet()) { ContentTag(it, it, source) },
                state = status?.let(::parseState),
                authors = author?.let { setOf(it) } ?: emptySet(),
                source = source,
            )
        }
    }

    /**
     * 题材文本在列表页是「#校园/#格斗」、详情页是「：欢乐向 | 生活」，去掉冒号、井号这些装饰。
     */
    internal fun parseGenreNames(raw: String): List<String> = raw.split('/', '|')
        .mapNotNull { part ->
            part.trim()
                .trimStart('：', ':', '｜', '|', '#', '-', '—', ' ')
                .trimStart('#')
                .trim()
                .takeIf { it.isNotEmpty() }
        }

    internal fun parseCategories(doc: Document): List<ContentTag> {
        // 「按类型」是唯一顶层可用的标签组，其余组都必须带分类前缀
        val group = doc.select("div.filter-item")
            .firstOrNull { it.selectFirst("label")?.textOrNull()?.contains("类型") == true }
            ?: return emptyList()
        val currentCategory = doc.selectFirst("div.filter-item a.active[href^=/list/]")
            ?.attr("href")
            ?.removePrefix("/list/")
            ?.removeSuffix("/")
            ?.takeIf { it.isNotEmpty() && it != "-" }
        return group.select("a[href^=/list/]").mapNotNull { a ->
            val key = a.attr("href")
                .removePrefix("/list/")
                .removeSuffix("/")
                .removeSuffix("-")
                .takeIf { it.isNotEmpty() && it != "-" }
                ?: return@mapNotNull null
            val name = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // 分类页里「全部」指向当前分类（/list/shaonian/），不是全站列表，要跳过
            if (key == currentCategory && name == "全部") return@mapNotNull null
            if (name == "全部") return@mapNotNull null
            ContentTag(name, key, source)
        }.distinctBy { it.key }
    }

    internal fun parseChapters(doc: Document): List<ContentChapter> {
        // 长篇漫画的目录被站点切成多段（chapter-list-1 是最新 ~30 话，chapter-list-3 是公告，
        // chapter-list-9 是开头的全量列表），各段互不重叠，合并后按章节 id 升序才是正确的阅读顺序
        val lists = doc.select("ul[id^=chapter-list]")
        val entries = lists.flatMap { list ->
            list.select("li a[href]").mapNotNull { a ->
                val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
                val title = a.selectFirst("span.list_con_zj")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val order = CHAPTER_ID_REGEX.find(href)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                Triple(href, title, order)
            }
        }.distinctBy { it.first }
        // 只有一段时站点给的就是正序，保持原样；多段时按章节 id 升序排才是阅读顺序
        val ordered = if (lists.size <= 1) entries else entries.sortedBy { it.third }

        var lastNumber = 0f
        return ordered.mapIndexed { index, (href, title, _) ->
            // 标题里的数字不保证递增（「03话番外」会回到 3），这里兜一下
            val number = (parseChapterNumber(title) ?: (index + 1).toFloat()).coerceAtLeast(lastNumber + 0.001f)
            lastNumber = number
            ContentChapter(
                id = generateUid(href),
                title = title,
                number = number,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
    }

    internal fun parsePageImages(doc: Document, imageHost: String): List<String> {
        val script = doc.selectFirst("body > script")?.html().orEmpty()
        val rawImages = script.substringBetweenFirst("chapterImages = ", ";") ?: return emptyList()
        val list = parseJsArray(rawImages)
        if (list.isEmpty()) {
            return emptyList()
        }
        val path = script.substringBetweenFirst("chapterPath = \"", "\";").orEmpty()
        return list.mapNotNull { image ->
            when {
                image.startsWith("http://") || image.startsWith("https://") -> image
                image.startsWith("/") -> imageHost + image
                else -> "$imageHost/$path$image"
            }.takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun fetchImageHost(): String {
        imageHost?.let { return it }
        val configUrl = "https://$domain/js/config.js"
        val script = webClient.httpGet(configUrl).parseRaw()
        val host = RES_HOST_REGEX.find(script)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ParseException(shortMessage = "九二漫画：config.js 里没有可用的图片域名", url = configUrl)
        imageHost = host
        return host
    }

    internal fun pageVar(doc: Document, name: String): String? {
        val script = doc.select("script").map { it.html() }.firstOrNull { it.contains("var $name") } ?: return null
        return script.substringBetweenFirst("var $name = \"", "\";")?.takeIf { it.isNotEmpty() }
    }

    private fun parseState(text: String): ContentState? = when {
        text.contains("已完结") || text.contains("完结") -> ContentState.FINISHED
        text.contains("连载") -> ContentState.ONGOING
        else -> null
    }

    private suspend fun fetchDocument(url: String): Document {
        // 站点对短时间内连续请求会回 403（限流），隔一会儿重试一次通常就能拿到页面
        var lastFailure: HttpStatusException? = null
        repeat(2) { attempt ->
            try {
                return webClient.httpGet(url).parseHtml()
            } catch (e: HttpStatusException) {
                if (e.statusCode != HTTP_FORBIDDEN && e.statusCode != HTTP_TOO_MANY_REQUESTS) {
                    throw e
                }
                lastFailure = e
                delay(1500L * (attempt + 1))
            }
        }
        throw lastFailure ?: ParseException(shortMessage = "九二漫画：请求失败", url = url)
    }

    /** 解析脚本里的字符串数组（斜杠被转义成 `\/`）：`["\/images\/a.jpg","\/images\/b.jpg"]`。 */
    internal fun parseJsArray(raw: String): List<String> = JS_STRING_REGEX.findAll(raw)
        .map { it.groupValues[1].replace("\\/", "/") }
        .filter { it.isNotEmpty() }
        .toList()

    /** 章节标题里的数字：`第01话` → 1、`第4.2话` → 4.2、`9话番外` → 9，解析不出来返回 null。 */
    internal fun parseChapterNumber(title: String): Float? {
        val match = CHAPTER_NUMBER_REGEX.find(title) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    private var imageHost: String? = null

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        val RES_HOST_REGEX = Regex("""resHost:.+?"?domain"?:\["(.+?)"""")
        val JS_STRING_REGEX = Regex(""""(.*?)"""")
        val CHAPTER_NUMBER_REGEX = Regex("""第?\s*(\d+(?:\.\d+)?)\s*[话話章节]""")
        val CHAPTER_ID_REGEX = Regex("""/manhua/\d+/(\d+)\.html""")
    }
}

