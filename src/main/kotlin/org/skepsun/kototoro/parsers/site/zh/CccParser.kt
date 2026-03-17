@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.parseBytes
import java.security.MessageDigest
import java.util.EnumSet
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CCC追漫台（api.creative-comic.tw）
 * 图片加密，解密后返回 data URL
 */
@ContentSourceParser("CCC_", "CCC追漫台", "zh")
internal class CccParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.CCC_, pageSize = 20),
    ContentParserAuthProvider,
    ContentParserCredentialsAuthProvider {

    private val apiUrl = "https://api.creative-comic.tw"
    private var token: String? = null
    private var cachedUsername: String? = null
    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("www.creative-comic.tw")
    override val authUrl: String
        get() = "https://${config[configKeyDomain]}/zh/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    private val sortTags: List<ContentTag> = listOf(
        TagOption("updated_at", "最新", "sort"),
        TagOption("read_count", "閲覽", "sort"),
        TagOption("like_count", "推薦", "sort"),
        TagOption("collect_count", "收藏", "sort"),
    ).map { it.toTag(source) }

    private val categoryTags: List<ContentTag> = listOf(
        TagOption("", "全部", "type"),
        TagOption("2", "劇情", "type"),
        TagOption("6", "愛情", "type"),
        TagOption("5", "青春成長", "type"),
        TagOption("3", "幽默搞笑", "type"),
        TagOption("10", "歷史古裝", "type"),
        TagOption("7", "奇幻架空", "type"),
        TagOption("4", "溫馨療癒", "type"),
        TagOption("9", "冒險動作", "type"),
        TagOption("8", "恐怖驚悚", "type"),
        TagOption("12", "新感覺推薦", "type"),
        TagOption("11", "推理懸疑", "type"),
        TagOption("13", "活動", "type"),
    ).map { it.toTag(source) }

    private val serialTags: List<ContentTag> = listOf(
        TagOption("", "全部", "serial"),
        TagOption("2", "已完結", "serial"),
        TagOption("0", "連載中", "serial"),
    ).map { it.toTag(source) }

    private val updatedTags: List<ContentTag> = listOf(
        TagOption("", "全部", "updated"),
        TagOption("month", "本月", "updated"),
        TagOption("week", "本周", "updated"),
    ).map { it.toTag(source) }

    private val literatureTags: List<ContentTag> = listOf(
        TagOption("", "全部", "literature"),
        TagOption("1", "短篇", "literature"),
        TagOption("2", "中篇", "literature"),
        TagOption("3", "長篇", "literature"),
    ).map { it.toTag(source) }

    private val comicTypeTags: List<ContentTag> = listOf(
        TagOption("", "全部", "comic_type"),
        TagOption("3", "條漫", "comic_type"),
        TagOption("2", "格漫", "comic_type"),
    ).map { it.toTag(source) }

    private val publisherTags: List<ContentTag> = listOf(
        "44-MOJOIN",
        "37-目宿媒體股份有限公司",
        "4-大辣出版",
        "18-MarsCat火星貓科技",
        "2-CCC創作集",
        "23-海穹文化",
        "11-國立歷史博物館",
        "6-未來數位",
        "34-虎尾建國眷村再造協會",
        "24-鏡文學股份有限公司",
        "43-Taiwan Comic City",
        "42-聯經出版事業股份有限公司",
        "48-東立出版社有限公司",
        "9-留守番工作室",
        "16-獨步文化",
        "21-尖端媒體集團",
        "29-相之丘tōkhiu books",
        "7-威向文化",
        "54-白範出版工作室",
        "22-時報文化出版企業股份有限公司",
        "20-國立臺灣工藝研究發展中心",
        "17-獨立出版",
        "51-大寬文化工作室",
        "32-金繪國際有限公司",
        "47-前衛出版社",
        "36-奇異果文創",
        "14-綺影映畫",
        "53-彰化縣政府",
        "31-艾德萊娛樂",
        "8-特有生物研究保育中心",
        "39-聚場文化",
        "38-XPG",
        "52-陌上商行有限公司",
        "49-國際合製｜臺漫新視界",
        "40-KADOKAWA",
        "10-國立臺灣美術館",
        "26-金漫獎",
        "5-台灣東販",
        "45-國立國父紀念館",
        "35-國立臺灣歷史博物館",
        "15-蓋亞文化",
        "1-長鴻出版社",
        "19-柒拾陸號原子",
        "33-台灣角川",
        "28-一顆星工作室",
        "46-好人出版",
        "27-澄波藝術文化股份有限公司",
        "12-黑白文化",
        "13-慢工文化 Slowork Publishing",
        "30-經濟部智慧財產局",
        "50-Contents Lab. Blue TOKYO",
        "3-大塊文化",
        "25-目色出版",
        "41-文化內容策進院",
    ).map { it.split("-", limit = 2) }
        .map { (id, title) -> TagOption(id, title, "publisher").toTag(source) }

    private val rankTags: List<ContentTag> = listOf(
        TagOption("read", "人氣榜", "rank"),
        TagOption("buy", "銷售榜", "rank"),
        TagOption("donate", "斗內榜", "rank"),
        TagOption("collect", "收藏榜", "rank"),
    ).map { it.toTag(source) }

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = (sortTags + categoryTags + serialTags + updatedTags + literatureTags + comicTypeTags + publisherTags + rankTags).toSet()
        return ContentListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(
                ContentTagGroup("排序", sortTags.toSet()),
                ContentTagGroup("分類", categoryTags.toSet()),
                ContentTagGroup("連載狀態", serialTags.toSet()),
                ContentTagGroup("更新日期", updatedTags.toSet()),
                ContentTagGroup("作品篇幅", literatureTags.toSet()),
                ContentTagGroup("作品形式", comicTypeTags.toSet()),
                ContentTagGroup("出版社", publisherTags.toSet()),
                ContentTagGroup("排行榜", rankTags.toSet()),
            ),
            availableStates = emptySet(),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Accept", "application/json")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("device", "web_desktop")
        .apply {
            if (token.isNullOrEmpty()) {
                add("uuid", "null")
            } else {
                add("Authorization", "Bearer $token")
            }
        }
        .build()

    private suspend fun getApiHeaders(withAuth: Boolean = false): Headers {
        if (withAuth && token.isNullOrEmpty()) {
            refreshToken()
        }
        val builder = Headers.Builder()
            .add("User-Agent", UserAgents.CHROME_DESKTOP)
            .add("Accept", "application/json")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .add("device", "web_desktop")
        if (withAuth && token.isNullOrEmpty()) {
            refreshToken()
        }
        if (token.isNullOrEmpty()) {
            builder.add("uuid", "null")
        } else {
            builder.add("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private suspend fun refreshToken() {
        // No-op fallback to keep requests working even without cached token
    }

    override suspend fun login(username: String, password: String): Boolean {
        // 尝试使用密码模式（不含验证码），若失败则提示需要官方应用登录
        val headers = getApiHeaders(true)
        val body = JSONObject().apply {
            put("grant_type", "password")
            put("client_id", "2")
            put("client_secret", "9eAhsCX3VWtyqTmkUo5EEaoH4MNPxrn6ZRwse7tE")
            put("username", username)
            put("password", password)
        }
        val res = webClient.httpPost("$apiUrl/token", body)
        if (!res.isSuccessful) {
            throw ParseException("需要验证码，当前环境无法登录，请在官方客户端完成登录", res.request.url.toString())
        }
        val json = res.parseJsonObject()
        val access = json.optString("access_token")
        if (access.isNullOrEmpty()) {
            throw ParseException("登录失败，未返回 token", res.request.url.toString())
        }
        token = access
        cachedUsername = username
        return true
    }

    override suspend fun getUsername(): String {
        if (token.isNullOrEmpty()) {
            throw AuthRequiredException(source)
        }
        return cachedUsername ?: throw ParseException("未能获取用户名，请重新登录", authUrl)
    }

    override suspend fun isAuthorized(): Boolean = !token.isNullOrEmpty()

    suspend fun logout(): Boolean {
        token = null
        cachedUsername = null
        return true
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val selected = filter.tags.associateBy { it.key.substringBefore(':') }
        val type = selected["type"]?.key?.substringAfter(':').orEmpty()
        val rank = selected["rank"]?.key?.substringAfter(':')
        val sort = selected["sort"]?.key?.substringAfter(':')?.ifEmpty { null } ?: when (order) {
            SortOrder.POPULARITY -> "read_count"
            SortOrder.UPDATED -> "updated_at"
            else -> "updated_at"
        }
        val serial = selected["serial"]?.key?.substringAfter(':').orEmpty()
        val updated = selected["updated"]?.key?.substringAfter(':').orEmpty()
        val literature = selected["literature"]?.key?.substringAfter(':').orEmpty()
        val comicType = selected["comic_type"]?.key?.substringAfter(':').orEmpty()
        val publisher = selected["publisher"]?.key?.substringAfter(':').orEmpty()

        return if (rank != null) {
            val params = buildString {
                append("page=$page&rows_per_page=$pageSize&rank=$rank&class=2")
                if (type.isNotEmpty()) append("&type=$type")
            }
            parseComics("$apiUrl/rank?$params")
        } else {
            val params = mutableListOf(
                "page=$page",
                "rows_per_page=$pageSize",
                "class=2",
                "sort_by=$sort",
            )
            filter.query?.takeIf { it.isNotEmpty() }?.let { params.add("keyword=$it") }
            if (type.isNotEmpty()) params.add("type=$type")
            if (serial.isNotEmpty()) params.add("serial=$serial")
            if (updated.isNotEmpty()) params.add("updated_at=$updated")
            if (literature.isNotEmpty()) params.add("literature_form=$literature")
            if (comicType.isNotEmpty()) params.add("comic_type=$comicType")
            if (publisher.isNotEmpty()) params.add("publisher=$publisher")
            parseComics("$apiUrl/book?${params.joinToString("&")}")
        }
    }

    private suspend fun search(query: String, page: Int): List<Content> {
        val params = listOf(
            "page=$page",
            "rows_per_page=$pageSize",
            "keyword=${query}",
            "class=2",
            "sort_by=updated_at",
        )
        return parseComics("$apiUrl/book?${params.joinToString("&")}")
    }

    private suspend fun parseComics(url: String): List<Content> {
        val res = fetchWithFallback(url) ?: return emptyList()
        val root = res.parseJsonObject()
        if (root.optInt("code", 0) != 0) {
            return emptyList()
        }
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<Content>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = (item.optString("book_id").ifEmpty { item.optString("id") }).ifEmpty { continue }
            val title = item.optString("name")
            val cover = item.optString("image1").ifEmpty {
                item.optString("image2").ifEmpty { item.optString("image3") }
            }
            val tagSet = mutableSetOf<ContentTag>()
            val authorArr = item.optJSONArray("author") ?: org.json.JSONArray()
            for (j in 0 until authorArr.length()) {
                val name = (authorArr.optJSONObject(j)?.optString("name")).orEmpty()
                if (name.isNotEmpty()) tagSet.add(ContentTag(name, name, source))
            }
            val typeName = item.optJSONObject("type")?.optString("name").orEmpty()
            if (typeName.isNotEmpty()) tagSet.add(ContentTag(typeName, typeName, source))
            result.add(
                Content(
                    id = generateUid(id),
                    url = id,
                    publicUrl = "https://www.creative-comic.tw/zh/book/$id",
                    coverUrl = cover,
                    title = title,
                    altTitles = emptySet(),
                    rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                    contentRating = null,
                    tags = tagSet,
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            )
        }
        return result
    }

    private suspend fun fetchWithFallback(url: String): okhttp3.Response? {
        // Try with auth first if we have a token; fallback to no-auth on 401/5xx
        var res = runCatching { webClient.httpGet(url, getApiHeaders(token != null)) }.getOrNull()
        if (res == null) return null
        if (!res.isSuccessful && (res.code == 401 || res.code in 500..599)) {
            res.close()
            res = runCatching { webClient.httpGet(url, getApiHeaders(false)) }.getOrNull()
        }
        return res?.takeIf { it.isSuccessful }
    }

    override suspend fun getDetails(manga: Content): Content {
        val infoRes = webClient.httpGet("$apiUrl/book/${manga.url}/info", getApiHeaders(true))
        if (!infoRes.isSuccessful) return manga
        val data = infoRes.parseJsonObject().optJSONObject("data") ?: return manga
        val authors = mutableSetOf<String>().apply {
            val arr = data.optJSONArray("author") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val name = (arr.optJSONObject(i)?.optString("name")).orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }
        val tags = mutableSetOf<ContentTag>().apply {
            val arr = data.optJSONArray("tags") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val name = (arr.optJSONObject(i)?.optString("name")).orEmpty()
                if (name.isNotEmpty()) add(ContentTag(name, name, source))
            }
        }
        val chapters = loadChapters(manga.url.toString())
        val cover = data.optString("image1").ifEmpty {
            data.optString("image2").ifEmpty { data.optString("image3") }
        }
        val desc = data.optString("description", manga.description ?: "").ifEmpty { manga.description }
        return manga.copy(
            title = data.optString("name", manga.title).ifEmpty { manga.title },
            coverUrl = cover.ifEmpty { manga.coverUrl },
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            chapters = chapters,
            description = desc,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    private suspend fun loadChapters(bookId: String): List<ContentChapter> {
        val chapterRes = webClient.httpGet("$apiUrl/book/$bookId/chapter", getApiHeaders(true))
        if (!chapterRes.isSuccessful) return emptyList()
        val data = chapterRes.parseJsonObject().optJSONObject("data") ?: return emptyList()
        val chaptersArr = data.optJSONArray("chapters") ?: return emptyList()
        val result = mutableListOf<ContentChapter>()
        for (i in 0 until chaptersArr.length()) {
            val obj = chaptersArr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val vol = obj.optString("vol_name")
            val name = obj.optString("name")
            val title = buildString {
                if (!isFree(obj)) append("[付費]")
                append(vol)
                if (name.isNotEmpty()) append("-").append(name)
            }
            result.add(
                ContentChapter(
                    id = generateUid("$bookId-$id"),
                    url = obj.optString("id"),
                    title = title,
                    number = (i + 1).toFloat(),
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            )
        }
        return result
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val res = webClient.httpGet("$apiUrl/book/chapter/${chapter.url}", getApiHeaders(true))
        if (!res.isSuccessful) return emptyList()
        val data = res.parseJsonObject().optJSONObject("data") ?: return emptyList()
        val proportions = data.optJSONObject("chapter")?.optJSONArray("proportion") ?: return emptyList()
        val pages = mutableListOf<ContentPage>()
        for (i in 0 until proportions.length()) {
            val obj = proportions.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            pages.add(
                ContentPage(
                    id = generateUid("$id-$i"),
                    url = id,
                    preview = null,
                    source = source,
                )
            )
        }
        return pages
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        // 1) 获取 key
        val keyRes = webClient.httpGet("$apiUrl/book/chapter/image/${page.url}", getApiHeaders())
        if (!keyRes.isSuccessful) return page.url
        val keyData = keyRes.parseJsonObject().optJSONObject("data") ?: return page.url
        val encryptedKeyBase64 = keyData.optString("key")
        val token = "freeforccc2020reading"
        val sha = MessageDigest.getInstance("SHA-512").digest(token.toByteArray())
        val pageKey = sha.copyOfRange(0, 32)
        val pageIv = sha.copyOfRange(15, 31) // 16 bytes
        val decryptedKey = decryptAesCbc(encryptedKeyBase64.decodeBase64(), pageKey, pageIv)
        val keyIvStr = decryptedKey.decodeToString().trim()
        val keyParts = keyIvStr.split(":")
        if (keyParts.size != 2) return page.url
        val realKey = keyParts[0].hexToBytes()
        val realIv = keyParts[1].hexToBytes()

        // 2) 拉取图片密文并解密
        val imgUrl = "https://storage.googleapis.com/ccc-www/fs/chapter_content/encrypt/${page.url}/2"
        val imgResp = webClient.httpGet(imgUrl, getApiHeaders())
        if (!imgResp.isSuccessful) return page.url
        val decryptedImgBytes = decryptAesCbc(imgResp.parseBytes(), realKey, realIv)
        val base64Str = decryptedImgBytes.decodeToString()
        val payload = base64Str.substringAfter(",", base64Str).trim()
        val mime = base64Str.substringAfter("data:", "").substringBefore(";").ifEmpty { "image/jpeg" }
        return "data:$mime;base64,$payload"
    }

    private fun decryptAesCbc(encrypted: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    private fun String.decodeBase64(): ByteArray = try {
        java.util.Base64.getDecoder().decode(this)
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun ByteArray.decodeToString(): String = toString(Charsets.UTF_8)

    private fun String.hexToBytes(): ByteArray {
        val clean = replace(" ", "")
        val len = clean.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((clean[i].digitToInt(16) shl 4) + clean[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }

    private fun JSONObject.forEach(block: (String, Any?) -> Unit) {
        keys().forEachRemaining { k -> block(k, opt(k)) }
    }

    private fun isFree(obj: JSONObject): Boolean {
        if (obj.optBoolean("is_free")) return true
        val sales = obj.optInt("sales_plan", 0)
        val buy = obj.optBoolean("is_coin_buy") || obj.optBoolean("is_point_buy")
        val rent = obj.optBoolean("is_coin_rent") || obj.optBoolean("is_point_rent")
        val isBuy = obj.optBoolean("is_buy")
        val isRent = obj.optBoolean("is_rent")
        if (sales != 0 && buy && !isBuy && rent && !isRent) {
            return false
        }
        return true
    }

    private data class TagOption(val value: String, val title: String, val ns: String) {
        fun toTag(source: ContentSource): ContentTag = ContentTag(title, "$ns:$value", source)
    }
}
