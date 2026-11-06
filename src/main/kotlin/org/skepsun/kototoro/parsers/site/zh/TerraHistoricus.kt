package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * 明日方舟漫画站（terra-historicus.hypergryph.com）—— 鹰角网络的官方漫画/绘本站。
 *
 * 站点协议（2026-11-06 实测，全站只有 JSON 接口，无 HTML 抓取）：
 * - 频道（topicKey）只有两个：`terra-historicus`（泰拉记事，22 部）与 `talos-ii-historicus`（塔罗斯II，6 部）。
 *   作品列表 `GET /api/comic?topicKey={频道}`，条目字段 `cid`（作品 id）、`title`、`subtitle`、`cover`、`authors`。
 * - 最近更新 `GET /api/recentUpdate?topicKey={频道}`，条目是「作品 + 最新章节」：`comicCid`、`title`、
 *   `coverUrl`、`subtitle`、`episodeCid`、`episodeShortTitle`、`updateTime`（秒）。
 * - 详情 `GET /api/comic/{cid}`（不带 topicKey）：`introduction`、`keywords`、`authors`、`episodes[]`，
 *   每话字段是 `cid`（**是话 id，不是作品 id**）、`title`、`shortTitle`、`displayTime`（秒）。
 * - 阅读 `GET /api/comic/{cid}/episode/{eid}` 给 `pageInfos[]`（含 width/height/doublePage），页数就是数组长度。
 * - 图片 `GET /api/comic/{cid}/episode/{eid}/page?pageNum={n}` 返回 `{"code":0,"data":{"url":"https://res01.hycdn.cn/..."}}`，
 *   URL 带时效签名，所以这里保存的是「取图接口地址」，由 [getPageUrl] 现取现用。
 *
 * 站点本身没有分页和排序参数：两个频道各自一次给全，因此这里把「页码」用作频道位（第 1 页 = 泰拉记事，
 * 第 2 页 = 塔罗斯II，第 3 页起为空），并用标签让用户手动选频道，不伪造不存在的翻页能力。
 */
