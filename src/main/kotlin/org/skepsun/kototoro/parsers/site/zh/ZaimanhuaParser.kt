@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.MangaParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.time.LocalDate
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference

/**
 * 再漫画 (v4api.zaimanhua.com)
 */
@MangaSourceParser("ZAIMANHUA", "再漫画", "zh")
internal class ZaimanhuaParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ZAIMANHUA, pageSize = 20),
    MangaParserAuthProvider,
    MangaParserCredentialsAuthProvider,
    FavoritesProvider,
    org.skepsun.kototoro.parsers.FavoritesSyncProvider {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("v4api.zaimanhua.com")
    override val authUrl: String = "https://www.zaimanhua.com/login"
    override val faviconDomain: String
        get() = "www.zaimanhua.com"

    override fun onCreateConfig(keys: MutableCollection<org.skepsun.kototoro.parsers.config.ConfigKey<*>>) {
        super.onCreateConfig(keys) // Abstract 已添加域名配置，这里只补充额外开关
        keys.add(signTaskKey)
    }

    private val tokenRef = AtomicReference<String?>()
    private val usernameRef = AtomicReference<String?>()
    private val lastSignDate = AtomicReference<String?>()

    init {
        tokenRef.set(tokenFromCookies())
        usernameRef.set(usernameFromCookies())
        logAuth("init -> token=${maskToken(tokenRef.get())}, user=${usernameRef.get().orEmpty()}")
    }

    private val categoryMap: List<FilterOption> = listOf(
        "全部" to "0",
        "冒险" to "4",
        "欢乐向" to "5",
        "格斗" to "6",
        "科幻" to "7",
        "爱情" to "8",
        "侦探" to "9",
        "竞技" to "10",
        "魔法" to "11",
        "神鬼" to "12",
        "校园" to "13",
        "惊悚" to "14",
        "其他" to "16",
        "四格" to "17",
        "亲情" to "3242",
        "百合" to "3243",
        "秀吉" to "3244",
        "悬疑" to "3245",
        "纯爱" to "3246",
        "热血" to "3248",
        "泛爱" to "3249",
        "历史" to "3250",
        "战争" to "3251",
        "萌系" to "3252",
        "宅系" to "3253",
        "治愈" to "3254",
        "励志" to "3255",
        "武侠" to "3324",
        "机战" to "3325",
        "音乐舞蹈" to "3326",
        "美食" to "3327",
        "职场" to "3328",
        "西方魔幻" to "3365",
        "高清单行" to "4459",
        "TS" to "4518",
        "东方" to "5077",
        "魔幻" to "5806",
        "奇幻" to "5848",
        "节操" to "6219",
        "轻小说" to "6316",
        "颜艺" to "6437",
        "搞笑" to "7568",
        "仙侠" to "23388",
        "舰娘" to "7900",
        "动画" to "13627",
        "AA" to "17192",
        "福瑞" to "18522",
        "生存" to "23323",
        "日常" to "23388",
        "画集" to "30788",
        "C100" to "31137",
    ).map { FilterOption(it.second, it.first, "theme") }

    private val cateOptions = listOf(
        FilterOption("0", "全部", "cate"),
        FilterOption("3262", "少年漫画", "cate"),
        FilterOption("3263", "少女漫画", "cate"),
        FilterOption("3264", "青年漫画", "cate"),
        FilterOption("13626", "女青漫画", "cate"),
    )

    private val statusOptions = listOf(
        FilterOption("0", "全部", "status"),
        FilterOption("2309", "连载中", "status"),
        FilterOption("2310", "已完结", "status"),
        FilterOption("29205", "短篇", "status"),
    )

    private val zoneOptions = listOf(
        FilterOption("0", "全部", "zone"),
        FilterOption("2304", "日本", "zone"),
        FilterOption("2305", "韩国", "zone"),
        FilterOption("2306", "欧美", "zone"),
        FilterOption("2307", "港台", "zone"),
        FilterOption("2308", "内地", "zone"),
        FilterOption("8435", "其他", "zone"),
    )

    private val signTaskKey = org.skepsun.kototoro.parsers.config.ConfigKey.Toggle(
        key = "zaimanhua_sign_task",
        title = "每日签到",
        defaultValue = true,
    )

    private fun headers(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .apply {
            tokenRef.get()?.let { add("authorization", "Bearer $it") }
        }
        .build()

    private fun buildUrl(path: String): String = "https://${config[configKeyDomain]}/app/v1/$path"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val themeTags = categoryMap.map { it.toTag(source) }.toSet()
        val cateTags = cateOptions.map { it.toTag(source) }.toSet()
        val statusTags = statusOptions.map { it.toTag(source) }.toSet()
        val zoneTags = zoneOptions.map { it.toTag(source) }.toSet()
        val all = themeTags + cateTags + statusTags + zoneTags
        return MangaListFilterOptions(
            availableTags = all,
            tagGroups = listOf(
                MangaTagGroup("分类", themeTags),
                MangaTagGroup("题材", cateTags),
                MangaTagGroup("状态", statusTags),
                MangaTagGroup("地区", zoneTags),
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )
    }

    override fun getRequestHeaders(): Headers = headers()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        runCatching { trySignTask() }
        val selected = filter.tags.associateBy { it.key.substringBefore(':', "") }
        val theme = selected["theme"]?.key?.substringAfter(':').orEmpty()
        val cate = selected["cate"]?.key?.substringAfter(':').orEmpty()
        val status = selected["status"]?.key?.substringAfter(':').orEmpty()
        val zone = selected["zone"]?.key?.substringAfter(':').orEmpty()
        val sortType = if (order == SortOrder.POPULARITY) 2 else 1
        val url = when {
            !filter.query.isNullOrEmpty() -> buildUrl("search/index?keyword=${filter.query.urlEncoded()}&page=$page&sort=0&size=$pageSize")
            else -> buildUrl(
                "comic/filter/list?status=${status.ifEmpty { "0" }}&theme=${theme.ifEmpty { "0" }}&zone=${zone.ifEmpty { "0" }}&cate=${cate.ifEmpty { "0" }}&sortType=$sortType&page=$page&size=$pageSize",
            )
        }
        val res = runCatching { webClient.httpGet(url, headers()) }.getOrElse { return emptyList() }
        if (!res.isSuccessful) return emptyList()
        val root = res.parseJsonObject()
        val dataObj = root.optJSONObject("data")
        val items = dataObj?.optJSONArray("comicList")
            ?: dataObj?.optJSONArray("data")
            ?: dataObj?.optJSONArray("list")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("result")
            ?: return emptyList()
        val list = mutableListOf<Manga>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = listOf(obj.optString("comic_id"), obj.optString("id"))
                .firstOrNull { it.isNotEmpty() && it != "0" }
                ?: continue
            val title = obj.optString("title").ifEmpty { obj.optString("name") }
            val cover = obj.optString("cover")
            val tags: Set<MangaTag> = when {
                obj.optJSONArray("types") != null -> {
                    val tArr = obj.optJSONArray("types")
                    mutableSetOf<MangaTag>().apply {
                        for (j in 0 until tArr.length()) {
                            val t = tArr.optJSONObject(j)?.optString("tag_name").orEmpty()
                            if (t.isNotEmpty()) add(MangaTag(t, t, source))
                        }
                    }
                }
                obj.optString("types").isNotEmpty() -> {
                    obj.optString("types").split('/', '、', ',', '|').mapNotNull {
                        val t = it.trim()
                        t.takeIf { it.isNotEmpty() }?.let { name -> MangaTag(name, name, source) }
                    }.toSet()
                }
                else -> emptySet()
            }
            val rating = classifyRating(tags)
            list.add(
                Manga(
                    id = generateUid(id),
                    url = id,
                    publicUrl = "https://www.zaimanhua.com/comic/$id",
                    coverUrl = cover,
                    title = title,
                    altTitles = emptySet<String>(),
                    rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                    tags = tags,
                    authors = emptySet<String>(),
                    state = null,
                    source = source,
                    contentRating = rating,
                )
            )
        }
        val ratingFilter = filter.contentRating
        return if (ratingFilter.isEmpty()) list else list.filter { it.contentRating in ratingFilter }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        fun freshHeaders(): Headers = headers().newBuilder()
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .build()

        var res = runCatching {
            webClient.httpGet(buildUrl("comic/detail/${manga.url}?channel=android"), freshHeaders())
        }.getOrElse { return manga }
        if (res.code == 304) {
            val retryUrl = buildUrl("comic/detail/${manga.url}?channel=android&_t=${System.currentTimeMillis()}")
            res = runCatching { webClient.httpGet(retryUrl, freshHeaders()) }.getOrElse { return manga }
        }
        if (!res.isSuccessful) return manga
        val root = res.parseJsonObject()
        val errno = root.optInt("errno", -1)
        if (errno != 0) {
            // errno 2 usually means "does not exist", but for some manga it means "login required"
            if (errno == 2 && !isAuthorized()) {
                throw AuthRequiredException(source)
            }
            return manga
        }
        val data = root.optJSONObject("data")?.optJSONObject("data")
            ?: root.optJSONObject("data")
            ?: return manga
        val title = data.optString("title", manga.title).ifEmpty { manga.title }
        val cover = data.optString("cover", manga.coverUrl)
        val desc = data.optString("description", manga.description ?: "")

        fun mapTags(arr: org.json.JSONArray?): MutableSet<MangaTag> = mutableSetOf<MangaTag>().apply {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("tag_name").orEmpty()
                    if (name.isNotEmpty()) add(MangaTag(name, name, source))
                }
            }
        }

        val authors = data.optJSONArray("authors")?.let { arr ->
            mutableSetOf<String>().apply {
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optString("tag_name").orEmpty()
                    if (v.isNotEmpty()) add(v)
                }
            }
        } ?: emptySet()
        val tags = mapTags(data.optJSONArray("types"))
        tags.addAll(mapTags(data.optJSONArray("status")))
        val rating = classifyRating(tags)

        val chapters = mutableListOf<MangaChapter>()
        val groupsArray = data.optJSONArray("chapters")
            ?: data.optJSONArray("chapterList")
        val groupsObject = if (groupsArray == null) data.optJSONObject("chapters") else null

        fun appendGroup(branchTitle: String?, arr: org.json.JSONArray) {
            for (j in arr.length() - 1 downTo 0) {
                val ch = arr.optJSONObject(j) ?: continue
                val cid = ch.optString("chapter_id").ifEmpty { ch.optString("id") }
                val name = ch.optString("chapter_title").ifEmpty { ch.optString("title") }
                if (cid.isEmpty()) continue
                val number = (chapters.size + 1).toFloat()
                chapters.add(
                    MangaChapter(
                        id = generateUid("$cid-${manga.id}"),
                        url = "${manga.url}/$cid",
                        title = name.ifEmpty { "Chapter $cid" },
                        number = number,
                        volume = 0,
                        scanlator = null,
                        uploadDate = 0,
                        branch = branchTitle,
                        source = source,
                    )
                )
            }
        }

        if (groupsArray != null) {
            for (i in 0 until groupsArray.length()) {
                val group = groupsArray.optJSONObject(i) ?: continue
                val branchTitle = group.optString("title").ifEmpty { null }
                val arr = group.optJSONArray("data")
                    ?: group.optJSONArray("chapter_list")
                    ?: continue
                appendGroup(branchTitle, arr)
            }
        } else if (groupsObject != null) {
            val keys = groupsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = groupsObject.optJSONArray(key) ?: continue
                appendGroup(key.takeIf { it.isNotEmpty() }, arr)
            }
        }

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = desc.ifEmpty { manga.description },
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            chapters = chapters,
            contentRating = rating,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/")
        val comicId = parts.firstOrNull().orEmpty()
        val chapterId = parts.getOrNull(1).orEmpty()
        if (comicId.isEmpty() || chapterId.isEmpty()) return emptyList()
        val res = runCatching { webClient.httpGet(buildUrl("comic/chapter/$comicId/$chapterId"), headers()) }.getOrElse { return emptyList() }
        if (!res.isSuccessful) return emptyList()
        val data = res.parseJsonObject().optJSONObject("data")?.optJSONObject("data") ?: return emptyList()
        val images = data.optJSONArray("page_url_hd") ?: data.optJSONArray("page_url") ?: return emptyList()
        val list = mutableListOf<MangaPage>()
        for (i in 0 until images.length()) {
            val img = images.optString(i)
            if (img.isNotEmpty()) {
                val finalUrl = if (img.startsWith("http")) img else "https://i.zaimanhua.com$img"
                list.add(
                    MangaPage(
                        id = generateUid("$finalUrl-$i"),
                        url = finalUrl,
                        preview = finalUrl,
                        source = source,
                    )
                )
            }
        }
        return list
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun login(username: String, password: String): Boolean {
        val encrypted = java.security.MessageDigest.getInstance("MD5")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = "username=${username.urlEncoded()}&passwd=${encrypted}"
        val res = webClient.httpPost(
            "https://account-api.zaimanhua.com/v1/login/passwd".toHttpUrl(),
            body,
            Headers.Builder()
                .add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .build()
        )
        if (!res.isSuccessful) {
            throw org.skepsun.kototoro.parsers.exception.ParseException("登录失败: HTTP ${res.code}", res.request.url.toString())
        }
        val json = res.parseJsonObject()
        val errno = json.optInt("errno", -1)
        if (errno != 0) {
            throw org.skepsun.kototoro.parsers.exception.ParseException("登录失败: ${json.optString("errmsg")}", res.request.url.toString())
        }
        val token = json.optJSONObject("data")?.optJSONObject("user")?.optString("token")
        if (token.isNullOrEmpty()) {
            throw org.skepsun.kototoro.parsers.exception.ParseException("登录失败: 未返回 token", res.request.url.toString())
        }
        tokenRef.set(token)
        usernameRef.set(username)
        context.cookieJar.insertCookies(config[configKeyDomain], "authorization=Bearer $token", "token=$token")
        context.cookieJar.insertCookies(config[configKeyDomain], "zmh_username=${username.urlEncoded()}")
        logAuth("login success -> token=${maskToken(token)}, user=$username")
        lastSignDate.set(null)
        return true
    }

    override suspend fun getUsername(): String {
        if (tokenRef.get().isNullOrEmpty()) {
            throw AuthRequiredException(source)
        }
        val cached = usernameRef.get().takeUnless { it.isNullOrEmpty() }
            ?: usernameFromCookies()
        if (cached != null) {
            usernameRef.set(cached)
            logAuth("getUsername -> cached user=$cached")
            return cached
        }
        logAuth("getUsername -> fallback User (no username found)")
        return "User"
    }

    override suspend fun isAuthorized(): Boolean {
        if (tokenRef.get().isNullOrEmpty()) {
            logAuth("isAuthorized -> memory token empty, try cookies")
            tokenRef.set(tokenFromCookies())
            if (usernameRef.get().isNullOrEmpty()) {
                usernameRef.set(usernameFromCookies())
            }
        }
        val ok = !tokenRef.get().isNullOrEmpty()
        logAuth("isAuthorized -> $ok, token=${maskToken(tokenRef.get())}, user=${usernameRef.get().orEmpty()}")
        return ok
    }

    suspend fun logout(): Boolean {
        tokenRef.set(null)
        usernameRef.set(null)
        context.cookieJar.insertCookies(
            config[configKeyDomain],
            "authorization=; Max-Age=0; Path=/",
            "token=; Max-Age=0; Path=/",
            "zmh_username=; Max-Age=0; Path=/",
        )
        logAuth("logout -> cleared token/user")
        lastSignDate.set(null)
        return true
    }

    private fun tokenFromCookies(): String? {
        val domains = listOf(
            config[configKeyDomain],
            "account-api.zaimanhua.com",
            "www.zaimanhua.com",
            "zaimanhua.com",
        ).distinct()
        for (host in domains) {
            val cookies = context.cookieJar.getCookies(host)
            val tokenCookie = cookies.firstOrNull { it.name.equals("authorization", true) || it.name.equals("token", true) }
            if (tokenCookie != null && tokenCookie.value.isNotBlank()) {
                logAuth("tokenFromCookies -> hit domain=$host, name=${tokenCookie.name}, value=${maskToken(tokenCookie.value)}")
                return tokenCookie.value.removePrefix("Bearer ").takeIf { it.isNotBlank() }
            } else {
                logAuth("tokenFromCookies -> domain=$host, cookies=${cookies.joinToString { it.name }}")
            }
        }
        logAuth("tokenFromCookies -> not found")
        return null
    }

    private fun usernameFromCookies(): String? {
        val host = config[configKeyDomain]
        val cookies = context.cookieJar.getCookies(host)
        val user = cookies.firstOrNull { it.name.equals("zmh_username", true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
        logAuth("usernameFromCookies -> domain=$host, names=${cookies.joinToString { it.name }}, user=${user.orEmpty()}")
        return user
    }

    private fun maskToken(token: String?): String {
        if (token.isNullOrEmpty()) return ""
        return if (token.length <= 8) token else token.take(4) + "..." + token.takeLast(4)
    }

    private fun logAuth(msg: String) {
        kotlin.runCatching { println("[ZaimanhuaAuth] $msg") }
    }

    private fun classifyRating(tags: Set<MangaTag>): ContentRating {
        val titles = tags.map { it.title.lowercase() }
        val adultKeywords = setOf("r18", "18+", "成人", "限制", "h漫", "本子", "里番", "nsfw", "smut", "情色", "节操")
        val suggestiveKeywords = setOf("后宫", "福利", "擦边", "性感")
        return when {
            titles.any { t -> adultKeywords.any { t.contains(it) } } -> ContentRating.ADULT
            titles.any { t -> suggestiveKeywords.any { t.contains(it) } } -> ContentRating.SUGGESTIVE
            else -> ContentRating.SAFE
        }
    }

    private data class FilterOption(val value: String, val title: String, val ns: String) {
        fun toTag(source: MangaSource): MangaTag = MangaTag(title, "$ns:$value", source)
    }

    private suspend fun trySignTask() {
        if (!config[signTaskKey]) return
        if (tokenRef.get().isNullOrEmpty()) return
        val today = LocalDate.now().toString()
        if (lastSignDate.get() == today) return
        val res = runCatching {
            webClient.httpPost(
                "https://i.zaimanhua.com/lpi/v1/task/sign_in".toHttpUrl(),
                emptyMap<String, String>(),
                headers(),
            )
        }.getOrElse { return }
        if (!res.isSuccessful) return
        val json = res.parseJsonObject()
        if (json.optInt("errno", -1) == 0) {
            lastSignDate.set(today)
            context.showToast("再漫画：签到成功")
        }
    }

    override suspend fun fetchFavorites(): List<Manga> {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val headers = headers()
        val result = mutableListOf<Manga>()
        var page = 1
        val size = 50
        while (true) {
            val url = buildUrl("comic/sub/list?status=0&page=$page&size=$size")
            val resp = webClient.httpGet(url, headers)
            if (resp.code == 401) throw AuthRequiredException(source)
            if (!resp.isSuccessful) break
            val json = resp.parseJsonObject()
            val data = json.optJSONObject("data")
            val subList = data?.optJSONArray("subList") ?: break
            if (subList.length() == 0) break
            for (i in 0 until subList.length()) {
                val obj = subList.optJSONObject(i) ?: continue
                val id = obj.optString("comic_id").ifEmpty { obj.optString("id") }
                if (id.isEmpty()) continue
                val title = obj.optString("title").ifEmpty { obj.optString("name") }
                val cover = obj.optString("cover")
                val tags = obj.optJSONArray("types")?.let { arr ->
                    mutableSetOf<MangaTag>().apply {
                        for (j in 0 until arr.length()) {
                            val n = arr.optJSONObject(j)?.optString("tag_name").orEmpty()
                            if (n.isNotEmpty()) add(MangaTag(n, n, source))
                        }
                    }
                } ?: emptySet()
                val rating = classifyRating(tags)
                result.add(
                    Manga(
                        id = generateUid(id),
                        url = id,
                        publicUrl = "https://www.zaimanhua.com/comic/$id",
                        coverUrl = cover,
                        title = title,
                        altTitles = emptySet(),
                        rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                        tags = tags,
                        authors = emptySet(),
                        state = null,
                        source = source,
                        contentRating = rating,
                    )
                )
            }
            val total = data?.optInt("total", result.size) ?: result.size
            if (page * size >= total) break
            page += 1
        }
        return result
    }

    override suspend fun addFavorite(manga: Manga): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val headers = headers()
        val url = buildUrl("comic/sub/add?comic_id=${manga.url}")
        val res = webClient.httpGet(url, headers)
        if (res.code == 401) throw AuthRequiredException(source)
        val json = res.parseJsonObject()
        return json.optInt("errno", -1) == 0
    }

    override suspend fun removeFavorite(manga: Manga): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val headers = headers()
        val url = buildUrl("comic/sub/del?comic_id=${manga.url}")
        val res = webClient.httpGet(url, headers)
        if (res.code == 401) throw AuthRequiredException(source)
        val json = res.parseJsonObject()
        return json.optInt("errno", -1) == 0
    }
}
