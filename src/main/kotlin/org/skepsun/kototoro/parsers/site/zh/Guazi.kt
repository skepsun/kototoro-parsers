package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
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

/**
 * 瓜子漫画（www.guazimanhua.com）—— 免费的国产 / 韩漫 / 日漫聚合站，页面全部服务端渲染。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/category.php?page=N`（36 部/页，共约 670 页），卡片是 `section.grid > article.card`：
 *   `a.cover-wrap[href]` + `img.cover[src]` + `span.genre`（连载 / 完结）+ `h3 > a`（标题）+ `.meta`（作者 · 题材）。
 *   排序走 `sort=update|hits|daily|score`，题材走 `cid=N`，两者可组合。
 * - 详情：`/comic.php?id={id}`。标题在 `h1`，作者 / 简介 / 封面 / 题材在 JSON-LD 的 `ComicStory` 节点里；
 *   章节目录同时出现在 JSON-LD `ItemList` 和 HTML 的 `a[href^=/chapter.php?id=]` 中，
 *   HTML 顺序是**新章在前**，这里倒序后按出现顺序返回。
 * - 阅读页：`/chapter.php?id={id}`，正文图片在 `section.reader-images > img.reading-image[src]`，
 *   直链 webp、无防盗链；同一页还有封面与推荐作品的 `<img>`，必须限定容器才不会混进来。
 * - 搜索：站点没有可用的搜索入口（`search.php` 404），因此不声明搜索能力。
 */
