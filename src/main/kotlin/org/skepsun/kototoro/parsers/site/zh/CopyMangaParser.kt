@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)
package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.FavoritesSyncProvider
import org.skepsun.kototoro.parsers.network.GZipOptions
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.json.mapJSONIndexed
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import java.util.Base64
import kotlin.random.Random
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

import org.skepsun.kototoro.parsers.model.ContentTagGroup

/** 拷贝漫画网页 API 解析器。 */
@ContentSourceParser("COPYMANGA", "拷贝漫画", "zh")
@OptIn(InternalParsersApi::class)
@InternalParsersApi
internal class CopyContentParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.COPYMANGA, pageSize = 30),
    Interceptor,
    ContentParserAuthProvider,
    ContentParserCredentialsAuthProvider,
    FavoritesProvider,
    FavoritesSyncProvider {
    init {
        // 统一从第 1 页开始，以匹配 /comics?page=1 的分页策略
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    @OptIn(InternalParsersApi::class)
    override val configKeyDomain = ConfigKey.Domain(
        "api.copy3000.com",
        "api.2026copy.com",
        "api.copy202601.com",
        "api.mangacopy.com",
        "mapi.copy20.com",
        "mapi.copy2000.site",
        "mapi.hotmangasd.com",
        "api.manga2025.com",
        "mapi.hotmangasf.com",
        "mapi.hotmangasg.com",
        "mapi.elfgjfghkk.club",
        "mapi.fgjfghkk.club",
        "mapi.fgjfghkkcenter.club",
    )
    @OptIn(InternalParsersApi::class)
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    @OptIn(InternalParsersApi::class)
    private val preferredLineKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            "0" to "海外线路",
            "1" to "大陆线路",
        ),
        defaultValue = DEFAULT_REGION,
    )
    private val nicknameRef = AtomicReference<String?>()
    private val authorPathWordDict = mutableMapOf<String, String>()

    private val apiRateLimitLock = Any()
    private var lastApiRequestTime = 0L

    override val faviconDomain: String
        get() = DEFAULT_SITE_DOMAIN

    private fun siteDomain(): String = when (apiBase()) {
        "api.copy3000.com" -> "www.copy3000.com"
        "api.2026copy.com" -> "www.2026copy.com"
        "api.copy202601.com" -> "www.copy202601.com"
        "mapi.copy20.com" -> "www.copy20.com"
        "api.mangacopy.com" -> "www.mangacopy.com"
        else -> DEFAULT_SITE_DOMAIN
    }
    private val imageQuality: String = "1500"
    // 主题分类映射（简化为空，接口将返回全部）
    private val CATEGORY_PARAM_DICT: Map<String, String> = mapOf(
        "全部" to "",
        // 最小可用映射，用户反馈可正常浏览
        "爱情" to "aiqing",
        "歡樂向" to "huanlexiang",
        "冒險" to "maoxian",
        "奇幻" to "qihuan",
        "百合" to "baihe",
        "校园" to "xiaoyuan",
        "科幻" to "kehuan",
        "東方" to "dongfang",
        "耽美" to "danmei",
        "生活" to "shenghuo",
        "格鬥" to "gedou",
        "轻小说" to "qingxiaoshuo",
        "悬疑" to "xuanyi",
        "其他" to "qita",
        "神鬼" to "shengui",
        "职场" to "zhichang",
        "TL" to "teenslove",
        "萌系" to "mengxi",
        "治愈" to "zhiyu",
        "長條" to "changtiao",
        "四格" to "sige",
        "节操" to "jiecao",
        "舰娘" to "jianniang",
        "竞技" to "jingji",
        "搞笑" to "gaoxiao",
        "伪娘" to "weiniang",
        "热血" to "rexue",
        "励志" to "lizhi",
        "性转换" to "xingzhuanhuan",
        "彩色" to "COLOR",
        "後宮" to "hougong",
        "美食" to "meishi",
        "侦探" to "zhentan",
        "AA" to "aa",
        "音乐舞蹈" to "yinyuewudao",
        "魔幻" to "mohuan",
        "战争" to "zhanzheng",
        "历史" to "lishi",
        "异世界" to "yishijie",
        "惊悚" to "jingsong",
        "机战" to "jizhan",
        "都市" to "dushi",
        "穿越" to "chuanyue",
        "恐怖" to "kongbu",
        "C100" to "comiket100",
        "重生" to "chongsheng",
        "C99" to "comiket99",
        "C101" to "comiket101",
        "C97" to "comiket97",
        "C96" to "comiket96",
        "生存" to "shengcun",
        "宅系" to "zhaixi",
        "武侠" to "wuxia",
        "C98" to "C98",
        "C95" to "comiket95",
        "FATE" to "fate",
        "转生" to "zhuansheng",
        "無修正" to "Uncensored",
        "仙侠" to "xianxia",
        "LoveLive" to "loveLive"
    )

    @OptIn(InternalParsersApi::class)
    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        // 保持顺序：域名设置在上方，其下方添加线路设置
        keys.add(preferredLineKey)
        keys.add(userAgentKey)
    }

    override val authUrl: String = "https://$DEFAULT_SITE_DOMAIN/login"

    override suspend fun isAuthorized(): Boolean {
        return getAuthToken() != null
    }

    private fun getAuthToken(): String? {
        val base = apiBase()
        val site = siteDomain()
        val domains = buildSet {
            add(base)
            add(site)
            addAll(API_DOMAINS)
            addAll(listOf("www.copy3000.com", "www.2026copy.com", "www.copy20.com", "www.mangacopy.com"))
        }
        logAuth("getAuthToken: searching in domains=$domains")
        for (domain in domains) {
            val cookies = context.cookieJar.getCookies(domain)
            val token = cookies.firstOrNull { it.name.equals("token", true) }?.value
                ?: cookies.firstOrNull { it.name.equals("authorization", true) }?.value
            if (!token.isNullOrEmpty()) {
                logAuth("getAuthToken: found token in domain=$domain, value=${maskToken(token)}")
                return token
            }
        }
        logAuth("getAuthToken: token NOT found")
        return null
    }

	override suspend fun getUsername(): String {
		if (!isAuthorized()) throw AuthRequiredException(source)
		nicknameRef.get()?.takeIf { it.isNotBlank() }?.let { 
            logAuth("getUsername: cached user=$it")
            return it 
        }
		val cookieName = "copy_nickname"
		val domains = listOf(siteDomain(), apiBase(), DEFAULT_SITE_DOMAIN)
		val fromCookie = domains.asSequence()
			.mapNotNull { domain ->
				val cookie = context.cookieJar.getCookies(domain).firstOrNull { it.name == cookieName }?.value
                if (cookie != null) logAuth("getUsername: found nickname in domain=$domain")
                cookie
			}
			.firstOrNull { it.isNotBlank() }
		if (!fromCookie.isNullOrBlank()) {
			nicknameRef.set(fromCookie)
            logAuth("getUsername: found nickname in cookies=$fromCookie")
			return fromCookie
		}
        logAuth("getUsername: nickname not found, fallback to User")
		return "User"
	}

    override suspend fun login(username: String, password: String): Boolean {
        val base = apiBase()
        val url = "https://$base/api/v3/login"
        val salt = Random.nextInt(1000, 10000).toString()
        val encryptedPassword = Base64.getEncoder().encodeToString("$password-$salt".toByteArray(Charsets.UTF_8))
        val requestBody = FormBody.Builder()
            .add("username", username)
            .add("password", encryptedPassword)
            .add("salt", salt)
            .add("source", "freeSite")
            .add("version", WEB_API_VERSION)
            .add("platform", WEB_PLATFORM)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .headers(getRequestHeaders())
            .tag(GZipOptions::class.java, GZipOptions(skip = true))
            .build()

        val response = try {
            context.httpClient.newCall(request).await()
        } catch (e: Exception) {
            logAuth("login: request failed: $e")
            return false
        }
        val json = try {
            response.unzip().parseJson()
        } catch (e: Exception) {
            logAuth("login: invalid response: $e")
            return false
        }

        val code = json.optInt("code")
        if (code == 210) {
            val msg = json.optString("message").ifBlank {
                json.optJSONObject("results")?.optString("detail").orEmpty()
            }
            throw ParseException("登录受限：$msg", url)
        }

        val token = json.optJSONObject("results")?.optString("token")
        val nickname = json.optJSONObject("results")?.optString("nickname").orEmpty()
        if (!token.isNullOrEmpty()) {
            logAuth("login: success, token=${maskToken(token)}")
            val site = siteDomain()
            context.cookieJar.insertCookies(site, "token=$token; Domain=$site; Path=/; HttpOnly")
            context.cookieJar.insertCookies(base, "token=$token; Domain=$base; Path=/; HttpOnly")
            if (nickname.isNotEmpty()) {
                nicknameRef.set(nickname)
                context.cookieJar.insertCookies(site, "copy_nickname=$nickname; Domain=$site; Path=/")
            }
            return true
        }
        logAuth("login: failed without token")
        return false
    }

    @OptIn(InternalParsersApi::class)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.UPDATED_ASC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
    )

    @OptIn(InternalParsersApi::class)
    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

    @OptIn(InternalParsersApi::class)
    override suspend fun getFilterOptions(): ContentListFilterOptions {
        // 将 JS 中的“主题”和“排行”的选择映射为标签组（固定分类）
        val themeTags: Set<ContentTag> = CATEGORY_PARAM_DICT.entries.map { entry ->
            ContentTag(title = entry.key, key = entry.value, source = source)
        }.toSet()
        return ContentListFilterOptions(
            availableTags = themeTags,
            tagGroups = listOf(
                ContentTagGroup(
                    title = "主题",
                    tags = themeTags,
                ),
            ),
            availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
            availableContentRating = emptySet(),
        )
    }

    @OptIn(InternalParsersApi::class)
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("Accept-Language", "en-US,en;q=0.9,zh-TW;q=0.8,zh;q=0.7")
        .add("Origin", WEB_ORIGIN)
        .add("Version", WEB_API_VERSION)
        .add("Region", config[preferredLineKey] ?: DEFAULT_REGION)
        .add("Webp", "0")
        .add("platform", WEB_PLATFORM)
        .add("sec-fetch-dest", "document")
        .add("sec-fetch-mode", "navigate")
        .add("sec-fetch-site", "same-origin")
        .add("sec-fetch-user", "?1")
        .add("upgrade-insecure-requests", "1")
        .add("User-Agent", config[userAgentKey])
        .apply {
            getAuthToken()?.let { add("Authorization", "Token $it") }
        }
        .build()

    @OptIn(InternalParsersApi::class)
    private fun apiBase(): String {
        return config[configKeyDomain]
    }

    private suspend fun resolveComicUuid(pathWord: String, headers: Headers): String? {
        val api = apiBase()
        val url = "https://$api/api/v3/comic2/$pathWord"
        return apiGetJson(url, headers).optJSONObject("results")?.optJSONObject("comic")?.optString("uuid")
    }

    override suspend fun fetchFavorites(): List<Content> {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val headers = getRequestHeaders()
        val api = apiBase()
        val result = mutableListOf<Content>()
        var offset = 0
        val limit = 30
        while (true) {
            val url =
                "https://$api/api/v3/member/collect/comics?limit=$limit&offset=$offset&free_type=1&ordering=-datetime_updated"
            val resp = webClient.httpGet(url.toHttpUrl(), headers)
            if (resp.code == 401) throw AuthRequiredException(source)
            if (!resp.isSuccessful) break
            val json = resp.parseJson()
            val results = json.optJSONObject("results") ?: break
            val list = results.optJSONArray("list") ?: JSONArray()
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val wrapper = list.optJSONObject(i) ?: continue
                val comic = wrapper.optJSONObject("comic") ?: wrapper
                val id = comic.optString("path_word")
                val title = comic.optString("name")
                val cover = comic.optString("cover")
                logDebug("favorites: id=$id title=$title cover=$cover")
                val tags = (comic.optJSONArray("theme") ?: JSONArray()).mapJSON { t ->
                    val n = t.optString("name")
                    ContentTag(title = n, key = n, source = source)
                }.toSet()
                val authors = (comic.optJSONArray("author") ?: JSONArray()).mapJSON { a ->
                    a.optString("name")
                }.toSet()
                val site = siteDomain()
                result.add(
                    Content(
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
                        contentRating = ContentRating.SAFE,
                    )
                )
            }
            val total = results.optInt("total", result.size)
            offset += limit
            if (offset >= total) break
        }
        return result
    }

    override suspend fun addFavorite(manga: Content): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val headers = getRequestHeaders().newBuilder()
            .add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .build()
        val uuid = resolveComicUuid(manga.url, headers) ?: return false
        val api = apiBase()
        val tokenHeader = headers["authorization"] ?: "Token"
        val body = "comic_id=$uuid&is_collect=1&authorization=${tokenHeader.removePrefix("Token ")}"
        val url = "https://$api/api/v3/member/collect/comic"
        val resp = webClient.httpPost(url.toHttpUrl(), body, headers)
        if (resp.code == 401) throw AuthRequiredException(source)
        return resp.isSuccessful
    }

    override suspend fun removeFavorite(manga: Content): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val headers = getRequestHeaders().newBuilder()
            .add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .build()
        val uuid = resolveComicUuid(manga.url, headers) ?: return false
        val api = apiBase()
        val tokenHeader = headers["authorization"] ?: "Token"
        val body = "comic_id=$uuid&is_collect=0&authorization=${tokenHeader.removePrefix("Token ")}"
        val url = "https://$api/api/v3/member/collect/comic"
        val resp = webClient.httpPost(url.toHttpUrl(), body, headers)
        if (resp.code == 401) throw AuthRequiredException(source)
        return resp.isSuccessful
    }

    private suspend fun apiGetJson(url: String, headers: Headers = getRequestHeaders()): JSONObject {
        val response = webClient.httpGet(url, headers)
        val body = response.parseJson()
        val code = body.optInt("code", response.code)
        if (code == 200) return body

        val message = body.optString("message").ifBlank {
            body.optJSONObject("results")?.optString("detail").orEmpty()
        }
        throw ParseException(
            shortMessage = "拷贝漫画接口错误 $code${message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
            url = url,
        )
    }

    @OptIn(InternalParsersApi::class)
    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val base = apiBase()
        val offset = (page - paginator.firstPage) * pageSize
        val isRanking = filter.tags.any { it.key == "ranking" }
        val url = if (isRanking) {
            // 排行接口：audience_type 与 date_type 作为“选项”绑定到 tags 中的 key 或查询
            val audience = filter.query?.substringBefore('|') ?: "male" // 默认男频
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
        } else if (!filter.query.isNullOrEmpty() && filter.query.startsWith("作者:")) {
            val authorName = filter.query.removePrefix("作者:").trim()
            val pathWord = authorPathWordDict[authorName]
            if (pathWord != null) {
                "https://$base/api/v3/comics?limit=$pageSize&offset=$offset" +
                    "&ordering=-datetime_updated&author=${pathWord.urlEncoded()}&_update=true&free_type=1"
            } else {
                "https://$base$BASE_SEARCH_API?limit=$pageSize&offset=$offset" +
                    "&q=${authorName.urlEncoded()}&q_type=author"
            }
        } else if (!filter.query.isNullOrEmpty()) {
            val q = filter.query.urlEncoded()
            buildString {
                append("https://")
                append(base)
                append(BASE_SEARCH_API)
                append("?limit=")
                append(pageSize)
                append("&offset=")
                append(offset)
                append("&q=")
                append(q)
                append("&q_type=")
                append("") // 默认空字符串，匹配全部或按 JS 逻辑
            }
        } else {
            // 主题分类列表
            val themeParam = filter.tags.firstOrNull()?.key ?: ""
            val ordering = when (order) {
                SortOrder.UPDATED -> "-datetime_updated"
                SortOrder.UPDATED_ASC -> "datetime_updated"
                SortOrder.POPULARITY -> "-popular"
                SortOrder.POPULARITY_ASC -> "popular"
                else -> "-datetime_updated"
            }
            val top = when {
                ContentState.FINISHED in filter.states -> "finish"
                ContentState.ONGOING in filter.states -> "-全部"
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

        val root = apiGetJson(url)
        val list = root.optJSONObject("results")?.optJSONArray("list") ?: JSONArray()
        return list.mapJSON { jo ->
                val comic = jo.optJSONObject("comic") ?: jo
                val id = comic.optString("path_word")
                val title = comic.optString("name")
                val cover = comic.optString("cover")
                logDebug("list: id=$id title=$title cover=$cover")
                val tagsArray = comic.optJSONArray("theme") ?: JSONArray()
                val tags = tagsArray.mapJSON { t ->
                    val n = t.optString("name")
                    ContentTag(title = n, key = n, source = source)
                }.toSet()
                val authors = (comic.optJSONArray("author") ?: JSONArray()).mapJSON { a -> a.optString("name") }.toSet()
                val site = siteDomain()
                Content(
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
    }

    @OptIn(InternalParsersApi::class)
    override suspend fun getDetails(manga: Content): Content {
        val base = apiBase()
        val headers = getRequestHeaders()
        val detailUrl = "https://$base/api/v3/comic2/${manga.url}"
        val res = apiGetJson(detailUrl, headers).optJSONObject("results") ?: return manga
        val comic = res.optJSONObject("comic") ?: return manga

        // 记录作者 path_word (参考 JS 配置)
        val authorsArray = comic.optJSONArray("author") ?: JSONArray()
        for (i in 0 until authorsArray.length()) {
            val authorObj = authorsArray.optJSONObject(i) ?: continue
            val name = authorObj.optString("name")
            val pathWord = authorObj.optString("path_word")
            if (name.isNotBlank() && pathWord.isNotBlank()) {
                authorPathWordDict[name] = pathWord
            }
        }

        val title = comic.optString("name", manga.title).ifBlank { manga.title }
        val cover = comic.optString("cover", manga.coverUrl).ifBlank { manga.coverUrl }
        logDebug("details: url=${manga.url} title=$title cover=$cover authors=${authorPathWordDict.keys.joinToString()}")
        val desc = comic.optString("brief", manga.description).ifBlank { manga.description }
        val stateStr = comic.optString("status", "")
        val state = when (stateStr.lowercase()) {
            "finished", "end" -> ContentState.FINISHED
            "ongoing" -> ContentState.ONGOING
            else -> manga.state
        }

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

        val chapters = ArrayList<ContentChapter>()
        for (path in pathList) {
            logDebug("chapters: branch=${manga.url} group=$path base=$base")
            var offset = 0
            while (true) {
                val list = fetchChapters(base, manga.url, path, offset, headers)
                if (list.length() == 0) break
                
                for (j in 0 until list.length()) {
                    val c = list.optJSONObject(j) ?: continue
                    val serial = c.optString("name", "${offset + j + 1}")
                    val uuid = c.optString("uuid")
                    val idPathWord = c.optString("path_word", "${manga.url}-${offset + j}")
                    val id = if (uuid.isNotBlank()) uuid else idPathWord
                    val number = parseChapterNumber(serial) ?: (offset + j + 1).toFloat()
                    chapters += ContentChapter(
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

    private suspend fun fetchChapters(
        base: String,
        mangaUrl: String,
        groupPath: String,
        offset: Int,
        headers: Headers,
    ): JSONArray {
        val url = "https://$base/api/v3/comic/$mangaUrl/group/$groupPath/chapters" +
            "?limit=100&offset=$offset&_update=true"
        return apiGetJson(url, headers).optJSONObject("results")?.optJSONArray("list") ?: JSONArray()
    }

    @OptIn(InternalParsersApi::class)
    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = "https://${apiBase()}/api/v3/comic/${chapter.branch}/chapter2/${chapter.url}"
        val chapterData = apiGetJson(url).optJSONObject("results")?.optJSONObject("chapter")
            ?: throw ParseException("章节内容为空", url)
        return parsePages(chapter, chapterData)
    }

    private fun parsePages(chapter: ContentChapter, chapterData: JSONObject): List<ContentPage> {
        val contents = chapterData.optJSONArray("contents") ?: JSONArray()
        val orders = chapterData.optJSONArray("words") ?: JSONArray()
        val pages = contents.mapJSONIndexed { index, item ->
            val rawUrl = item.optString("url")
            val pageUrl = if (rawUrl.hasSignedQuery()) rawUrl else rawUrl.replaceResolution()
            orders.optInt(index, index) to pageUrl
        }.filter { (_, url) -> url.isNotBlank() }

        return pages.sortedBy { it.first }.mapIndexed { index, (_, url) ->
            ContentPage(
                id = generateUid("${chapter.url}/$index"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun String.hasSignedQuery(): Boolean = contains('?') ||
        contains("token=", ignoreCase = true) ||
        contains("sign", ignoreCase = true) ||
        contains("auth", ignoreCase = true)

    private fun String.replaceResolution(): String = replace(
        Regex("""([./])c\d+x\.([a-zA-Z]+)$"""),
    ) { match -> "${match.groupValues[1]}c${imageQuality}x.webp" }

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


    @OptIn(InternalParsersApi::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val accept = req.header("Accept").orEmpty()
        val url = req.url
        val path = url.encodedPath
        val isApiRequest = path.startsWith("/api/")
        val hasImageExt = url.pathSegments.lastOrNull()?.let { seg ->
            seg.endsWith(".jpg", true) || seg.endsWith(".jpeg", true) || seg.endsWith(".png", true) || seg.endsWith(".webp", true) || seg.endsWith(".gif", true) || seg.endsWith(".avif", true) || seg.endsWith(".svg", true) || seg.endsWith(".ico", true)
        } == true
        val isImageRequest = accept.contains("image/") || hasImageExt

        if (isApiRequest) {
            synchronized(apiRateLimitLock) {
                val waitMs = API_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastApiRequestTime)
                if (waitMs > 0) Thread.sleep(waitMs)
                lastApiRequestTime = System.currentTimeMillis()
            }
            return chain.proceed(req).unzip()
        }

        if (isImageRequest) {
            val imageRequest = req.newBuilder()
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", UserAgents.CHROME_MOBILE)
                .header("Referer", "https://${siteDomain()}/")
                .removeHeader("Authorization")
                .removeHeader("Cookie")
                .build()
            val response = chain.proceed(imageRequest)
            return if (response.header("Content-Type").orEmpty().contains("octet-stream", ignoreCase = true)) {
                response.newBuilder().header("Content-Type", "image/jpeg").build()
            } else {
                response
            }
        }

        if (req.method == "GET") {
            val webRequest = req.newBuilder()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,image/png,image/*,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", config[userAgentKey])
                .removeHeader("Authorization")
                .removeHeader("Cookie")
                .build()
            val response = chain.proceed(webRequest).unzip()
            return if (response.header("Content-Type").orEmpty().contains("octet-stream", ignoreCase = true)) {
                response.newBuilder().header("Content-Type", "image/jpeg").build()
            } else {
                response
            }
        }

        return chain.proceed(req).unzip()
    }

    /** 兼容服务端返回已压缩但未被 OkHttp 自动解压的响应。 */
    @OptIn(InternalParsersApi::class)
    private fun Response.unzip(): Response {
        val contentEncoding = header("Content-Encoding")
        if (contentEncoding?.contains("gzip", ignoreCase = true) != true) return this
        val responseBody = body ?: return this
        
        return try {
            val bytes = GZIPInputStream(responseBody.byteStream()).readBytes()
            logAuth("unzip: decompressed ${responseBody.contentLength()} bytes to ${bytes.size} bytes")
            val contentType = responseBody.contentType()
            newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(bytes.toResponseBody(contentType))
                .build()
        } catch (e: Exception) {
            logAuth("unzip: decompression failed: $e")
            this
        }
    }

    private fun maskToken(token: String?): String {
        if (token.isNullOrEmpty()) return ""
        return if (token.length <= 8) token else token.take(4) + "..." + token.takeLast(4)
    }

    private fun logAuth(msg: String) {
        kotlin.runCatching { println("[CopyContentAuth] $msg") }
    }
    private fun logDebug(msg: String) {
        kotlin.runCatching { println("[CopyContentDebug] $msg") }
    }

    companion object {
        private const val DEFAULT_SITE_DOMAIN = "www.copy3000.com"
        private const val DEFAULT_REGION = "0"
        private const val WEB_ORIGIN = "https://2025copy.com"
        private const val WEB_API_VERSION = "2025.11.21"
        private const val WEB_PLATFORM = "1"
        private const val API_REQUEST_INTERVAL_MS = 100L
        private const val BASE_SEARCH_API = "/api/v3/search/comic"
        private val API_DOMAINS = listOf(
            "api.copy3000.com",
            "api.2026copy.com",
            "api.copy202601.com",
            "api.mangacopy.com",
            "mapi.copy20.com",
        )
    }
}
