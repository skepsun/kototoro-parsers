package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 黄果短剧 — 竖屏 AI / 真人短剧站，直连 JSON API。
 *
 * 站点: https://huangguoai.com（备用地址发布页 https://huangguoai.pages.dev/）
 * 最后验证: 2026-11-20（经本机 7890 代理实机复核，接口见 docs/huangguo_drama_api_research.md）
 *
 * 数据模型: 一部剧 = 一个 [Content]，一集 = 一个 [ContentChapter]，播放地址是 HLS(m3u8)。
 *
 * 接口（全部匿名可用，无需登录）:
 * - 列表: GET /api/videos?page=&size=，默认序≈按 hot 倒序；`sort=new` 才是最新
 * - 分类: GET /api/videos/category/{cate}?page=&size=
 * - 搜索: GET /api/search?q=&page=&size=（参数名是 q，传 keyword 会报错）
 * - 详情: GET /api/videos/{id}，一次返回完整 episodes[]
 * - 播放: **POST** /api/videos/{id}/episodes/{ep}/playback → data.streams.main 即 m3u8，约 1 小时过期
 *
 * 注意:
 * - `page` 从 1 开始，传 0 会直接 400「请求参数无效」。
 * - 站点只会**静默忽略**未知的排序参数（未知值一律回落默认序），
 *   因此这里只暴露实测生效的两种排序。`sort=popular` 虽然写在 /api/site/sorts/video 里，
 *   但 feed 接口对它会返回 400，所以不能直接拿来用。
 * - 封面 CDN 返回的是 AES 密文而不是图片，
 *   由 [intercept] 走 [HuangguoImageDecoder] 解密。
 * - 分集 `title` 字段经常塞的是整段剧情简介而不是「第N集」，
 *   这里统一规范成「第N集」，避免异常长且露骨的文字直接进章节列表。
 * - 目前只有 `ai-duanju` / `ai-manju` 两个频道还有内容；
 *   `ai-huanlian` / `ai-mogai`（AI 换脸 / 魔改）自 2026-11-20 起返回 410「内容已下线」，
 *   已从筛选项里移除（这类换脸内容本身也不适合接入）。
 *   详情里的 `tags`（「都市」「换脸」…）没有对应接口，点选后按关键词搜索处理（见 [buildListUrl]）。
 */
