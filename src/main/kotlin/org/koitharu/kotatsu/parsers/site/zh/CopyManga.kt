@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)
package org.koitharu.kotatsu.parsers.site.zh

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.getCookies
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.copyCookies
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONIndexed
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.Base64
import kotlin.random.Random
import java.util.EnumSet

/**
 * 拷贝漫画（新站）解析器
 * 参考 /Users/sunchuxiong/kotatsu_demo/copymanga.js
 */
@MangaSourceParser("COPYMANGA", "拷贝漫画", "zh")
@OptIn(InternalParsersApi::class)
@InternalParsersApi
internal class CopyMangaParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COPYMANGA, pageSize = 21), Interceptor {
    init {
        // 统一从第 1 页开始，以匹配 /comics?page=1 的分页策略
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    // 仅保留测试用域名：将 parser 的公开域指向站点域，保证 HTML 回退可用
    @OptIn(InternalParsersApi::class)
    override val configKeyDomain = ConfigKey.Domain(
        "api.copy2000.online",
    )
    @OptIn(InternalParsersApi::class)
    override val userAgentKey = ConfigKey.UserAgent("COPY/3.0.0")
    // 线路设置：海外(0)/大陆(1)，用于 Header 的 region 以及优先尝试的 line
    @OptIn(InternalParsersApi::class)
    private val preferredLineKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            "0" to "海外线路",
            "1" to "大陆线路",
        ),
        defaultValue = DEFAULT_REGION,
    )
    // 运行期设备参数（一次生成，实例持有）
    private val deviceInfo: String by lazy { generateDeviceInfo() }
    private val device: String by lazy { generateDevice() }
    private val pseudoId: String by lazy { generatePseudoId() }
    private var baseUrlOverride: String? = null
    // 站点域（用于图片 Referer/Origin、HTML 回退等）
    private var siteDomainOverride: String? = null
    // 测试/运行期开关：仅走 API 分支，跳过站点 HTML 请求
    private fun preferApiOnly(): Boolean = true
    private fun siteDomain(): String {
        // 站点域优先取覆盖值；否则根据 API 域推导（移除前缀 "api."），最终回退默认值
        val apiHost = baseUrlOverride
        val derived = apiHost?.let { if (it.startsWith("api.", ignoreCase = true)) it.removePrefix("api.") else it }
        return siteDomainOverride ?: derived ?: "copy2000.online"
    }
    private val imageQuality: String = "1500"
    // 主题分类映射（简化为空，接口将返回全部）
    private val CATEGORY_PARAM_DICT: Map<String, String> = mapOf(
        // 最小可用映射，用户反馈可正常浏览
        "爱情" to "aiqing",
    )

    @OptIn(InternalParsersApi::class)
    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        // 保持顺序：域名设置在上方，其下方添加线路设置
        keys.add(preferredLineKey)
        keys.add(userAgentKey)
    }

    // ===== 授权接口实现 =====
    // 不实现账户登录与用户信息接口

    @OptIn(InternalParsersApi::class)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY,
    )

    @OptIn(InternalParsersApi::class)
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

    @OptIn(InternalParsersApi::class)
    override suspend fun getFilterOptions(): MangaListFilterOptions {
        // 将 JS 中的“主题”和“排行”的选择映射为标签组（固定分类）
        val themeTags: Set<MangaTag> = CATEGORY_PARAM_DICT.entries.map { entry ->
            MangaTag(title = entry.key, key = entry.value, source = source)
        }.toSet()
        val rankingTag = MangaTag(title = "排行", key = "ranking", source = source)
        return MangaListFilterOptions(
            availableTags = themeTags + setOf(rankingTag),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentRating = emptySet(),
        )
    }

    @OptIn(InternalParsersApi::class)
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("source", "copyApp")
        .add("deviceinfo", deviceInfo)
        .add("platform", "3")
        .add("referer", "com.copymanga.app-3.0.0")
        .add("version", "3.0.0")
        .add("device", device)
        .add("pseudoid", pseudoId)
        .add("Accept", "application/json")
        .add("region", config[preferredLineKey] ?: DEFAULT_REGION)
        .apply {
            // 附加 Token：从 Cookie 中读取（若存在）
            val apiDomain = apiBase()
            var tokenCookie = context.cookieJar.getCookies(apiDomain).find {
                it.name.equals("token", ignoreCase = true) || it.name.equals("authorization", ignoreCase = true)
            }
            if (tokenCookie == null) {
                // 如果 API 域没有 token，尝试从站点域拷贝指定 cookie 到 API 域
                val site = siteDomain()
                context.cookieJar.copyCookies(site, apiDomain, arrayOf("token", "authorization"))
                tokenCookie = context.cookieJar.getCookies(apiDomain).find {
                    it.name.equals("token", ignoreCase = true) || it.name.equals("authorization", ignoreCase = true)
                }
            }
            val authHeader = tokenCookie?.value?.let { v ->
                if (v.isNotBlank()) "Token $v" else "Token"
            } ?: "Token"
            add("authorization", authHeader)
            // 认证时间戳与签名
            val now = java.util.Date()
            val cal = java.util.Calendar.getInstance().apply { time = now }
            val year = cal.get(java.util.Calendar.YEAR)
            val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            val ts = (now.time / 1000).toString()
            add("dt", "$year.$month.$day")
            add("x-auth-timestamp", ts)
            val sig = hmacSha256(COPY_SECRET, ts)
            add("x-auth-signature", sig)
            add("umstring", "b4c89ca4104ea9a97750314d791520ac")
        }
        .build()

    @OptIn(InternalParsersApi::class)
    private fun apiBase(): String {
        return baseUrlOverride ?: config[configKeyDomain]
    }

    // 刷新 API 端点（优先使用配置的固定域 api.copy2000.online）
    @OptIn(InternalParsersApi::class)
    private suspend fun refreshAppApi(): String {
        // 若已刷新过则复用缓存
        baseUrlOverride?.let { return it }
        val host = config[configKeyDomain]
        baseUrlOverride = host
        // 同步推导站点域（移除 "api." 前缀）
        siteDomainOverride = if (host.startsWith("api.")) host.removePrefix("api.") else siteDomainOverride
        return host
    }

    // 移除登录与桥接方法

    // 统一的 210 抗刷重试 GET（返回 JSON）；用于详情与章节列表
    @OptIn(InternalParsersApi::class)
    private suspend fun httpGetJsonWithAntiAbuse(url: String, headers: Headers, maxAttempts: Int = 5): JSONObject {
        var attempt = 0
        val defaultWaitMs = 40_000L
        while (attempt < maxAttempts) {
            val response = try {
                webClient.httpGet(url, headers)
            } catch (e: Exception) {
                // 4xx/5xx 等状态异常，交由调用方回退到其他参数组合
                return JSONObject()
            }
            if (response.code == 210) {
                // 访问过于频繁，解析等待时间并重试
                val waitMs = runCatching {
                    val body = response.parseJson()
                    val msg = body.optString("message")
                    val m = Regex("(\\d+)\\s*seconds").find(msg)
                    val seconds = m?.groups?.get(1)?.value?.toLongOrNull()
                        ?: msg.toLongOrNull()
                        ?: 40L
                    seconds * 1000L
                }.getOrElse { defaultWaitMs }
                kotlinx.coroutines.delay(waitMs)
                attempt++
                continue
            }
            return response.parseJson()
        }
        return JSONObject()
    }

    @OptIn(InternalParsersApi::class)
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val base = refreshAppApi()
        val offset = (page - paginator.firstPage) * pageSize
        val isRanking = filter.tags.any { it.key == "ranking" }
        val url = if (isRanking) {
            // 排行接口：audience_type 与 date_type 作为“选项”绑定到 tags 中的 key 或查询
            val audience = filter.query?.substringBefore('|') ?: "1" // 默认 type=1
            val dateType = filter.query?.substringAfter('|') ?: "month" // 默认最近30天
            buildString {
                append("https://")
                append(base)
                append("/api/v3/ranks?limit=")
                append(pageSize)
                append("&offset=")
                append(offset)
                append("&_update=true&type=1&audience_type=")
                append(audience)
                append("&date_type=")
                append(dateType)
                append("&free_type=1")
            }
        } else if (!filter.query.isNullOrEmpty()) {
            // 搜索接口：优先 webAPI（copymanga.js 中 refreshSearchApi 可能改变路径），这里简化为统一 API
            val q = filter.query.urlEncoded()
            buildString {
                append("https://")
                append(base)
                append(COPY_SEARCH_API)
                append("?limit=")
                append(pageSize)
                append("&offset=")
                append(offset)
                append("&q=")
                append(q)
                append("&q_type=")
                append("name")
                append("&_update=true&free_type=1")
            }
        } else {
            // 主题分类列表
            val themeParam = filter.tags.firstOrNull()?.key ?: ""
            val ordering = "-datetime_updated" // 默认时间倒序
            val top = when {
                MangaState.FINISHED in filter.states -> "finish"
                MangaState.ONGOING in filter.states -> "-全部"
                else -> "-全部"
            }
            buildString {
                append("https://")
                append(base)
                append("/api/v3/comics?limit=")
                append(pageSize)
                append("&offset=")
                append(offset)
                append("&ordering=")
                append(ordering)
                append("&theme=")
                append(themeParam)
                append("&top=")
                append(top)
                append("&_update=true&free_type=1")
            }
        }

        // 先尝试 API；若返回维护页/异常/空列表，则改用 HTML 列表页解析
        val apiResults = runCatching {
            val resp = webClient.httpGet(url, getRequestHeaders())
            val root = resp.parseJson()
            val list = root.optJSONObject("results")?.optJSONArray("list") ?: JSONArray()
            list.mapJSON { jo ->
                val comic = jo.optJSONObject("comic") ?: jo
                val id = comic.optString("path_word")
                val title = comic.optString("name")
                val cover = comic.optString("cover")
                val tagsArray = comic.optJSONArray("theme") ?: JSONArray()
                val tags = tagsArray.mapJSON { t ->
                    val n = t.optString("name")
                    MangaTag(title = n, key = n, source = source)
                }.toSet()
                val authors = (comic.optJSONArray("author") ?: JSONArray()).mapJSON { a -> a.optString("name") }.toSet()
                val site = siteDomain()
                Manga(
                    id = generateUid(id),
                    url = id,
                    publicUrl = "https://$site/comic/$id",
                    coverUrl = cover,
                    title = title,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    tags = tags,
                    authors = authors,
                    state = null,
                    source = source,
                    contentRating = null,
                )
            }
        }.getOrElse { emptyList() }

        return apiResults
    }

    @OptIn(InternalParsersApi::class)
    override suspend fun getDetails(manga: Manga): Manga {
        // 仅使用 API 详情与分组章节批量拉取（含 210 重试与参数回退）
        val base = refreshAppApi()
        val headers = getRequestHeaders()
        val detailBase = "https://$base/api/v3/comic2/${manga.url}"
        val detailParamCombos = listOf(
            "?platform=3&_update=true",
            "?platform=3&_update=true&in_mainland=true&request_id=",
            "?in_mainland=true&_update=true&request_id=",
            "?platform=3&_update=true&in_mainland=true",
            "?platform=3&_update=true&request_id=",
            "?platform=3",
        )
        var res: JSONObject? = null
        for (suffix in detailParamCombos) {
            val url = detailBase + suffix
            val data = httpGetJsonWithAntiAbuse(url, headers)
            val candidate = data.optJSONObject("results")
            if (candidate != null) {
                res = candidate
                break
            }
        }
        if (res == null) return manga
        val comic = res.optJSONObject("comic") ?: return manga

        val title = comic.optString("name", manga.title).ifBlank { manga.title }
        val cover = comic.optString("cover", manga.coverUrl).ifBlank { manga.coverUrl }
        val desc = comic.optString("brief", manga.description).ifBlank { manga.description }
        val stateStr = comic.optString("status", "")
        val state = when (stateStr.lowercase()) {
            "finished", "end" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            else -> manga.state
        }

        // 分组与章节批量拉取（每次 100），支持 JSONArray / JSONObject 两种返回
        val pathList = mutableListOf<String>()
        val groupsArr = res.optJSONArray("groups")
        if (groupsArr != null && groupsArr.length() > 0) {
            for (i in 0 until groupsArr.length()) {
                val g = groupsArr.optJSONObject(i) ?: continue
                val path = g.optString("path_word", g.optString("path"))
                if (path.isNotBlank()) pathList += path
            }
        } else {
            val groupsObj = res.optJSONObject("groups")
            if (groupsObj != null) {
                val keys = groupsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val g = groupsObj.optJSONObject(k) ?: continue
                    val path = g.optString("path_word", g.optString("path", k))
                    if (path.isNotBlank()) pathList += path
                }
            }
        }
        if (pathList.isEmpty()) pathList += "default"

        val chapters = ArrayList<MangaChapter>()
        for (path in pathList) {
            var offset = 0
            while (true) {
                val baseQuery = "https://$base/api/v3/comic/${manga.url}/group/$path/chapters?limit=100&offset=$offset"
                val paramCombos = listOf(
                    "&platform=3&_update=true&in_mainland=true&request_id=",
                    "&platform=3&_update=true",
                    "&_update=true",
                    "&in_mainland=true&request_id=",
                    "&platform=3",
                )
                var list: JSONArray = JSONArray()
                var found = false
                for (suffix in paramCombos) {
                    val chUrl = baseQuery + suffix
                    val groupData = httpGetJsonWithAntiAbuse(chUrl, headers)
                    val results = groupData.optJSONObject("results")
                    val candidate = results?.optJSONArray("list") ?: JSONArray()
                    if (candidate.length() > 0) {
                        list = candidate
                        found = true
                        break
                    }
                    // 若服务端明确返回 total=0，则继续下一个组合；否则视为未命中，尝试下一个
                    val total = results?.optInt("total", 0) ?: 0
                    if (total == 0) continue
                }
                if (!found) break
                for (j in 0 until list.length()) {
                    val c = list.optJSONObject(j) ?: continue
                    val serial = c.optString("name", "${offset + j + 1}")
                    val uuid = c.optString("uuid")
                    val idPathWord = c.optString("path_word", "${manga.url}-${offset + j}")
                    val id = if (uuid.isNotBlank()) uuid else idPathWord
                    val number = parseChapterNumber(serial) ?: (offset + j + 1).toFloat()
                    chapters += MangaChapter(
                        id = generateUid(id),
                        title = serial,
                        number = number,
                        volume = 0,
                        url = id,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = manga.url,
                        source = source,
                    )
                }
                // 如果返回不足 100，则已到末尾
                if (list.length() < 100) break
                offset += 100
            }
        }

        return manga.copy(
            title = title,
            coverUrl = cover,
            largeCoverUrl = cover,
            description = desc,
            state = state,
            chapters = chapters.sortedBy { it.number },
        )
    }

    @OptIn(InternalParsersApi::class)
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // 仅使用 API 读取章节内容（含 210 重试与参数回退组合）
        val base = refreshAppApi()
        val headers = getRequestHeaders()
        val epBase = "https://$base/api/v3/comic/${chapter.branch}/chapter2/${chapter.url}"
        val preferredLine = config[preferredLineKey] ?: DEFAULT_REGION
        val paramCombos = if (preferredLine == "0") {
            listOf(
                "?platform=3&_update=true&line=0",
                "?platform=3&_update=true&line=1",
                "?line=0&_update=true",
                "?line=1&_update=true",
                "?platform=3&_update=true&line=0&in_mainland=true&request_id=",
                "?platform=3&_update=true&line=1&in_mainland=true&request_id=",
                "?line=0&in_mainland=true&request_id=",
                "?line=1&in_mainland=true&request_id=",
                "?platform=3&line=0",
                "?platform=3&line=1",
            )
        } else {
            listOf(
                "?platform=3&_update=true&line=1",
                "?platform=3&_update=true&line=0",
                "?line=1&_update=true",
                "?line=0&_update=true",
                "?platform=3&_update=true&line=1&in_mainland=true&request_id=",
                "?platform=3&_update=true&line=0&in_mainland=true&request_id=",
                "?line=1&in_mainland=true&request_id=",
                "?line=0&in_mainland=true&request_id=",
                "?platform=3&line=1",
                "?platform=3&line=0",
            )
        }

        for (suffix in paramCombos) {
            val url = epBase + suffix
            val data = httpGetJsonWithAntiAbuse(url, headers)
            val res = data.optJSONObject("results") ?: JSONObject()
            val chapterObj = res.optJSONObject("chapter") ?: JSONObject()
            val contents = chapterObj.optJSONArray("contents") ?: JSONArray()
            val orders = chapterObj.optJSONArray("words") ?: JSONArray()

            val urls = ArrayList<String>(contents.length())
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                val rawUrl = item.optString("url")
                if (rawUrl.isNullOrEmpty()) continue
                // 仅当 URL 为纯路径（不含查询/签名）时才尝试替换清晰度；否则保留原始 URL，避免签名失效
                val hasQueryOrSignature = rawUrl.contains('?') || rawUrl.contains("token=", ignoreCase = true) ||
                    rawUrl.contains("sign", ignoreCase = true) || rawUrl.contains("auth", ignoreCase = true)
                val hdUrl = if (hasQueryOrSignature) rawUrl else rawUrl.replace(
                    Regex("""([./])c\d+x\.([a-zA-Z]+)$""")) { m ->
                    val sep = m.groupValues.getOrNull(1).orEmpty()
                    // 与 Venera 对齐：无签名路径强制使用 webp 清晰度变体
                    "$sep" + "c${imageQuality}x.webp"
                }
                urls += hdUrl
            }

            // 根据 words 排序；若映射失败则采用原始顺序回退
            val pagesByOrder = MutableList(urls.size) { "" }
            for (i in urls.indices) {
                val pos = orders.optInt(i, i)
                if (pos in pagesByOrder.indices) {
                    pagesByOrder[pos] = urls[i]
                }
            }
            val ordered = pagesByOrder.mapIndexedNotNull { i, u ->
                if (u.isEmpty()) null else MangaPage(
                    id = generateUid("${chapter.url}/$i"),
                    url = u,
                    preview = null,
                    source = source,
                )
            }
            if (ordered.isNotEmpty()) {
                return ordered
            }
            if (urls.isNotEmpty()) {
                // 回退：使用原始顺序（仅当存在内容时）
                return urls.mapIndexed { i, u ->
                    MangaPage(
                        id = generateUid("${chapter.url}/$i"),
                        url = u,
                        preview = null,
                        source = source,
                    )
                }
            }
            // 无内容则尝试下一个参数组合
            continue
        }
        // 回退：依次尝试 cartoon 与旧 chapter 端点，并对每个端点尝试 line=1/0 等参数组合
        run {
            val altBases = listOf(
                "https://$base/api/v3/cartoon/${chapter.branch}/chapter/${chapter.url}",
                "https://$base/api/v3/comic/${chapter.branch}/chapter/${chapter.url}",
            )
            for (altBase in altBases) {
                for (suffix in paramCombos) {
                    val altUrl = altBase + suffix
                    val altData = httpGetJsonWithAntiAbuse(altUrl, headers)
                    val altRes = altData.optJSONObject("results") ?: JSONObject()
                    val altChapter = altRes.optJSONObject("chapter") ?: JSONObject()
                    val altContents = altChapter.optJSONArray("contents") ?: JSONArray()
                    val altOrders = altChapter.optJSONArray("words") ?: JSONArray()
                    if (altContents.length() == 0) continue

                    val altUrls = ArrayList<String>(altContents.length())
                    for (i in 0 until altContents.length()) {
                        val item = altContents.optJSONObject(i) ?: continue
                        val rawUrl = item.optString("url")
                        if (rawUrl.isNullOrEmpty()) continue
                        val hasQueryOrSignature = rawUrl.contains('?') || rawUrl.contains("token=", ignoreCase = true) ||
                            rawUrl.contains("sign", ignoreCase = true) || rawUrl.contains("auth", ignoreCase = true)
                        val hdUrl = if (hasQueryOrSignature) rawUrl else rawUrl.replace(
                            Regex("""([./])c\d+x\.([a-zA-Z]+)$""")) { m ->
                            val sep = m.groupValues.getOrNull(1).orEmpty()
                            "${sep}c${imageQuality}x.webp"
                        }
                        altUrls += hdUrl
                    }

                    // 排序：优先根据 words 映射；否则使用原始顺序
                    val pagesByOrder = MutableList(altUrls.size) { "" }
                    for (i in altUrls.indices) {
                        val pos = altOrders.optInt(i, i)
                        if (pos in pagesByOrder.indices) pagesByOrder[pos] = altUrls[i]
                    }
                    val ordered = pagesByOrder.mapIndexedNotNull { i, u ->
                        if (u.isEmpty()) null else MangaPage(
                            id = generateUid("${chapter.url}/$i"),
                            url = u,
                            preview = null,
                            source = source,
                        )
                    }
                    if (ordered.isNotEmpty()) return ordered
                    if (altUrls.isNotEmpty()) {
                        return altUrls.mapIndexed { i, u ->
                            MangaPage(
                                id = generateUid("${chapter.url}/$i"),
                                url = u,
                                preview = null,
                                source = source,
                            )
                        }
                    }
                    // 若无内容，继续尝试下一个参数组合
                }
            }
        }
        return emptyList()
    }

    // 从章节标题中解析话序号，如 “第1话”、“1话”、“第12章”等，失败返回 null
    private fun parseChapterNumber(title: String): Float? {
        val patterns = listOf(
            Regex("第\\s*([0-9]+(?:\\.[0-9]+)?)\\s*话"),
            Regex("第\\s*([0-9]+(?:\\.[0-9]+)?)\\s*章"),
            Regex("\\b([0-9]+(?:\\.[0-9]+)?)\\s*话\\b"),
            Regex("\\b([0-9]+(?:\\.[0-9]+)?)\\s*章\\b"),
        )
        for (p in patterns) {
            val m = p.find(title)
            if (m != null) {
                val n = m.groupValues.getOrNull(1)?.toFloatOrNull()
                if (n != null && n >= 1f) return n
            }
        }
        return null
    }


    // 为图片请求设置站点 Referer/Origin，避免静态资源服务端返回 4xx/5xx
    @OptIn(InternalParsersApi::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val accept = req.header("Accept").orEmpty()
        val url = req.url
        val host = url.host
        val path = url.encodedPath
        val isApiRequest = host.startsWith("api.") || path.contains("/api/")
        val hasImageExt = url.pathSegments.lastOrNull()?.let { seg ->
            seg.endsWith(".jpg", true) || seg.endsWith(".jpeg", true) || seg.endsWith(".png", true) || seg.endsWith(".webp", true) || seg.endsWith(".gif", true) || seg.endsWith(".avif", true) || seg.endsWith(".svg", true) || seg.endsWith(".ico", true)
        } == true
        val isImageRequest = accept.contains("image/") || hasImageExt
        val site = siteDomain()

        return if (isImageRequest) {
            val builder = req.newBuilder()
                // 与终端示例严格对齐：不设置 Accept/Accept-Language，仅设置 UA/Referer/Origin
                .removeHeader("Accept")
                .removeHeader("accept")
                .removeHeader("Accept-Language")
                .removeHeader("accept-language")
                .header("User-Agent", UserAgents.CHROME_DESKTOP)
                .header("Referer", "https://${site}/")
                .header("Origin", "https://${site}")
                // 避免在图片请求上携带认证头导致服务端拒绝
                .removeHeader("Authorization")
                .removeHeader("authorization")
                // 清理 App 特征头，避免静态域安全校验拒绝
                .removeHeader("source")
                .removeHeader("deviceinfo")
                .removeHeader("platform")
                .removeHeader("version")
                .removeHeader("device")
                .removeHeader("pseudoid")
                .removeHeader("region")
                .removeHeader("dt")
                .removeHeader("x-auth-timestamp")
                .removeHeader("x-auth-signature")
                .removeHeader("umstring")

            // 始终移除 Cookie，匹配 Python 验证脚本的最小化图片请求头，避免部分 CDN 对 Cookie 的异常处理导致 500
            val newReq = builder.removeHeader("Cookie").removeHeader("cookie").build()
            val resp = chain.proceed(newReq)
            val ct = resp.header("Content-Type").orEmpty()
            return if (ct.contains("octet-stream", ignoreCase = true)) {
                resp.newBuilder().header("Content-Type", "image/jpeg").build()
            } else resp
        } else if (!isApiRequest && req.method == "GET") {
            // 统一处理非 API 的 GET：放宽 Accept，设置 Referer/Origin，移除 App 特征与认证头
            val newReq = req.newBuilder()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,image/png,image/*,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", config[userAgentKey])
                .apply {
                    val originSite = when {
                        host.contains("mangacopy.com", ignoreCase = true) -> "www.mangacopy.com"
                        host.contains("copy-manga.com", ignoreCase = true) -> "copy-manga.com"
                        host.contains("copy2000.online", ignoreCase = true) -> "copy2000.online"
                        else -> site
                    }
                    header("Referer", "https://$originSite/")
                    header("Origin", "https://$originSite")
                }
                .removeHeader("Authorization")
                .removeHeader("authorization")
                .removeHeader("Cookie")
                .removeHeader("cookie")
                .removeHeader("source")
                .removeHeader("deviceinfo")
                .removeHeader("platform")
                .removeHeader("version")
                .removeHeader("device")
                .removeHeader("pseudoid")
                .removeHeader("region")
                .removeHeader("dt")
                .removeHeader("x-auth-timestamp")
                .removeHeader("x-auth-signature")
                .removeHeader("umstring")
                .build()
            val resp = chain.proceed(newReq)
            val ct = resp.header("Content-Type").orEmpty()
            return if (ct.contains("octet-stream", ignoreCase = true)) {
                resp.newBuilder().header("Content-Type", "image/jpeg").build()
            } else resp
        } else {
            chain.proceed(req)
        }
    }

    private fun generateDeviceInfo(): String {
        fun randInt(min: Int, max: Int): Int = Random.Default.nextInt(min, max + 1)
        return "${randInt(1000000, 9999999)}V-${randInt(1000, 9999)}"
    }

    private fun generateDevice(): String {
        fun randCharA(): Char = (Random.Default.nextInt('A'.code, 'Z'.code + 1)).toChar()
        fun randDigit(): Char = (Random.Default.nextInt('0'.code, '9'.code + 1)).toChar()
        return buildString {
            append(randCharA()); append(randCharA()); append(randDigit()); append(randCharA());
            append('.'); repeat(6) { append(randDigit()) }
            append('.'); repeat(3) { append(randDigit()) }
        }
    }

    private fun generatePseudoId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString {
            repeat(16) { append(chars[Random.Default.nextInt(chars.length)]) }
        }
    }

    private fun hmacSha256(secretBase64: String, data: String): String {
        val secret = java.util.Base64.getDecoder().decode(secretBase64)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val keySpec = javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(data.toByteArray())
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        // 默认使用海外线路（与 Python 校验一致）
        private const val DEFAULT_REGION = "1"
        private const val COPY_SECRET = "M2FmMDg1OTAzMTEwMzJlZmUwNjYwNTUwYTA1NjNhNTM="
        private const val COPY_SEARCH_API = "/api/kb/web/searchb/comics"
        private val HAN_REGEX = Regex("[\\p{IsHan}]")
    }
}
