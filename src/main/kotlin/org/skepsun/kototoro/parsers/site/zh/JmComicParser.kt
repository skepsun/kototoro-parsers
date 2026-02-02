package org.skepsun.kototoro.parsers.site.zh

import java.security.MessageDigest
import java.util.EnumSet
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.FavoritesSyncProvider
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.skepsun.kototoro.parsers.bitmap.Rect
import java.util.zip.GZIPInputStream
import okhttp3.FormBody
import okhttp3.Request
import org.skepsun.kototoro.parsers.model.MangaSource
import org.skepsun.kototoro.parsers.network.GZipOptions

/**
 * JM (禁漫天堂) API-based parser.
 *
 * Ported from venera jm.js:
 * - API 请求需要 token + tokenparam
 * - data 字段 AES/ECB/PKCS5 解密
 * - 图片需要解扰（按 epId + 文件名计算分块反转）
 */
@MangaSourceParser("JMCOMIC", "禁漫天堂", "zh", type = ContentType.HENTAI_MANGA)
internal class JmParser(
	context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.JMCOMIC, pageSize = 80), 
    Interceptor, 
    MangaParserAuthProvider, 
    MangaParserCredentialsAuthProvider,
    FavoritesProvider,
    FavoritesSyncProvider {

    private val categoryTags = listOf(
        "最新A漫" to "0",
        "同人" to "doujin",
        "單本" to "single",
        "短篇" to "short",
        "其他類" to "another",
        "韓漫" to "hanman",
        "美漫" to "meiman",
        "Cosplay" to "another_cosplay",
        "3D" to "3D",
        "禁漫漢化組" to "禁漫漢化組",
    )

    private val groupedSearchTags: List<Pair<String, List<String>>> = listOf(
        "主題A漫" to listOf(
            "無修正", "劇情向", "青年漫", "校服", "純愛", "人妻", "教師", "百合", "Yaoi", "性轉", "NTR",
            "女裝", "癡女", "全彩", "女性向", "完結", "禁漫漢化組",
        ),
        "角色扮演" to listOf(
            "御姐", "熟女", "巨乳", "貧乳", "女性支配", "女僕", "護士", "泳裝", "眼鏡", "連褲襪", "其他制服", "兔女郎",
        ),
        "特殊PLAY" to listOf(
            "群交", "足交", "束縛", "肛交", "阿黑顏", "藥物", "扶他", "調教", "野外露出", "催眠", "自慰", "觸手", "獸交",
            "亞人", "怪物女孩", "皮物", "ryona", "騎大車",
        ),
        "其他" to listOf(
            "CG", "重口", "獵奇", "非H", "血腥暴力", "站長推薦",
        ),
    )

    private val apiKey = "18comicAPPContent"
    private val dataSecret = "185Hcomic3PAPP7R"
    private val jmVersion = "2.0.16"
    private val packageName = "com.example.app"

    // API 域名，允许后续通过设置刷新
    private var apiDomains: List<String> = listOf(
        "www.cdntwice.org",
        "www.cdnsha.org",
        "www.cdnaspa.cc",
        "www.cdnntr.cc",
    )
    private var activeDomain: String = apiDomains.first()
    private var imageHost: String = "https://cdn-msp.jmapinodeudzn.net"
    private var domainsInitialized = false
    private var imageHostInitialized = false
    private var lastLoginEmail: String? = null

    override val faviconDomain: String
        get() = "18comic.vip"
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        apiDomains.first(),
        *apiDomains.drop(1).toTypedArray(),
    )
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.JM_WEBVIEW)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_YEAR, // 用作「最多圖片」
        SortOrder.RATING, // 用作「最多喜歡」
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val categoryTagObjs = categoryTags.map { (title, param) ->
            MangaTag(title = title, key = "c:$param", source = source)
        }.toSet()
        val searchGroups = groupedSearchTags.map { (groupName, tags) ->
            val distinctTags = LinkedHashSet<String>().apply { addAll(tags) }
            val tagObjs = distinctTags.map { title ->
                MangaTag(title = title, key = "s:$title", source = source)
            }.toSet()
            MangaTagGroup(groupName, tagObjs)
        }
        val allSearchTags = searchGroups.flatMap { it.tags }.toSet()
        val allTags = (categoryTagObjs + allSearchTags).toSet()
        val tagGroups = buildList {
            add(MangaTagGroup("分類", categoryTagObjs))
            addAll(searchGroups)
        }
        return MangaListFilterOptions(
            availableTags = allTags,
            tagGroups = tagGroups,
        )
    }

    private val baseUrl: String
        get() = "https://${activeDomain}"

    private val headersBase: Headers
        get() = Headers.Builder()
            .add("Accept", "*/*")
            // Do not send Accept-Encoding manually; let OkHttp handle gzip transparently
            .add("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Connection", "keep-alive")
            .add("Origin", "https://localhost")
            .add("Referer", "https://localhost/")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("X-Requested-With", packageName)
            .add("User-Agent", userAgentKey.defaultValue)
            .build()

    override fun getRequestHeaders(): Headers = headersBase

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        ensureDomains()
        val sort = sortParam(order)
        val categoryTag = filter.tags.firstOrNull { it.key.startsWith("c:") }
        // 其余标签（含无前缀的详情页标签）都作为搜索关键字
        val searchTags = filter.tags.filterNot { it.key.startsWith("c:") }

        // jm.js 里分类标签走 categories/filter，不与搜索组合
        if (categoryTag != null) {
            val param = categoryTag.key.removePrefix("c:")
            return categoryList(sort, page, param)
        }

        val keyword = buildKeyword(filter.query, searchTags)
        return if (!keyword.isNullOrBlank()) search(keyword, page, sort) else categoryList(sort, page, "0")
    }

    private fun buildKeyword(query: String?, searchTags: List<MangaTag>): String? {
        val parts = mutableListOf<String>()
        if (!query.isNullOrBlank()) parts += query
        if (searchTags.isNotEmpty()) parts += searchTags.map { it.title.ifBlank { it.key } }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private fun sortParam(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY -> "mv"
        SortOrder.POPULARITY_MONTH -> "mv_m"
        SortOrder.POPULARITY_WEEK -> "mv_w"
        SortOrder.POPULARITY_TODAY -> "mv_t"
        SortOrder.POPULARITY_YEAR -> "mp" // 最多圖片
        SortOrder.RATING -> "tf" // 最多喜歡
        else -> "mr" // 最新
    }

    private suspend fun search(keyword: String, page: Int, sort: String): List<Manga> {
        val kw = keyword.trim().urlEncoded().replace("%20", "+")
        val url = buildString {
            append(baseUrl)
            append("/search?search_query=")
            append(kw)
            append("&o=$sort")
            if (page > 1) append("&page=$page")
        }
        val jsonText = apiGet(url)
        val json = JSONObject(jsonText)
        val content = json.optJSONArray("content") ?: return emptyList()
        val result = ArrayList<Manga>(content.length())
        for (i in 0 until content.length()) {
            val obj = content.optJSONObject(i) ?: continue
            parseComic(obj)?.let { result.add(it) }
        }
        return result
    }

    private suspend fun categoryList(sort: String, page: Int, category: String?): List<Manga> {
        val c = (category ?: "0").trim().urlEncoded()
        val url = "$baseUrl/categories/filter?o=$sort&c=$c&page=$page"
        val json = JSONObject(apiGet(url))
        val content = json.optJSONArray("content") ?: return emptyList()
        val result = ArrayList<Manga>(content.length())
        for (i in 0 until content.length()) {
            val obj = content.optJSONObject(i) ?: continue
            parseComic(obj)?.let { result.add(it) }
        }
        return result
    }

    private fun parseComic(obj: JSONObject): Manga? {
        val id = obj.optString("id").ifEmpty { obj.optString("album_id") }.ifEmpty { return null }
        val title = obj.optString("name").ifEmpty { return null }
        val author = obj.optString("author")
        val desc = obj.optString("description")
        val tags = buildSet {
            obj.optJSONObject("category")?.optString("title")?.takeIf { it.isNotBlank() }?.let { add(it) }
            obj.optJSONObject("category_sub")?.optString("title")?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val cover = "${imageHost}/media/albums/${id}_3x4.jpg"
        return Manga(
            id = generateUid("jm:$id"),
            title = title,
            altTitles = emptySet(),
            url = "$baseUrl/album?id=$id",
            publicUrl = "$baseUrl/album?id=$id",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.map { MangaTag(it, it, source) }.toSet(),
            state = null,
            authors = if (author.isNotBlank()) setOf(author) else emptySet(),
            description = desc,
            chapters = null,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        ensureDomains()
        val id = manga.url.substringAfter("id=").substringBefore("&").ifBlank {
            manga.publicUrl.substringAfter("id=").substringBefore("&").ifBlank {
                manga.id.toString()
            }
        }
        val url = "$baseUrl/album?id=$id"
        val json = JSONObject(apiGet(url))
        val author = json.optJSONArray("author")?.optString(0).orEmpty()
        val desc = json.optString("description")
        val tags: Set<MangaTag> = json.optJSONArray("tags")?.let { arr ->
            val list = mutableListOf<MangaTag>()
            for (idx in 0 until arr.length()) {
                val t = arr.optString(idx)
                if (!t.isNullOrBlank()) list.add(MangaTag(t, t, source))
            }
            list.toSet()
        } ?: emptySet()
        val series = json.optJSONArray("series")
        val chapters: List<MangaChapter> = if (series != null && series.length() > 0) {
            val list = mutableListOf<MangaChapter>()
            for (idx in 0 until series.length()) {
                val obj = series.optJSONObject(idx) ?: continue
                val cid = obj.optString("id")
                if (cid.isNullOrBlank()) continue
                val sort = obj.optInt("sort", idx + 1)
                val name = obj.optString("name").ifBlank { "第${sort}話" }
                list.add(
                    MangaChapter(
                        id = generateUid("jm_ch:$id-$cid"),
                        title = name,
                        number = sort.toFloat(),
                        volume = 0,
                        url = "$baseUrl/chapter?id=$cid",
                        scanlator = null,
                        uploadDate = 0,
                        branch = null,
                        source = source,
                    )
                )
            }
            list
        } else {
            listOf(
                MangaChapter(
                    id = generateUid("jm_ch:$id-1"),
                    title = "第1話",
                    number = 1f,
                    volume = 0,
                    url = "$baseUrl/chapter?id=$id",
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            )
        }
        val cover = "${imageHost}/media/albums/${id}_3x4.jpg"
        return manga.copy(
            title = json.optString("name", manga.title).ifBlank { manga.title },
            authors = if (author.isNotBlank()) setOf(author) else manga.authors,
            description = desc.ifBlank { manga.description },
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        ensureDomains()
        val cid = chapter.url.substringAfter("id=").substringBefore("&")
        val url = "$baseUrl/chapter?id=$cid"
        val json = JSONObject(apiGet(url))
        val images = json.optJSONArray("images") ?: return emptyList()
        val pages = mutableListOf<MangaPage>()
        for (idx in 0 until images.length()) {
            val name = images.optString(idx)
            if (name.isNullOrBlank()) continue
            val imgUrl = "$imageHost/media/photos/$cid/$name"
            pages.add(
                MangaPage(
                    id = generateUid("jm_img:$cid-$idx"),
                    url = imgUrl,
                    preview = imgUrl,
                    source = source,
                )
            )
        }
        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        val path = req.url.encodedPath
        val isImage = path.contains("/media/photos/")
        return if (isImage) {
            val newReq = req.newBuilder()
                .headers(imageHeaders(req.header("Accept")))
                .build()
            val res = chain.proceed(newReq)
            val pathSegs = req.url.pathSegments
            val size = pathSegs.size
            val epId = pathSegs.getOrNull(size - 2)?.toLongOrNull()
            val filename = pathSegs.lastOrNull() ?: ""
            val num = computeScrambleSegments(epId, filename)
            return if (num <= 1) res else context.redrawImageResponse(res) { bmp ->
                descrambleImage(bmp, num)
            }
        } else {
            chain.proceed(req)
        }
    }

    private fun imageHeaders(accept: String?): Headers {
        return Headers.Builder()
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Connection", "keep-alive")
            .add("Referer", "https://localhost/")
            .add("Sec-Fetch-Dest", "image")
            .add("Sec-Fetch-Mode", "no-cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("Sec-Fetch-Storage-Access", "active")
            .add("User-Agent", userAgentKey.defaultValue)
            .add("X-Requested-With", packageName)
            .build()
    }

    private fun computeScrambleSegments(epId: Long?, filename: String): Int {
        if (epId == null) return 0
        val scrambleId = 220980
        val picName = filename.substringBeforeLast('.')
        return when {
            epId < scrambleId -> 0
            epId < 268850 -> 10
            epId > 421926 -> {
                val hash = md5Hex(epId.toString() + picName)
                val remainder = hash.last().code % 8
                remainder * 2 + 2
            }
            else -> {
                val hash = md5Hex(epId.toString() + picName)
                val remainder = hash.last().code % 10
                remainder * 2 + 2
            }
        }
    }

    private fun descrambleImage(image: org.skepsun.kototoro.parsers.bitmap.Bitmap, num: Int): org.skepsun.kototoro.parsers.bitmap.Bitmap {
        val blockSize = image.height / num
        val remainder = image.height % num
        val res = context.createBitmap(image.width, image.height)
        var y = 0
        for (i in num - 1 downTo 0) {
            val start = i * blockSize
            val end = start + blockSize + if (i != num - 1) 0 else remainder
            val height = end - start
            val srcRect = Rect(0, start, image.width, end)
            val dstRect = Rect(0, y, image.width, y + height)
            res.drawBitmap(image, srcRect, dstRect)
            y += height
        }
        return res
    }

    private suspend fun apiGet(url: String, retries: Int = 3): String {
        ensureDomains()
        var attempt = 0
        var delayMs = 800L + Random.nextLong(0, 400)
        var last: Exception? = null
        while (attempt < retries) {
            try {
                val now = (System.currentTimeMillis() / 1000).toString()
                val reqHeaders = apiHeaders(now)
                val resp = webClient.httpGet(url, reqHeaders)
                val headers = resp.headers
                val contentEncoding = headers["Content-Encoding"].orEmpty()
                val contentType = headers["Content-Type"].orEmpty()
                val rawBytes = resp.body.bytes()
                val body = rawBytes.toString(Charsets.UTF_8)
                val preview = body.take(500)
                val obj = runCatching { JSONObject(body) }.getOrElse { e ->
                    val hex = rawBytes.toHexPreview()
                    throw RuntimeException(
                        "JM JSON parse failed, enc=$contentEncoding, ctype=$contentType, hex=$hex, preview=${preview.toVisibleAscii()}",
                        e,
                    )
                }
                val status = obj.optInt("status", obj.optInt("code", 0))
                if (status != 200) throw RuntimeException("Invalid status: $status, preview=${preview.toVisibleAscii()}")
                val dataEnc = obj.optString("data")
                if (dataEnc.isNullOrEmpty()) throw RuntimeException("Empty data, preview=${preview.toVisibleAscii()}")
                return convertData(dataEnc, "$now$dataSecret")
            } catch (e: Exception) {
                last = e
                // rotate domain on failure
                if (apiDomains.size > 1) {
                    val nextIdx = (apiDomains.indexOf(activeDomain) + 1) % apiDomains.size
                    activeDomain = apiDomains[nextIdx]
                }
                delay(delayMs)
                delayMs = min(delayMs * 2, 5000)
                attempt++
            }
        }
        throw last ?: RuntimeException("apiGet failed for $url")
    }

    private fun apiHeaders(time: String): Headers {
        // JM expects the md5 hex string to be hex-encoded again (same as debug_jm_api.py)
        val tokenMd5Hex = md5Hex(time + apiKey)
        val token = hexEncode(tokenMd5Hex.toByteArray(Charsets.UTF_8))
        return headersBase.newBuilder()
            .add("Authorization", "Bearer")
            .add("Sec-Fetch-Storage-Access", "active")
            .add("token", token)
            .add("tokenparam", "$time,$jmVersion")
            .add("User-Agent", userAgentKey.defaultValue)
            .build()
    }

    private fun apiHeadersNoGzip(time: String): Headers {
        return apiHeaders(time).newBuilder()
            .add("Content-Encoding", "identity")
            .build()
    }

    private fun convertData(input: String, secret: String): String {
        val key = hexEncode(md5Bytes(secret)).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val cleaned = cleanBase64(input)
        val decoded = context.decodeBase64(cleaned)
        val decrypted = cipher.doFinal(decoded)
        val text = decrypted.toString(Charsets.UTF_8)
        var i = text.length - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '}' || c == ']') break
            i--
        }
        return text.substring(0, min(i + 1, text.length))
    }

    private fun md5Bytes(data: String): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data.toByteArray(Charsets.UTF_8))
    }

    private fun md5Hex(data: String): String = hexEncode(md5Bytes(data))

    private fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append((v ushr 4).toString(16))
            sb.append((v and 0x0F).toString(16))
        }
        return sb.toString()
    }

    private fun ByteArray.toHexPreview(limit: Int = 32): String {
        val take = min(size, limit)
        val sb = StringBuilder(take * 3)
        for (i in 0 until take) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02x", this[i]))
        }
        if (size > take) sb.append(" ...(${size} bytes)")
        return sb.toString()
    }

    private fun String.toVisibleAscii(): String = buildString {
        for (ch in this@toVisibleAscii) {
            append(
                if (ch.code in 32..126) ch else '?'
            )
        }
    }

    private fun cleanBase64(src: String): String {
        var s = src.trim()
        if (s.startsWith("\uFEFF")) { // BOM
            s = s.removePrefix("\uFEFF")
        }
        return s.replace("\\s+".toRegex(), "")
    }

    private suspend fun ensureDomains() {
        if (!domainsInitialized) {
            domainsInitialized = true
            runCatching {
                val url = "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt"
                val resp = webClient.httpGet(url, headersBase)
                val raw = resp.parseRaw()
                val decrypted = convertData(raw, "diosfjckwpqpdfjkvnqQjsik")
                val json = JSONObject(decrypted)
                val arr = json.optJSONArray("Server")
                if (arr != null && arr.length() > 0) {
                    val list = mutableListOf<String>()
                    val take = minOf(arr.length(), 4)
                    for (i in 0 until take) {
                        val s = arr.optString(i)
                        if (!s.isNullOrBlank()) list.add(s)
                    }
                    if (list.isNotEmpty()) {
                        apiDomains = mergeDomains(list)
                    }
                }
            }
        }
        applyConfiguredDomain()
        if (!imageHostInitialized) {
            imageHostInitialized = true
            runCatching { refreshImageHost() }
        }
    }

    private suspend fun refreshImageHost() {
        val res = apiGet("$baseUrl/setting?app_img_shunt=1&express=")
        runCatching {
            val json = JSONObject(res)
            val host = json.optString("img_host")
            if (!host.isNullOrBlank()) {
                imageHost = host
            }
        }
    }

    override val authUrl: String
        get() = "$baseUrl/login"

    override suspend fun isAuthorized(): Boolean {
        // Check cookies on all known domains, not just active domain
        val candidates = domainCandidates()
        for (domain in candidates) {
            if (context.cookieJar.getCookies(domain).any { it.name == "app_token" }) {
                activeDomain = domain
                return true
            }
        }
        return false
    }

    override suspend fun getUsername(): String {
        if (!isAuthorized()) throw AuthRequiredException(source)
        if (!lastLoginEmail.isNullOrBlank()) {
            return lastLoginEmail!!
        }
        val candidates = domainCandidates()
        for (domain in candidates) {
            val cookieEmail = context.cookieJar.getCookies(domain)
                .firstOrNull { it.name == "jm_email" }
                ?.value
            if (!cookieEmail.isNullOrBlank()) {
                lastLoginEmail = cookieEmail
                return cookieEmail
            }
        }
        return "User"
    }

    override suspend fun login(username: String, password: String): Boolean {
        ensureDomains()
        val body = mapOf(
            "username" to username,
            "password" to password,
        )

        val loginPaths = listOf("/login")
        val candidates = domainCandidates()
        val maxAttempts = minOf(candidates.size, 4)
        repeat(maxAttempts) { attempt ->
            for (domain in candidates) {
                activeDomain = domain
                for (path in loginPaths) {
                    val url = "$baseUrl$path"
                    val result = runCatching {
                        val time = (System.currentTimeMillis() / 1000).toString()
                        val raw = requestLogin(url, body)
                        val loginInfo = extractLoginInfo(raw, time)
                        val token = loginInfo.token
                        lastLoginEmail = loginInfo.email
                        if (!token.isNullOrBlank()) {
                            context.cookieJar.insertCookies(
                                domain,
                                "app_token=$token; Domain=$domain; Path=/",
                            )
                        }
                        val email = loginInfo.email
                        if (!email.isNullOrBlank()) {
                            val allDomains = domainCandidates()
                            for (emailDomain in allDomains) {
                                context.cookieJar.insertCookies(
                                    emailDomain,
                                    "jm_email=$email; Domain=$emailDomain; Path=/",
                                )
                            }
                        }
                        isAuthorized()
                    }.getOrDefault(false)
                    if (result) {
                        return true
                    }
                }
            }
            if (attempt == 0) {
                runCatching { refreshImageHost() }
            }
            if (attempt == 1) {
                runCatching {
                    domainsInitialized = false
                    ensureDomains()
                }
            }
        }
        return false
    }

    private data class LoginInfo(
        val token: String?,
        val email: String?,
    )

    private fun extractLoginInfo(raw: String, time: String): LoginInfo {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return LoginInfo(null, null)
        val code = root.optInt("code", root.optInt("status", -1))
        if (code != 200) {
            return LoginInfo(null, null)
        }
        val dataEnc = root.optString("data")
        if (dataEnc.isNullOrBlank()) {
            return LoginInfo(null, null)
        }
        val decrypted = runCatching { convertData(dataEnc, "$time$dataSecret") }.getOrNull() ?: return LoginInfo(null, null)
        val dataObj = runCatching { JSONObject(decrypted) }.getOrNull() ?: return LoginInfo(null, null)
        val directToken = dataObj.optString("app_token")
            .ifBlank { dataObj.optString("appToken") }
            .ifBlank { dataObj.optString("token") }
            .ifBlank { dataObj.optString("s") }
        val directEmail = dataObj.optString("email").ifBlank { null }
        if (directToken.isNotBlank()) {
            return LoginInfo(directToken, directEmail)
        }
        val nested = dataObj.optJSONObject("data")
        val nestedToken = nested?.optString("app_token")
            ?.ifBlank { nested.optString("appToken") }
            ?.ifBlank { nested.optString("token") }
            ?.ifBlank { nested.optString("s") }
            ?.takeIf { it.isNotBlank() }
        val nestedEmail = nested?.optString("email")?.ifBlank { null }
        return LoginInfo(nestedToken, directEmail ?: nestedEmail)
    }

    private suspend fun requestLogin(
        url: String,
        body: Map<String, String>,
    ): String {
        val headers = loginHeaders()
        val formBody = FormBody.Builder().apply {
            body.forEach { (key, value) ->
                addEncoded(key, value)
            }
        }.build()
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .post(formBody)
            .headers(headers)
            .tag(MangaSource::class.java, source)
            .tag(GZipOptions::class.java, GZipOptions(skip = true))
            .build()
        val response = context.httpClient.newCall(request).await()
        return response.use { resp ->
            val encoding = resp.header("Content-Encoding").orEmpty()
            if (encoding.contains("gzip", ignoreCase = true)) {
                val stream = resp.body?.byteStream() ?: return@use ""
                return@use GZIPInputStream(stream).use { String(it.readBytes(), Charsets.UTF_8) }
            }
            resp.parseRaw()
        }
    }

    private fun loginHeaders(): Headers = headersBase.newBuilder()
        .add("Accept-Encoding", "gzip, deflate, br, zstd")
        .build()

    private fun domainCandidates(): List<String> {
        val configured = config[configKeyDomain].trim()
        val result = LinkedHashSet<String>()
        if (configured.isNotBlank()) {
            result.add(configured)
        }
        result.addAll(apiDomains)
        return result.toList()
    }

    private fun mergeDomains(newDomains: List<String>): List<String> {
        val configured = config[configKeyDomain].trim()
        val result = LinkedHashSet<String>()
        if (configured.isNotBlank()) {
            result.add(configured)
        }
        result.addAll(newDomains)
        return result.toList()
    }

    private fun applyConfiguredDomain() {
        val configured = config[configKeyDomain].trim()
        if (configured.isNotBlank()) {
            if (!apiDomains.contains(configured)) {
                apiDomains = mergeDomains(apiDomains)
            }
            activeDomain = configured
        } else if (activeDomain !in apiDomains && apiDomains.isNotEmpty()) {
            activeDomain = apiDomains.first()
        }
    }

    override suspend fun fetchFavorites(): List<Manga> {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val result = mutableListOf<Manga>()
        val order = "mr" // 默认按更新排序
        var page = 1
        val pageSize = 20
        while (true) {
            val url = "$baseUrl/favorite?folder_id=0&page=$page&o=$order"
            val json = JSONObject(apiGet(url))
            val list = json.optJSONArray("list") ?: break
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                parseComic(obj)?.let { result.add(it) }
            }
            val total = json.optInt("total", result.size)
            if (page * pageSize >= total) break
            page++
        }
        return result
    }

    override suspend fun addFavorite(manga: Manga): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val aid = manga.url.substringAfter("id=").substringBefore("&").ifBlank { manga.url }
        val time = (System.currentTimeMillis() / 1000).toString()
        val headers = apiHeadersNoGzip(time)
        val resp = webClient.httpPost("$baseUrl/favorite".toHttpUrl(), mapOf("aid" to aid), headers)
        if (resp.code == 401) throw AuthRequiredException(source)
        return resp.isSuccessful
    }

    override suspend fun removeFavorite(manga: Manga): Boolean {
        // JM 使用同一接口切换收藏状态
        return addFavorite(manga)
    }
}