@ContentSourceParser("GUAZI", "瓜子漫画", "zh", type = ContentType.MANGA)
internal class Guazi(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.GUAZI, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.guazimanhua.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genres = GENRES.map { (code, title) -> ContentTag(title, code, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = genres,
            tagGroups = listOf(ContentTagGroup("题材", genres)),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val tag = filter.tags.firstOrNull()?.key?.takeIf { key -> GENRES.any { it.first == key } }
        val url = listUrl(page, order, tag)
        val items = parseListPage(fetchDocument(url))
        if (items.isEmpty() && page == 1) {
            throw ParseException("瓜子漫画列表为空，可能是站点结构变化", url)
        }
        return items
    }

    /** 解析分类页的卡片区，抽出来便于离线夹具测试。 */
    internal fun parseListPage(doc: Document): List<Content> =
        doc.select("section.grid article.card").mapNotNull { parseCard(it) }

    private fun listUrl(page: Int, order: SortOrder, cid: String?): String {
        val sort = when (order) {
            SortOrder.POPULARITY -> "hits"
            SortOrder.RATING -> "score"
            else -> "update"
        }
        return buildString {
            append("https://$domain/category.php?sort=").append(sort)
            if (cid != null) append("&cid=").append(cid)
            if (page > 1) append("&page=").append(page)
        }
    }

    private fun parseCard(card: Element): Content? {
        val link = card.selectFirst("a.cover-wrap[href*=/comic.php?id=]")
            ?: card.selectFirst("h3 a[href*=/comic.php?id=]")
            ?: return null
        val url = link.attrOrNull("href")?.toAbsoluteUrl(domain) ?: return null
        val id = url.substringAfterLast("id=", "").substringBefore('&').takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst("h3 a")?.textOrNull() ?: link.attrOrNull("title") ?: return null
        val cover = card.selectFirst("img.cover[src], img[src]")?.let {
            it.attrOrNull("data-src") ?: it.attrOrNull("src")
        }?.toAbsoluteUrl(domain)
        val isCompleted = card.selectFirst("span.genre")?.textOrNull()?.contains("完结") == true
        return Content(
            id = generateUid(id),
            title = title.trim(),
            altTitles = emptySet(),
            url = url,
            publicUrl = url,
            rating = card.selectFirst("span.score")?.textOrNull()?.toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            tags = emptySet(),
            state = if (isCompleted) ContentState.FINISHED else ContentState.ONGOING,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = fetchDocument(manga.url)
        val info = parseComicStory(doc)
        val title = doc.selectFirst("h1")?.textOrNull()?.trim().orEmpty().ifEmpty { manga.title }
        val chapters = parseChapterList(doc)
        if (chapters.isEmpty()) {
            throw NotFoundException("瓜子漫画：作品不存在或已下架", manga.url)
        }
        val tags = info?.genre?.filterNotNull()?.map { ContentTag(it.trim(), it.trim(), source) }?.toSet().orEmpty()
        return manga.copy(
            title = title,
            altTitles = manga.altTitles,
            authors = info?.author?.split(',', '，')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            description = info?.description?.trim(),
            coverUrl = info?.image?.toAbsoluteUrl(domain) ?: manga.coverUrl,
            tags = tags,
            chapters = chapters,
        )
    }

    /** JSON-LD 的 ComicStory 节点；站点结构变化时返回 null，让详情退化成只用 HTML 能拿到的信息。 */
    private fun parseComicStory(doc: Document): ComicStoryJson? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { org.json.JSONObject(script.data()) }.getOrNull() ?: continue
            val nodes = root.optJSONArray("@graph") ?: continue
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                if (node.optString("@type") != "ComicStory") continue
                val genre = node.optJSONArray("genre")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                val author = node.optJSONObject("author")
                return ComicStoryJson(
                    image = node.optString("image").takeIf(String::isNotBlank),
                    description = node.optString("description").takeIf(String::isNotBlank),
                    author = author?.optString("name")?.takeIf(String::isNotBlank),
                    genre = genre,
                )
            }
        }
        return null
    }

    /**
     * 章节目录：完整目录在 `[data-mobile-chapter-list]` 的 `<a>` 里（新章在前，倒序后返回）。
     * JSON-LD 的 `ItemList` 只在 50 话以内是全量，超过就截断（481 话的作品只给 50 条），所以只作兜底。
     */
    internal fun parseChapterList(doc: Document): List<ContentChapter> {
        val links = doc.select("[data-mobile-chapter-list] a[href*=/chapter.php?id=]")
            .ifEmpty { doc.select("a[href*=/chapter.php?id=]") }
        val result = parseChapterLinks(links)
        if (result.isNotEmpty()) {
            return result.asReversed().mapIndexed { index, chapter ->
                if (chapter.number > 0f) chapter else chapter.copy(number = (index + 1).toFloat())
            }
        }
        val fromJson = doc.select("script[type=application/ld+json]").mapNotNull { script ->
            runCatching { org.json.JSONObject(script.data()) }.getOrNull()
        }.flatMap { root ->
            val nodes = root.optJSONArray("@graph") ?: return@flatMap emptyList()
            (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
        }.firstOrNull { it.optString("@type") == "ItemList" && it.optString("name").contains("章节目录") }
            ?.optJSONArray("itemListElement")
            ?: return emptyList()
        return (0 until fromJson.length()).mapNotNull { index ->
            val node = fromJson.optJSONObject(index) ?: return@mapNotNull null
            val url = node.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = node.optString("name").takeIf(String::isNotBlank) ?: "第 ${index + 1} 话"
            val chapterId = url.substringAfterLast("id=", "").substringBefore('&')
            ContentChapter(
                id = generateUid(chapterId.ifBlank { index.toString() }),
                title = name,
                number = chapterNumber(name).takeIf { it > 0f } ?: (index + 1).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
    }

    private fun parseChapterLinks(links: List<Element>): List<ContentChapter> {
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<ContentChapter>()
        for (a in links) {
            val href = a.attrOrNull("href")?.toAbsoluteUrl(domain) ?: continue
            val chapterId = href.substringAfterLast("id=", "").substringBefore('&')
            if (chapterId.isBlank() || !seen.add(chapterId)) continue
            val name = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "第 $chapterId 话"
            chapters += ContentChapter(
                id = generateUid(chapterId),
                title = name,
                number = chapterNumber(name),
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
        return chapters
    }

    /**
     * 话号写法不统一：有的是 `473-叛逆的民国少女`，有的是 `第219话`，还有 `花朝节贺图` 这类没有编号的，
     * 解析不出来时返回 -1，交给调用方按顺序补号。
     */
    private fun chapterNumber(title: String): Float {
        val leading = Regex("""^\s*(\d+(?:\.\d+)?)""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
        if (leading != null) return leading
        return Regex("""第\s*(\d+(?:\.\d+)?)\s*[话話章节節回]""").find(title)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val doc = fetchDocument(chapter.url)
        val pages = parsePageList(doc, chapter.id)
        if (pages.isEmpty()) {
            throw ParseException("阅读页没有图片，可能未登录或该话已下架", chapter.url)
        }
        return pages
    }

    /** 正文图片只在 `section.reader-images` 里，同页的封面 / 推荐作品图片必须排除。 */
    internal fun parsePageList(doc: Document, chapterId: Long): List<ContentPage> =
        doc.select("section.reader-images img.reading-image").mapIndexedNotNull { index, img ->
            val url = (img.attrOrNull("src") ?: img.attrOrNull("data-src"))
                ?.toAbsoluteUrl(domain)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            ContentPage(
                id = generateUid("${chapterId}_$index"),
                url = url,
                preview = null,
                source = source,
            )
        }

    override suspend fun getPageUrl(page: ContentPage): String = page.url ?: throw ParseException(
        "瓜子漫画页面缺少图片地址",
        page.id.toString(),
    )

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private data class ComicStoryJson(
        val image: String?,
        val description: String?,
        val author: String?,
        val genre: List<String>,
    )

    private companion object {
        const val LIST_PAGE_SIZE = 36

        val GENRES = listOf(
            "41" to "耽美",
            "9" to "恋爱",
            "29" to "校园",
            "5" to "霸总",
            "42" to "都市",
            "8" to "穿越",
            "23" to "古风",
            "25" to "玄幻",
            "31" to "奇幻",
            "22" to "科幻",
            "21" to "灵异",
            "54" to "动作",
            "11" to "悬疑",
            "30" to "冒险",
            "17" to "真人",
            "15" to "搞笑",
            "13" to "热血",
            "14" to "恐怖",
            "148" to "系统",
            "97" to "逆袭",
            "55" to "脑洞",
            "61" to "复仇",
            "27" to "其它",
        )
    }
}