@ContentSourceParser("TERRA_HISTORICUS", "明日方舟漫画", "zh", type = ContentType.MANGA)
internal class TerraHistoricus(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.TERRA_HISTORICUS, pageSize = PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("terra-historicus.hypergryph.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchWithFiltersSupported = false,
        )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val channels = CHANNELS.map { (key, title) -> ContentTag(title, key, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = channels,
            tagGroups = listOf(ContentTagGroup(TAG_GROUP_CHANNEL, channels)),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 站点没有分页参数：第 1/2 页分别是两个频道，之后没有更多
        if (page > CHANNELS.size) return emptyList()
        val channel = filter.tags.firstNotNullOfOrNull { tag -> CHANNELS.firstOrNull { it.first == tag.key } }
            ?: CHANNELS[page - 1]
        val channelKey = channel.first
        val path = when (order) {
            SortOrder.UPDATED -> API_RECENT + "?topicKey=" + channelKey
            else -> API_COMIC + "?topicKey=" + channelKey
        }
        val url = "https://$domain$path"
        val items = if (order == SortOrder.UPDATED) {
            parseRecentJson(fetchJson(url), channelKey, url)
        } else {
            parseComicListJson(fetchJson(url), channelKey, url)
        }
        val query = filter.query?.trim().orEmpty()
        if (query.isEmpty()) {
            return items
        }
        // 站点接口不支持搜索，官方源也是本地按标题过滤
        return items.filter { it.title.contains(query, ignoreCase = true) }
    }

    override suspend fun getDetails(manga: Content): Content {
        val (channelKey, comicId) = parseComicUrl(manga.url, manga)
        val url = "https://$domain/api/comic/$comicId"
        val data = JSONObject(fetchJson(url)).payload(url)
        val episodes = data.optJSONArray("episodes") ?: JSONArray()
        val chapters = parseChapters(episodes, channelKey, comicId)
        return manga.copy(
            title = data.optString("title").trim().takeIf { it.isNotEmpty() } ?: manga.title,
            description = data.optString("introduction").trim().takeIf { it.isNotEmpty() } ?: manga.description,
            coverUrl = data.optString("cover").trim().takeIf { it.isNotEmpty() } ?: manga.coverUrl,
            authors = toStringSet(data.optJSONArray("authors")).ifEmpty { manga.authors },
            tags = toStringSet(data.optJSONArray("keywords")).mapTo(HashSet()) { ContentTag(it, it, source) }
                .ifEmpty { manga.tags },
            chapters = chapters.ifEmpty { manga.chapters },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val ids = parseChapterUrl(chapter.url, chapter)
        val url = "https://$domain/api/comic/${ids.first}/episode/${ids.second}"
        val data = JSONObject(fetchJson(url)).payload(url)
        val pages = data.optJSONArray("pageInfos") ?: JSONArray()
        if (pages.length() == 0) {
            throw ParseException("明日方舟漫画：该话没有页面数据（可能是预告/未发布）", url)
        }
        return (1..pages.length()).map { pageNum ->
            ContentPage(
                id = pageNum.toLong(),
                url = "$url/page?pageNum=$pageNum",
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        val url = page.url
        val data = JSONObject(fetchJson(url)).payload(url)
        return data.optString("url").trim().takeIf { it.isNotEmpty() }
            ?: throw ParseException("明日方舟漫画：取图接口没有返回图片地址", url)
    }

    /** `/api/comic?topicKey=…` 的作品条目。 */
    internal fun parseComicListJson(json: String, channelKey: String, requestUrl: String): List<Content> {
        val items = payloadArray(json, requestUrl)
        if (items.length() == 0) {
            throw ParseException("明日方舟漫画：作品列表为空，频道或接口可能已变化", requestUrl)
        }
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val cid = item.optString("cid").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            item.toContent(channelKey, cid, title, item.optString("cover"), item.optString("subtitle"))
        }
    }

    /** `/api/recentUpdate?topicKey=…` 是「作品 + 最新章节」结构，只取作品级字段。 */
    internal fun parseRecentJson(json: String, channelKey: String, requestUrl: String): List<Content> {
        val items = payloadArray(json, requestUrl)
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val cid = item.optString("comicCid").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            item.toContent(channelKey, cid, title, item.optString("coverUrl"), item.optString("subtitle"))
        }
    }

    private fun JSONObject.toContent(
        channelKey: String,
        cid: String,
        title: String,
        cover: String,
        subtitle: String,
    ): Content {
        val path = "/$channelKey/comic/$cid"
        return Content(
            id = generateUid(path),
            title = title,
            altTitles = emptySet(),
            url = path,
            publicUrl = path.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover.trim().takeIf { it.isNotEmpty() },
            tags = emptySet(),
            state = null,
            authors = toStringSet(optJSONArray("authors")),
            source = source,
            description = subtitle.trim().takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 话 id 与作品 id 混用同一个 `cid` 字段，这里显式带上作品 id 才能拼出取图地址。
     * 接口给的是新话在前，统一翻成从前到后并补序号。
     */
    internal fun parseChapters(
        episodes: JSONArray,
        channelKey: String,
        comicId: String,
    ): List<ContentChapter> = (episodes.length() - 1 downTo 0).mapNotNull { index ->
        val episode = episodes.optJSONObject(index) ?: return@mapNotNull null
        val eid = episode.optString("cid").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val shortTitle = episode.optString("shortTitle").trim()
        val title = episode.optString("title").trim()
        val name = listOf(shortTitle, title).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { return@mapNotNull null }
        val path = "/$channelKey/comic/$comicId/$eid"
        ContentChapter(
            id = generateUid(path),
            title = name,
            number = (episodes.length() - index).toFloat(),
            volume = 0,
            url = path,
            scanlator = null,
            uploadDate = episode.optLong("displayTime", 0L) * 1000L,
            branch = null,
            source = source,
        )
    }

    internal fun parseComicUrl(url: String, manga: Content): Pair<String, String> {
        val match = COMIC_PATH_REGEX.matchEntire(url)
            ?: throw ParseException("明日方舟漫画：无法从地址解析作品 id", manga.url)
        return match.groupValues[1] to match.groupValues[2]
    }

    internal fun parseChapterUrl(url: String, chapter: ContentChapter): Pair<String, String> {
        val match = CHAPTER_PATH_REGEX.matchEntire(url)
            ?: throw ParseException("明日方舟漫画：无法从地址解析话 id", chapter.url)
        return match.groupValues[2] to match.groupValues[3]
    }

    /** 详情/阅读/取图接口的 `data` 是对象，`code` 非 0 即站点侧异常。 */
    private fun JSONObject.payload(requestUrl: String): JSONObject {
        if (optInt("code", -1) != 0) {
            throw ParseException("明日方舟漫画：接口返回错误 code=${optInt("code")}", requestUrl)
        }
        return optJSONObject("data")
            ?: throw ParseException("明日方舟漫画：接口响应缺少 data", requestUrl)
    }

    /** 列表/更新接口的 `data` 是数组。 */
    private fun payloadArray(json: String, requestUrl: String): JSONArray {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw ParseException("明日方舟漫画：接口返回的不是 JSON", requestUrl)
        }
        if (root.optInt("code", -1) != 0) {
            throw ParseException("明日方舟漫画：接口返回错误 code=${root.optInt("code")}", requestUrl)
        }
        return root.optJSONArray("data")
            ?: throw ParseException("明日方舟漫画：接口响应缺少 data 数组", requestUrl)
    }

    private fun toStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return (0 until array.length())
            .mapNotNull { array.optString(it).trim().takeIf { value -> value.isNotEmpty() } }
            .toSet()
    }

    private suspend fun fetchJson(url: String): String = webClient.httpGet(url).parseRaw()

    private companion object {
        /** 站点一次给全一个频道（22 / 6 部），声明一个够大的页容量，避免宿主误判还有下一页。 */
        const val PAGE_SIZE = 30

        const val TAG_GROUP_CHANNEL = "频道"
        const val API_COMIC = "/api/comic"
        const val API_RECENT = "/api/recentUpdate"

        val CHANNELS = listOf(
            "terra-historicus" to "泰拉记事",
            "talos-ii-historicus" to "塔罗斯II",
        )

        val COMIC_PATH_REGEX = Regex("""/([\w-]+)/comic/(\d+)""")
        val CHAPTER_PATH_REGEX = Regex("""/([\w-]+)/comic/(\d+)/(\d+)""")
    }
}
