package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * 轻之国度
 *
 * 站点 2026 改版后存在两套 API：
 * - 旧版 `/proxy/api/category…`、`/proxy/api/search…`：Envelope 请求体 `{is_encrypted,platform,client,sign,gz,d}`；
 *   分类浏览（gid=106 最新）仍可用，旧搜索接口已废弃（code 5）
 * - 新版 BFF `/proxy/api/bff/apk-search-*-v1`：免签名的统一搜索/分面接口。
 *   `apk-search-taxonomy-v1` 提供标签分类（tag_id/中文名/分组）与频道；
 *   `apk-search-result-v1` 以 `primary_tag`=标签中文名过滤、`channel_code`/`work_type` 选频道、
 *   `status_bucket`=completed|serializing、sort=relevance|new，page 1 基、参数 pageSize 为驼峰。
 *   搜索或带标签/状态过滤时走它；纯浏览仍走旧版分类接口（v1 无 q 时须绑定频道）。
 * - 正文/详情接口需前端 HMAC 签名（密钥不下发），章节能力受限，见 getDetails 注释
 */
@ContentSourceParser("LKNOVEL_US", "轻之国度", "zh", type = ContentType.NOVEL)
internal class LKNovelUs(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.valueOf("LKNOVEL_US"), pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("www.lightnovel.fun")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun getRequestHeaders(): okhttp3.Headers {
        return super.getRequestHeaders().newBuilder()
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .add("Content-Type", "application/json")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private fun createBaseBody(data: JSONObject): JSONObject = JSONObject().apply {
        // 与站点 Web 端一致的参数，避免返回默认列表或校验失败
        put("is_encrypted", 0)
        put("platform", "pc")
        put("client", "web")
        put("sign", "")
        put("gz", 0)
        put("d", data as Any)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    /**
     * 标签/频道来自 `apk-search-taxonomy-v1`（免签名）。
     * 频道互斥（work_type 单值），标签单选（primary_tag 单值）
     */
    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val response = postJson(
            "https://$domain/proxy/api/bff/apk-search-taxonomy-v1",
            createBaseBody(JSONObject()),
        ).parseJson()
        val data = response.optJSONObject("data")
            ?: throw ParseException(
                "LKNOVEL_US: taxonomy 响应缺少 data 字段",
                "https://$domain/proxy/api/bff/apk-search-taxonomy-v1",
            )
        return parseTaxonomy(data)
    }

    internal fun parseTaxonomy(data: JSONObject): ContentListFilterOptions {
        val groups = LinkedHashMap<String, MutableSet<ContentTag>>()
        data.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tag = arr.optJSONObject(i) ?: continue
                val name = tag.optString("name").trim()
                if (name.isEmpty()) continue
                val group = TAG_GROUP_TITLES[tag.optString("display_group")] ?: TAG_GROUP_FALLBACK_TITLE
                groups.getOrPut(group) { LinkedHashSet() } += ContentTag(name, TAG_KEY_PREFIX + name, source)
            }
        }
        data.optJSONArray("channels")?.let { arr ->
            val channels = LinkedHashSet<ContentTag>()
            for (i in 0 until arr.length()) {
                val channel = arr.optJSONObject(i) ?: continue
                val code = channel.optString("code")
                val label = channel.optString("label").ifBlank { code }
                if (code.isEmpty()) continue
                channels += ContentTag(label, CHANNEL_KEY_PREFIX + code, source)
            }
            if (channels.isNotEmpty()) {
                groups[CHANNEL_GROUP_TITLE] = channels
            }
        }
        return ContentListFilterOptions(
            availableTags = groups.values.flatten().toSet(),
            tagGroups = groups.map { (title, tags) ->
                ContentTagGroup(title, tags, isExclusive = title == CHANNEL_GROUP_TITLE || title == TAG_GROUP_TITLES["hot"])
            },
            availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
            availableContentTypes = EnumSet.of(ContentType.NOVEL),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 旧版分类浏览不支持标签/状态过滤；一旦有查询或过滤条件就切到 v1 统一搜索
        val needsUnifiedSearch = !filter.query.isNullOrBlank() ||
            filter.tags.isNotEmpty() ||
            filter.states.isNotEmpty()
        return if (needsUnifiedSearch) {
            val response = postJson(
                "https://$domain/proxy/api/bff/apk-search-result-v1",
                createBaseBody(buildUnifiedSearchData(page, order, filter)),
            ).parseJson()
            parseUnifiedSearchResult(response)
        } else {
            explore(page)
        }
    }

    internal fun buildUnifiedSearchData(page: Int, order: SortOrder, filter: ContentListFilter): JSONObject {
        val query = filter.query?.trim().orEmpty()
        val primaryTag = filter.tags.firstOrNull { it.key.startsWith(TAG_KEY_PREFIX) }?.key?.removePrefix(TAG_KEY_PREFIX)
        val channel = filter.tags.firstOrNull { it.key.startsWith(CHANNEL_KEY_PREFIX) }?.key?.removePrefix(CHANNEL_KEY_PREFIX)
            ?.takeIf { channelFilter -> CHANNEL_CODES.contains(channelFilter) }
        val status = when (filter.states.firstOrNull()) {
            ContentState.FINISHED -> "completed"
            ContentState.ONGOING -> "serializing"
            null, ContentState.ABANDONED, ContentState.PAUSED, ContentState.UPCOMING, ContentState.RESTRICTED -> "all"
        }
        return JSONObject().apply {
            put("q", query)
            put("page", page)
            put("pageSize", pageSize)
            put("sort", if (order == SortOrder.RELEVANCE && query.isNotEmpty()) "relevance" else "new")
            if (primaryTag != null) put("primary_tag", primaryTag)
            // 无关键词浏览时 v1 必须绑定频道；缺省用站点默认频道（轻小说）
            val effectiveChannel = channel ?: if (query.isEmpty()) DEFAULT_CHANNEL_CODE else null
            if (effectiveChannel != null) put("channel_code", effectiveChannel)
            if (status != "all") put("status_bucket", status)
        }
    }

    internal fun parseUnifiedSearchResult(response: JSONObject): List<Content> {
        val data = response.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<Content>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val bookId = item.optLong("book_id", 0L)
            if (bookId == 0L) continue
            // source_series_id 是旧版合集 sid，优先复用使浏览/搜索得到同一实体
            val seriesId = item.optInt("source_series_id", 0)
            val url = if (seriesId > 0) "/series/$seriesId" else "/book/$bookId"
            val tags = item.optJSONArray("visible_tags")?.let { arrTags ->
                (0 until arrTags.length()).mapNotNull { idx ->
                    arrTags.optString(idx).trim().takeIf { it.isNotEmpty() }?.let { ContentTag(it, TAG_KEY_PREFIX + it, source) }
                }.toSet()
            }.orEmpty()
            val author = item.optString("author_name").trim()
            val ratingScore10 = item.optInt("rating_score_10", 0)
            result += Content(
                id = generateUid(url),
                title = item.optString("title"),
                altTitles = emptySet(),
                url = url,
                publicUrl = "https://$domain$url",
                rating = if (ratingScore10 > 0) ratingScore10 / 10f else RATING_UNKNOWN,
                contentRating = null,
                coverUrl = item.optString("cover_url").nullIfEmpty(),
                largeCoverUrl = null,
                state = null,
                tags = tags,
                authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
                source = source,
            )
        }
        return result
    }

    private suspend fun explore(page: Int): List<Content> {
        // 这里的探索接口参数参考了原源的 exploreUrl
        val url = "https://$domain/proxy/api/category/get-article-by-cate"
        val data = JSONObject().apply {
            put("parent_gid", 3)
            put("gid", "106") // 最新
            put("page", page.toString())
        }
        val body = JSONObject().apply {
            put("is_encrypted", 0)
            put("platform", "pc")
            put("client", "web")
            put("sign", "")
            put("gz", 0)
            put("d", data as Any)
        }
        val response = postJson(url, body).parseJson()
        return parseContentList(response)
    }

    internal fun parseContentList(response: JSONObject): List<Content> {
        val list = mutableListOf<Content>()
        val dataObj = response.optJSONObject("data") ?: return list
        
        // collections (合集/系列)
        dataObj.optJSONArray("collections")?.let { arr ->
            for (i in 0 until arr.length()) {
                parseContentItem(arr.getJSONObject(i), isSeries = true)?.let(list::add)
            }
        }

        // articles (单篇)
        dataObj.optJSONArray("articles")?.let { arr ->
            for (i in 0 until arr.length()) {
                parseContentItem(arr.getJSONObject(i), isSeries = false)?.let(list::add)
            }
        }

        // list (探索返回的列表)
        dataObj.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val sid = item.optInt("sid", 0)
                parseContentItem(item, isSeries = sid != 0)?.let(list::add)
            }
        }
        
        return list
    }

    private fun parseContentItem(item: JSONObject, isSeries: Boolean): Content? {
        // id 缺失（含占位 "0"）时退回另一套 id；仍缺失则丢弃该条，避免退化 URL 造成实体互并
        val idVal = sequenceOf(
            if (isSeries) item.optString("sid") else item.optString("aid"),
            if (isSeries) item.optString("aid") else item.optString("sid"),
        ).firstOrNull { it.isNotBlank() && it != "0" } ?: return null
        val url = if (isSeries) "/series/$idVal" else "/article/$idVal"
        val seriesName = item.optString("series_name")
        // 站方未挂集的合集统一占位为“未知合集”，退回文章标题以区分不同作品
        val title = when {
            isSeries && seriesName.isNotBlank() && seriesName != PLACEHOLDER_SERIES_NAME -> seriesName
            else -> item.optString("title").ifBlank {
                item.optString("name").ifBlank { seriesName.ifBlank { PLACEHOLDER_SERIES_NAME } }
            }
        }
        val coverRaw = item.optString("cover")
        val banner = item.optString("banner")
        val cover = listOf(coverRaw, banner)
            .firstOrNull {
                it.isNotBlank() && !it.contains("default_article_cover", ignoreCase = true)
            }
        val author = item.optString("author")
        val groupName = item.optString("group_name")
        val upload = dateFormat.parseSafe(item.optString("last_time"))
        
        return Content(
            id = generateUid(url),
            title = title,
            altTitles = emptySet(),
            url = url,
            publicUrl = "https://$domain$url",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover?.takeIf { it.isNotBlank() },
            tags = buildSet {
                if (groupName.isNotBlank()) add(ContentTag(groupName, "group:$groupName", source))
            },
            state = null,
            authors = if (author.isNotBlank()) setOf(author) else emptySet(),
            source = source
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val novelId = manga.url.substringAfterLast("/")
        return when {
            manga.url.startsWith("/series/") -> getSeriesDetails(manga, novelId)
            // v1 独有作品（无旧版 sid）：详情/章节接口未免签开放，仅展示已有元数据
            manga.url.startsWith("/book/") -> manga.copy(chapters = emptyList())
            else -> getArticleDetails(manga, novelId)
        }
    }

    private suspend fun getSeriesDetails(manga: Content, sid: String): Content {
        val url = "https://api.lightnovel.fun/api/series/get-info"
        val data = JSONObject().apply {
            put("sid", sid.toInt())
            put("security_key", JSONObject.NULL)
        }
        val response = postJson(url, createBaseBody(data)).parseJson()
        // 站点新协议要求对 /api/article/ 请求签名，但 /api/series/ 仍开放；
        // 失败或结构变化时降级为仅返回已有元数据，避免崩溃。
        val dataObj = response.optJSONObject("data") ?: return manga.copy(chapters = emptyList())

        val articles = dataObj.optJSONArray("articles")
        val chapters = if (articles != null) {
            val list = mutableListOf<ContentChapter>()
            for (i in 0 until articles.length()) {
                val art = articles.optJSONObject(i) ?: continue
                val aid = art.optString("aid")
                if (aid.isBlank()) continue
                val order = art.optInt("order")
                val title = art.optString("title").ifBlank { "Ch ${i + 1}" }
                list.add(ContentChapter(
                    id = generateUid("/article/$aid"),
                    title = "P${order.toString().padStart(2, '0')} $title",
                    number = i + 1f,
                    volume = 0,
                    url = "/article/$aid",
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                ))
            }
            list
        } else emptyList()

        return manga.copy(
            description = dataObj.optString("intro").ifBlank { manga.description },
            chapters = chapters
        )
    }

    private suspend fun getArticleDetails(manga: Content, aid: String): Content {
        // 站点 2026 改版后 /api/article/get-detail 需要 HMAC 签名（前端签名密钥不下发），
        // 未签名的请求返回 code 5001 且正文缺失；这里降级为「无章节」，不伪造章节也不崩溃。
        val url = "https://api.lightnovel.fun/api/article/get-detail"
        val data = JSONObject().apply {
            put("aid", aid.toInt())
            put("simple", 0)
        }
        val response = postJson(url, createBaseBody(data)).parseJson()
        val dataObj = response.optJSONObject("data") ?: return manga.copy(chapters = emptyList())
        return manga.copy(
            description = dataObj.optString("intro").ifBlank { manga.description },
            chapters = emptyList(),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val content = getChapterContent(chapter) ?: return emptyList()
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = content.html.toDataUrl(),
                preview = null,
                source = source
            )
        )
    }

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        val aid = chapter.url.substringAfterLast("/")
        val url = "https://api.lightnovel.fun/api/article/get-detail"
        val data = JSONObject().apply {
            put("aid", aid.toInt())
            put("simple", 0)
        }
        val response = postJson(url, createBaseBody(data)).parseJson()
        // 内容接口需要签名；失败或结构变化时返回 null，getPages 会得到空列表而非崩溃。
        val dataObj = response.optJSONObject("data") ?: return null
        val content = dataObj.optString("content")
        val resInfo = dataObj.optJSONObject("res_info")
            ?: dataObj.optJSONObject("res")?.optJSONObject("res_info")
        val images = mutableListOf<NovelChapterContent.NovelImage>()
        resInfo?.let { info ->
            info.keys().forEach { key ->
                val obj = info.optJSONObject(key) ?: return@forEach
                val urlVal = obj.optString("url").orEmpty()
                if (urlVal.isNotBlank()) {
                    images.add(
                        NovelChapterContent.NovelImage(
                            url = urlVal,
                            headers = mapOf("Referer" to "https://$domain/")
                        )
                    )
                }
            }
        }

        val html = buildChapterHtml(content, chapter.title ?: "", resInfo)
        return NovelChapterContent(html = html, images = images)
    }

    private fun buildChapterHtml(content: String, title: String, resInfo: JSONObject? = null): String {
        var processed = content
            // 简单的标签清理
            .replace(Regex("\\[[a-z]+=[^\\]]+\\]"), "")
            .replace(Regex("\\[\\/(?!res|img)[a-z]+\\]"), "")
            .replace("[b]", "")
        
        // 解析 [res]id[/res] => 使用 res_info 中的 url
        processed = processed.replace(Regex("\\[res\\](.*?)\\[\\/res\\]")) { match ->
            val key = match.groupValues[1].trim()
            val info = resInfo?.optJSONObject(key)
            val url = info?.optString("url").orEmpty()
            if (url.isNotBlank()) {
                "<p><img src=\"$url\" referrerpolicy=\"no-referrer\" loading=\"lazy\"></p>"
            } else {
                // 若缺少 res_info，保留占位，避免图片被误移除
                "<p><img data-res=\"$key\" alt=\"$key\" referrerpolicy=\"no-referrer\" loading=\"lazy\"></p>"
            }
        }
        
        // 处理图片
        processed = processed.replace(Regex("\\[img\\](.*?)\\[\\/img\\]")) { match ->
            "<p><img src=\"${match.groupValues[1]}\" referrerpolicy=\"no-referrer\"></p>"
        }
        // 兜底修正 img：兼容 data-src、// 开头等形式，确保可直接加载
        processed = processed
            .replace("data-src=\"", "src=\"")
            .replace("src=\"//", "src=\"https://")
            .replace("src=\"http://", "src=\"https://")
            .replace(Regex("<img([^>]*?)src=\"([^\"]+)\"([^>]*)>")) { m ->
                val attrs = "${m.groupValues[1]}${m.groupValues[3]}"
                val src = m.groupValues[2]
                "<img src=\"$src\" $attrs referrerpolicy=\"no-referrer\" loading=\"lazy\">"
            }

        val normalized = processed
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("\n{3,}"), "\n\n") // 压缩过多的空行
            .trim()
        val headingRegex = Regex("^(序幕|终幕|序章|终章|尾声|目录|Prologue|Epilogue|第?[0-9〇一二三四五六七八九十百千零]+(话|章|幕|卷|节|部分|篇))")
        val paragraphs = normalized.split(Regex("\n{2,}")).filter { it.isNotBlank() }

        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<style>")
            append("body{font-family:sans-serif;padding:20px;line-height:1.8;font-size:1.1rem;background:#fff;color:#000;}")
            append("img{max-width:100%;height:auto;display:block;margin:10px auto;}")
            append("p{margin-bottom:1.2rem;}")
            append("h1{font-size:1.4rem;border-bottom:1px solid #eee;padding-bottom:10px;margin-bottom:20px;}")
            append("h2{font-size:1.2rem;margin:1.2rem 0 0.6rem;}")
            append("</style></head>")
            append("<body>")
            if (title.isNotBlank()) append("<h1>$title</h1>")
            if (paragraphs.isNotEmpty()) {
                for (para in paragraphs) {
                    val text = para.trim()
                    if (text.isEmpty()) continue
                    val htmlPara = text.split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("<br/>")
                    if (htmlPara.isEmpty()) continue
                    if (headingRegex.containsMatchIn(text.lines().firstOrNull().orEmpty())) {
                        append("<h2>").append(htmlPara).append("</h2>")
                    } else {
                        append("<p>").append(htmlPara).append("</p>")
                    }
                }
            } else {
                // 回退：无空行分段时按行包裹
                normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    append("<p>").append(line).append("</p>")
                }
            }
            append("</body></html>")
        }
    }

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(StandardCharsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private suspend fun postJson(url: String, body: JSONObject) =
        webClient.httpPost(
            url.toHttpUrl(),
            body,
            Headers.Builder()
                .add("User-Agent", config[userAgentKey] ?: UserAgents.CHROME_DESKTOP)
                .add("Referer", "https://$domain/")
                .add("Origin", "https://$domain")
                .add("Content-Type", "application/json")
                .add("Accept", "application/json")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Accept-Encoding", "identity")
                .build()
        )

    private companion object {
        const val TAG_KEY_PREFIX = "tag:"
        const val CHANNEL_KEY_PREFIX = "channel:"
        const val CHANNEL_GROUP_TITLE = "频道"
        const val TAG_GROUP_FALLBACK_TITLE = "其他"
        const val DEFAULT_CHANNEL_CODE = "lightnovel"
        const val PLACEHOLDER_SERIES_NAME = "未知合集"

        // taxonomy channels 实测：lightnovel/original/fanfic/epub
        val CHANNEL_CODES = setOf("lightnovel", "original", "fanfic", "epub")

        // display_group 实测：hot/theme/role/plot
        val TAG_GROUP_TITLES = mapOf(
            "hot" to "热门题材",
            "theme" to "主题",
            "role" to "角色",
            "plot" to "情节",
        )
    }
}