@ContentSourceParser(
    "HUANGGUO",
    "黄果短剧",
    "zh",
    type = ContentType.HENTAI_VIDEO,
)
internal class HuangguoDrama(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HUANGGUO, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("huangguoai.com")

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        // 站点只有这两类频道能真正筛选，其余标签在详情里展示，但点选后按关键词搜索处理
        availableTags = CHANNELS.mapTo(linkedSetOf()) { (key, title) -> ContentTag(title, key, source) },
        availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Accept", "application/json, text/plain, */*")
        .add("Referer", "https://$domain/")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val data = response.parseJson().requireData(url)
        val items = data.optJSONArray("items") ?: return emptyList()
        val pages = data.optJSONObject("pagination")?.optInt("pages") ?: 0
        if (pages > 0 && page > pages) {
            return emptyList()
        }
        return parseList(items, url)
    }

    override suspend fun getDetails(content: Content): Content {
        val videoId = videoIdOf(content.url) ?: return content
        val url = "https://$domain${apiDetailPath(videoId)}"
        val data = webClient.httpGet(url, getRequestHeaders()).parseJson().requireData(url)
        val chapters = parseChapters(data.optJSONArray("episodes"), videoId)
            .ifEmpty { fallbackChapters(data, videoId) }
        val cover = normalizeCover(data.optString("cover"))
        val tags = parseTags(data)
        return content.copy(
            title = data.optString("title").trim().ifBlank { content.title },
            description = data.optString("description").trim().takeIf(String::isNotEmpty) ?: content.description,
            coverUrl = cover ?: content.coverUrl,
            largeCoverUrl = cover ?: content.largeCoverUrl,
            tags = tags.takeIf(Set<ContentTag>::isNotEmpty) ?: content.tags,
            state = parseState(data.optBoolean("is_finished"), content.state),
            rating = parseRating(data, content.rating),
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val episode = episodeRefOf(chapter.url)
            ?: throw ParseException("黄果短剧: 无法从章节地址解析剧集编号", chapter.url)
        val url = "https://$domain${playPath(episode.first, episode.second)}"
        // 站点 2026-11 改版后播放地址只走 POST；GET /api/videos/{id}/play?ep= 的 video_url 已恒为空
        val data = webClient.httpPost(url.toHttpUrl(), JSONObject(), getRequestHeaders())
            .parseJson()
            .requireData(url)
        val streamUrl = extractStreamUrl(data)
        if (streamUrl.isEmpty()) {
            throw ParseException("黄果短剧: 播放接口未返回 m3u8 地址", url)
        }
        return listOf(
            ContentPage(
                id = generateUid("${chapter.id}:stream"),
                url = streamUrl,
                preview = null,
                headers = mapOf(
                    "User-Agent" to config[userAgentKey],
                    "Referer" to "https://$domain/",
                ),
                source = source,
            ),
        )
    }

    /**
     * 新接口把播放地址放在 `streams.main`（另有 `hevc` / `preview` 两个变体）。
     * 旧字段 `video_url` 现在恒为空，保留兜底是为了老响应的兼容性。
     */
    private fun extractStreamUrl(data: JSONObject): String {
        val streams = data.optJSONObject("streams")
        if (streams != null) {
            streams.optString("main").trim().takeIf(String::isNotEmpty)?.let { return it }
            streams.optString("hevc").trim().takeIf(String::isNotEmpty)?.let { return it }
        }
        data.optString("video_url").trim().takeIf(String::isNotEmpty)?.let { return it }
        return data.optString("video_url_hevc").trim()
    }

    /**
     * 封面 CDN 返回 AES-128-CBC 密文，这里在响应链上解密，对上层完全透明。
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        return HuangguoImageDecoder.decodeResponse(chain.proceed(chain.request()))
    }

    /**
     * 站点只有分类接口对应的频道有真实筛选能力。详情里的 `tags`（「都市」「换脸」…）在服务端没有对应接口：
     * `/tag/{slug}/` 页面确实存在，但卡片并不按标签过滤（实测 /tag/fanzui/ 里混入大量无关剧集），
     * 所以这类标签退化成站点搜索的关键词，至少保证「点了标签列表会随之收窄」，而不是原地不动返回全站。
     */
    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val base = "https://$domain"
        val query = filter.query?.trim().orEmpty()
        // 站点搜索走独立接口，且不支持叠加分类筛选
        if (query.isNotEmpty()) {
            return searchUrl(base, page, query)
        }
        val tag = filter.tags.firstOrNull { channelOf(it) != null } ?: filter.tags.firstOrNull()
            ?: return feedUrl(base, page, order, channel = null)
        channelOf(tag)?.let { return feedUrl(base, page, order, channel = it) }
        val keyword = tag.title.trim().ifEmpty { tag.key.trim() }
        return if (keyword.isEmpty()) {
            feedUrl(base, page, order, channel = null)
        } else {
            searchUrl(base, page, keyword)
        }
    }

    private fun searchUrl(base: String, page: Int, query: String): String =
        "$base/api/search?q=${query.urlEncoded().replace("+", "%20")}&page=$page&size=$pageSize"

    private fun feedUrl(base: String, page: Int, order: SortOrder, channel: String?): String {
        val path = if (channel != null) "/api/videos/category/$channel" else "/api/videos"
        val sort = if (order == SortOrder.UPDATED) "&sort=new" else ""
        return "$base$path?page=$page&size=$pageSize$sort"
    }

    /**
     * 频道标签解析：先按 key（解析器自己发布的标签），再按完整标题，
     * 最后兼容详情标签里「换脸」「魔改」这类省掉 `AI` 前缀的写法。
     */
    private fun channelOf(tag: ContentTag): String? {
        CHANNELS.firstOrNull { (key, title) -> tag.key == key || tag.title == title }?.let { return it.first }
        val title = tag.title.trim()
        if (title.isEmpty()) return null
        return CHANNELS.firstOrNull { (_, channelTitle) -> channelTitle.removePrefix("AI") == title }?.first
    }

    internal fun parseList(items: JSONArray, url: String): List<Content> = buildList(items.length()) {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val videoId = videoIdOfItem(item) ?: continue
            add(parseCard(item, videoId))
        }
        if (isEmpty() && items.length() > 0) {
            throw ParseException("黄果短剧: 列表条目缺少 id", url)
        }
    }

    /**
     * `id` 实测是数字，但站点偶尔会把统计字段拼成字符串，这里两种都接受，
     * 单条坏数据不应该让整页失败（应用会把失败记为列表错误并停止翻页）。
     */
    private fun videoIdOfItem(item: JSONObject): Int? = when (val raw = item.opt("id")) {
        is Number -> raw.toInt().takeIf { it > 0 }
        is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

    internal fun parseCard(item: JSONObject, videoId: Int = item.optInt("id")): Content {
        val relativeUrl = detailPath("/video/$videoId")
        val cover = normalizeCover(item.optString("cover"))
        return Content(
            id = generateUid(relativeUrl),
            title = item.optString("title").trim().ifBlank { "未命名短剧" },
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = "https://$domain$relativeUrl/",
            rating = parseRating(item, RATING_UNKNOWN),
            contentRating = ContentRating.ADULT,
            coverUrl = cover,
            tags = parseTags(item),
            state = parseState(item.optBoolean("is_finished"), null),
            authors = parseAuthors(item),
            largeCoverUrl = cover,
            description = item.optString("description").trim().takeIf(String::isNotEmpty),
            source = source,
        )
    }

    internal fun parseChapters(episodes: JSONArray?, videoId: Int): List<ContentChapter> {
        if (episodes == null || episodes.length() == 0) {
            return emptyList()
        }
        val chapters = ArrayList<ContentChapter>(episodes.length())
        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue
            val number = episode.optInt("ep_num")
            if (number <= 0) continue
            val path = playPath(videoId, number)
            chapters.add(
                ContentChapter(
                    id = generateUid(path),
                    title = "第${number}集",
                    number = number.toFloat(),
                    volume = 0,
                    url = path,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                ),
            )
        }
        return chapters.distinctBy { it.number }.sortedBy { it.number }
    }

    /**
     * 详情里 `episodes` 缺失时，按 `episode_count` / `total_episodes` 补齐 1..N 集。
     *
     * 改版后播放地址不再由详情预置（`video_url` 恒为空），只要集数已知就能逐集现取，
     * 所以这里不需要再依赖任何预置流地址。
     */
    internal fun fallbackChapters(data: JSONObject, videoId: Int): List<ContentChapter> {
        val count = maxOf(data.optInt("episode_count"), data.optInt("total_episodes"))
        if (count <= 0) {
            return emptyList()
        }
        return (1..count).map { number ->
            val path = playPath(videoId, number)
            ContentChapter(
                id = generateUid(path),
                title = "第${number}集",
                number = number.toFloat(),
                volume = 0,
                url = path,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
    }

    /**
     * 把域名无关的相对路径还原成详情地址，兼容历史保存的 `/video/{id}` 与 `/video/{id}/`。
     */
    internal fun detailPath(url: String): String {
        val videoId = videoIdOf(url) ?: return url
        return "/video/$videoId"
    }

    internal fun videoIdOf(url: String): Int? {
        val match = VIDEO_ID_REGEX.find(url) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** 播放接口的站内相对地址（域名无关），播放前现取，避免缓存过期链。 */
    internal fun playPath(videoId: Int, episode: Int): String = "/api/videos/$videoId/episodes/$episode/playback"

    /**
     * 从章节地址还原「影片 id + 集号」。
     * 同时接受 2026-11 之前入库的老写法 `/api/videos/{id}/play?ep={n}`，
     * 让升级前已经下载过章节列表的用户不必重新添加收藏。
     */
    internal fun episodeRefOf(url: String): Pair<Int, Int>? {
        EPISODE_PATH_REGEX.find(url)?.let { match ->
            val videoId = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            return if (videoId != null && episode != null && videoId > 0 && episode > 0) {
                videoId to episode
            } else {
                null
            }
        }
        // 老写法：集号在查询串里，影片 id 在 /api/videos/{id} 段（与详情页的 /video/{id} 不是同一段）
        val episode = EP_QUERY_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val videoId = API_VIDEO_ID_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (videoId > 0 && episode > 0) videoId to episode else null
    }

    internal fun apiDetailPath(videoId: Int): String = "/api/videos/$videoId"

    private fun parseTags(item: JSONObject): Set<ContentTag> {
        val tags = item.optJSONArray("tags") ?: return emptySet()
        return buildSet {
            for (index in 0 until tags.length()) {
                val title = tags.optString(index).trim()
                if (title.isEmpty()) continue
                add(ContentTag(title, title, source))
            }
        }
    }

    private fun parseAuthors(item: JSONObject): Set<String> {
        val names = linkedSetOf<String>()
        item.optJSONObject("author")?.optString("name")?.trim()?.takeIf(String::isNotEmpty)?.let(names::add)
        val actors = item.optJSONArray("actors")
        if (actors != null) {
            for (index in 0 until actors.length()) {
                val name = actors.optString(index).trim()
                if (name.isNotEmpty()) names.add(name)
            }
        }
        return names
    }

    /**
     * 详情把完结状态放在 `is_finished`；未完结时沿用列表里的状态，缺省视为连载中。
     */
    private fun parseState(finished: Boolean, fallback: ContentState?): ContentState = when {
        finished -> ContentState.FINISHED
        else -> fallback ?: ContentState.ONGOING
    }

    /**
     * `score` 是 0–10 的站点评分，仓库要求 0–1；0 表示未评分。
     */
    private fun parseRating(item: JSONObject, fallback: Float): Float {
        val score = item.optDouble("score", 0.0)
        if (score <= 0.0) return fallback
        return (score / 10.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 封面 URL 带 1 小时时效的 `auth_key`。
     * 实测该参数未强制校验（去掉后依旧 200、字节一致），
     * 因此裁掉查询串，让入库/收藏后的封面地址长期有效。
     */
    internal fun normalizeCover(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val absolute = if (url.startsWith("//")) "https:$url" else url
        val bare = absolute.substringBefore('?')
        return bare.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun JSONObject.requireData(url: String): JSONObject {
        if (optInt("status", -1) != 1) {
            val message = optString("msg").trim().ifBlank { "接口返回失败" }
            throw ParseException("黄果短剧: $message", url)
        }
        return optJSONObject("data") ?: throw ParseException("黄果短剧: 响应缺少 data", url)
    }

    private companion object {

        /**
         * 站点频道。`chigua`（吃瓜）是帖子而非剧集，走 /api/posts，本解析器不接入；
         * `ai-huanlian` / `ai-mogai` 自 2026-11-20 起返回 410「内容已下线」，不再暴露，
         * 老筛选值会自然退化成关键词搜索而不报错。
         */
        val CHANNELS = listOf(
            "ai-duanju" to "AI成人短剧",
            "ai-manju" to "AI成人漫剧",
        )

        val VIDEO_ID_REGEX = Regex("""/video/(\d+)""")

        /** 接口路径里的影片 id：`/api/videos/{id}/...` */
        val API_VIDEO_ID_REGEX = Regex("""/api/videos/(\d+)""")

        /** 新播放接口写法：`/api/videos/{id}/episodes/{ep}/playback` */
        val EPISODE_PATH_REGEX = Regex("""/api/videos/(\d+)/episodes/(\d+)/playback""")

        /** 2026-11 改版前的老写法：`/api/videos/{id}/play?ep={n}` */
        val EP_QUERY_REGEX = Regex("""[?&]ep=(\d+)""")
    }
}
