package org.skepsun.kototoro.parsers.site.zh

import androidx.collection.ArrayMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.random.Random
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.FavoritesSyncProvider
import org.skepsun.kototoro.parsers.MangaLoaderContext
// import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.map
import org.skepsun.kototoro.parsers.util.mapNotNullToSet
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.setHeader
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.oneOrThrowIfMany
import java.util.Locale
import java.util.EnumSet
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy

/**
 * Picacg API-based parser
 * Notes:
 * - Requires Authorization token in headers. This implementation looks for a cookie named `token` or `authorization` on domain `picaapi.picacomic.com`.
 * - Signature MUST be calculated: sha256(lower("path+time+nonce+method+apiKey"))
 * - Most list endpoints require token; on HTTP 401 it throws AuthRequiredException.
 */
// @Broken("Temporarily disabled per release 9.4.2 requirements")
@MangaSourceParser("PICACG", "Picacg", "zh", type = ContentType.HENTAI_MANGA)
internal class PicacgParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.PICACG, pageSize = 24), Interceptor, MangaParserAuthProvider, MangaParserCredentialsAuthProvider, FavoritesProvider, FavoritesSyncProvider {

    // 默认使用官方域名，并保留旧域作为备选，方便在设置中切换
    override val configKeyDomain = ConfigKey.Domain(
        "api.go2778.com",
        "picaapi.picacomic.com",
    )
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTOTORO)

    private val imageQualityKey = ConfigKey.PreferredImageServer(
        presetValues = linkedMapOf(
            "original" to "original",
            "medium" to "medium",
            "low" to "low",
        ),
        defaultValue = "original",
    )
    override val faviconDomain: String
        get() = "manhuabika.com"



    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(imageQualityKey)
        // app-channel 固定为 3，不暴露重复的图片质量设置项
    }

    private val apiKey = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    private val hmacSecret = "~d}\u0024Q7\u0024eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"

    private val baseUrl: String
        get() = "https://${domain}"

    // In-memory token, set on successful API login and used for Authorization headers.
    private var authTokenMemory: String? = null

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val staticTags = staticCategoryTags
        val dynamicTags = runCatching { fetchAvailableKeywords() }
            .onFailure { println("PICACG TAGS: fetch keywords failed ${it.message}") }
            .getOrDefault(emptySet())
        val allTags = (dynamicTags + staticTags)
        println("PICACG TAGS: static=${staticTags.size}, dynamic=${dynamicTags.size}, all=${allTags.size}")
        val groups = buildList<MangaTagGroup> {
            if (staticTags.isNotEmpty()) add(MangaTagGroup("分类", staticTags))
            if (dynamicTags.isNotEmpty()) add(MangaTagGroup("关键词", dynamicTags))
        }
        return MangaListFilterOptions(
            availableTags = allTags,
            availableLocales = setOf(Locale.CHINESE),
            tagGroups = groups,
        )
    }

    // For image requests ensure appropriate Accept and strip auth
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val acceptHdr = req.header("Accept").orEmpty()
        val lastSeg = req.url.pathSegments.lastOrNull().orEmpty()
        val isImageExt = lastSeg.endsWith(".jpg", true) || lastSeg.endsWith(".jpeg", true) ||
            lastSeg.endsWith(".png", true) || lastSeg.endsWith(".webp", true) ||
            lastSeg.endsWith(".avif", true) || lastSeg.endsWith(".gif", true)
        val isImageAccept = acceptHdr.contains("image/")
        return if (isImageExt || isImageAccept) {
            val newReq = req.newBuilder()
                .header("Accept", "image/avif,image/webp,image/apng,image/png,image/*,*/*;q=0.8")
                .removeHeader("Authorization")
                .build()
            val res = chain.proceed(newReq)
            val ct = res.headers["Content-Type"]?.lowercase()
            return if (ct != null && ct.startsWith("image/")) {
                res
            } else {
                val guessed = when {
                    lastSeg.endsWith(".jpg", true) || lastSeg.endsWith(".jpeg", true) -> "image/jpeg"
                    lastSeg.endsWith(".png", true) -> "image/png"
                    lastSeg.endsWith(".webp", true) -> "image/webp"
                    lastSeg.endsWith(".avif", true) -> "image/avif"
                    lastSeg.endsWith(".gif", true) -> "image/gif"
                    else -> "image/jpeg"
                }
                res.body.use { body ->
                    val media = guessed.toMediaTypeOrNull()
                    val newBody = body.bytes().toResponseBody(media)
                    res.newBuilder().setHeader("Content-Type", guessed).body(newBody).build()
                }
            }
        } else {
            chain.proceed(req)
        }
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        // 与 Python 登录脚本保持一致，部分服务端会校验 UA
        .add("user-agent", "okhttp/3.8.1")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Referer", "https://$domain/")
        .build()

    private fun token(): String? {
        // 优先使用内存中的 API 登录令牌，不依赖 Cookie
        val mem = authTokenMemory?.takeIf { it.isNotBlank() }
        if (!mem.isNullOrBlank()) return mem
        // 兼容：若外部通过 Cookie 预置 token，则作为回退（不强依赖）
        fun findToken(host: String): String? {
            val cookies = context.cookieJar.getCookies(host)
            return cookies.firstOrNull { c ->
                c.name.equals("authorization", true) || c.name.equals("token", true)
            }?.value?.takeIf { it.isNotBlank() }
        }
        return findToken(domain) ?: findToken("picaapi.picacomic.com")
    }

    private fun tokenFromHost(host: String): String? {
        val cookies = context.cookieJar.getCookies(host)
        val tokenCookie = cookies.firstOrNull { c ->
            c.name.equals("token", true) || c.name.equals("authorization", true)
        }
        return tokenCookie?.value?.takeIf { it.isNotBlank() }
    }

    // 从 Cookie、环境变量或测试文件中提取预置 token（用于离线兜底）
    private fun getPresetTokenOrNull(): String? {
        // 开关：允许通过环境变量完全关闭预置 token 兜底
        runCatching {
            val v = System.getenv("PICACG_DISABLE_PRESET_TOKEN")?.trim()?.lowercase(Locale.ROOT)
            if (v == "1" || v == "true" || v == "yes") {
                println("PICACG PRESET-TOKEN SWITCH: disabled via PICACG_DISABLE_PRESET_TOKEN")
                return null
            }
        }
        // 1) 先查 Cookie 中的 token/authorization 两种名称，优先当前默认域
        tokenFromHost(domain)?.let { if (it.isNotBlank()) return it }
        tokenFromHost("picaapi.picacomic.com")?.let { if (it.isNotBlank()) return it }
        // 2) 允许通过环境变量 PICACG_TOKEN 提供
        runCatching { System.getenv("PICACG_TOKEN")?.trim() }.getOrNull()?.let { env ->
            if (env.isNotBlank()) return env
        }
        // 3) 允许通过上层项目根目录的 picacg_token.txt 文件（与测试环境一致），可通过 PICACG_SKIP_TOKEN_FILE 跳过
        val skipFile = runCatching { System.getenv("PICACG_SKIP_TOKEN_FILE")?.trim()?.lowercase(Locale.ROOT) }.getOrNull()
        val allowFile = !(skipFile == "1" || skipFile == "true" || skipFile == "yes")
        if (allowFile) {
            runCatching {
                val f = java.io.File("../picacg_token.txt")
                if (f.exists()) f.readText().trim() else null
            }.getOrNull()?.let { fromFile ->
                if (fromFile.isNotBlank()) return fromFile
            }
        } else {
            runCatching { println("PICACG PRESET-TOKEN: skip file via PICACG_SKIP_TOKEN_FILE") }
        }
        return null
    }

    private fun hmacSha256Hex(secret: String, dataLower: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(dataLower.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            val hi = "0123456789abcdef"[v ushr 4]
            val lo = "0123456789abcdef"[v and 0x0F]
            sb.append(hi).append(lo)
        }
        return sb.toString()
    }

    private fun createSignature(path: String, nonce: String, time: String, method: String): String {
        // 与 picacg.js 保持一致：使用原始 path（不强制加前导斜杠）
        val data = (path + time + nonce + method + apiKey).lowercase(Locale.ROOT)
        runCatching {
            println("PICACG SIGN INPUT: data=$data, keyLen=${apiKey.length}, secretLen=${hmacSecret.length}, hmacSecret=${hmacSecret}")
        }
        return hmacSha256Hex(hmacSecret, data)
    }

    // 删除代理/PWA相关签名逻辑（仅保留官方 API 签名）

    private fun buildApiHeaders(
        method: String,
        path: String,
        includeAuthorization: Boolean = true,
        hostOverride: String? = null,
        suppressLangReferer: Boolean = false,
        minimalForLogin: Boolean = false,
        overrideUserAgent: String? = null,
        overrideAccept: String? = null,
        omitAuthorization: Boolean = false,
        omitHostHeader: Boolean = false,
        appChannelOverride: String? = null,
    ): Headers {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val time = (System.currentTimeMillis() / 1000).toString()
        val signature = createSignature(path, nonce, time, method.uppercase(Locale.ROOT))
        val tokenVal = token()
        val host = hostOverride ?: domain
        val hdr = Headers.Builder()
        hdr.add("api-key", apiKey)
        hdr.add("accept", overrideAccept ?: "application/vnd.picacomic.com.v1+json")
        if (!suppressLangReferer) {
            hdr.add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        hdr.add("app-channel", appChannelOverride ?: "3")
        hdr.add("Content-Type", "application/json; charset=UTF-8")
        hdr.add("time", time)
        hdr.add("nonce", nonce)
        hdr.add("app-version", "2.2.1.3.3.4")
        hdr.add("app-uuid", appUuid())
        if (!minimalForLogin) {
            hdr.add("image-quality", config[imageQualityKey] ?: "original")
        }
        hdr.add("app-platform", "android")
        hdr.add("app-build-version", "45")
        if (overrideUserAgent == null) {
            hdr.add("user-agent", "okhttp/3.8.1")
        } else if (overrideUserAgent.isNotEmpty()) {
            hdr.add("user-agent", overrideUserAgent)
        } // else: empty string means skip UA header entirely
        if (!suppressLangReferer) {
            hdr.add("Referer", "https://$host/")
        }
        if (!omitHostHeader) {
            hdr.add("Host", host)
        }
        hdr.add("version", "v1.4.1")
        hdr.add("signature", signature)
        // 与 Python 登录脚本保持一致：始终发送 authorization 头（登录为空字符串）
        runCatching { println("PICACG HDR AUTH: include=$includeAuthorization token_present=${tokenVal != null}") }
        val authValue = if (includeAuthorization) (tokenVal?.trim() ?: "") else ""
        if (!omitAuthorization) {
            hdr.add("authorization", authValue)
        }
        return hdr.build()
    }

    // 与登录脚本保持一致：优先使用环境变量或工作区文件中的设备 UUID
    private fun appUuid(): String {
        val env = System.getenv("PICACG_UUID")?.trim()?.takeIf { it.isNotEmpty() }
        if (env != null) return env
        val file1 = java.io.File("../picacg_app_uuid.txt").takeIf { it.exists() }
        val file2 = java.io.File("picacg_app_uuid.txt").takeIf { it.exists() }
        val fromFile = sequenceOf(file1, file2).mapNotNull { f ->
            runCatching { f?.readText()?.trim() }.getOrNull()
        }.firstOrNull { !it.isNullOrEmpty() }
        return fromFile ?: "defaultUuid"
    }

    // ===== Authorization provider implementation =====
    override val authUrl: String
        get() = "https://picaapi.picacomic.com/"

    override suspend fun isAuthorized(): Boolean {
        val t = authTokenMemory ?: token()
        return t?.isNotBlank() == true
    }



    override suspend fun getUsername(): String {
        val t = token()
        if (t.isNullOrBlank()) throw AuthRequiredException(source)
        val path = "users/profile"
        val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
        if (res.code == 401) throw AuthRequiredException(source)
        val root = res.parseJson()
        val user = root.optJSONObject("data")?.optJSONObject("user")
            ?: throw ParseException("Unexpected response", baseUrl)
        val name = user.optString("name").orEmpty()
        if (name.isNotBlank()) return name
        val email = user.optString("email").orEmpty()
        val fallback = email.substringBefore('@')
        if (fallback.isNotBlank()) return fallback
        throw ParseException("Cannot parse username", baseUrl)
    }

    // ===== Credentials-based API login =====
    override suspend fun login(username: String, password: String): Boolean {
        val path = "auth/sign-in" // 与官方客户端一致，不以 '/' 开头
        // 头部与 Python 测试脚本保持一致：登录时不携带 authorization
        val headers = buildApiHeaders(
            method = "POST",
            path = path,
            includeAuthorization = true, // 与 Python 一致：登录时发送空 authorization
            suppressLangReferer = runCatching {
                System.getenv("PICACG_SUPPRESS_LANG_REFERER")?.trim()?.lowercase(java.util.Locale.ROOT)
            }.getOrNull().let { v -> v == "1" || v == "true" || v == "yes" },  // 可通过环境开关抑制 Accept-Language/Referer 以完全对齐 Python
            minimalForLogin = true,      // 与 Python 一致：保留 image-quality
            overrideUserAgent = null,     // 与 Python 一致：使用默认 okhttp/3.8.1 UA
            omitAuthorization = false,    // 与 Python 一致：始终存在 authorization 头（为空）
            omitHostHeader = false,       // 与 Python 一致：显式 Host
            appChannelOverride = System.getenv("PICACG_APP_CHANNEL") ?: "3",
        )
        runCatching {
            println(
                "PICACG LOGIN DEBUG: path=$path, method=POST, time=${headers["time"]}, nonce=${headers["nonce"]}, signature=${headers["signature"]}"
            )
            println("PICACG LOGIN URL: $baseUrl/$path")
            println("PICACG LOGIN HEADERS BEGIN")
            headers.names().forEach { name ->
                val v = headers.values(name).joinToString(",")
                println("PICACG LOGIN HDR: $name=$v")
            }
            println("PICACG LOGIN HEADERS END")
        }
        // 登录体与 Python 脚本一致：统一使用 email 字段提交
        val body = JSONObject().apply {
            put("email", username)
            put("password", password)
        }
        runCatching {
            println(
                "PICACG LOGIN INPUT: user_blank=${username.isBlank()}, pass_blank=${password.isBlank()}, user_len=${username.length}, pass_len=${password.length}"
            )
            println("PICACG LOGIN BODY: email=$username password_len=${password.length}")
        }
        val http = this.webClient

        // 如果启用原生客户端（非 OkHttp），使用 HttpURLConnection 直接发起请求
        // val useNative = runCatching {
        //     val s1 = System.getenv("PICACG_HTTP_STACK")?.trim()?.lowercase(java.util.Locale.ROOT)
        //     val s2 = System.getenv("PICACG_USE_NATIVE_CLIENT")?.trim()?.lowercase(java.util.Locale.ROOT)
        //     (s1 == "native") || (s2 == "1" || s2 == "true" || s2 == "yes")
        // }.getOrDefault(false)
        val useNative = true

        if (useNative) {
            val url = "$baseUrl/$path"
            runCatching {
                println("PICACG LOGIN(NATIVE) URL: $url")
                println("PICACG LOGIN(NATIVE) HEADERS BEGIN")
            }
            headers.names().forEach { name ->
                val v = headers.values(name).joinToString(",")
                println("PICACG LOGIN(NATIVE) HDR: $name=$v")
            }
            println("PICACG LOGIN(NATIVE) HEADERS END")
            val nr = httpPostNative(url, headers, body)
            val status = nr.code
            val bodyText = nr.body
            runCatching {
                println("PICACG LOGIN(NATIVE) URL: $url")
                println("PICACG LOGIN(NATIVE) STATUS: $status")
                println("PICACG LOGIN(NATIVE) RESP PREVIEW: ${bodyText.take(300)}")
            }
            if (status !in 200..299) {
                // 尝试解析 message
                val msg = runCatching { org.json.JSONObject(bodyText).optString("message").orEmpty() }.getOrDefault("")
                if (status == 401) throw org.skepsun.kototoro.parsers.exception.ParseException("Wrong password", baseUrl)
                throw org.skepsun.kototoro.parsers.exception.ParseException(msg.ifBlank { "Login failed ($status)" }, baseUrl)
            }
            val json = runCatching { org.json.JSONObject(bodyText) }.getOrElse {
                throw org.skepsun.kototoro.parsers.exception.ParseException("Login response not JSON", baseUrl)
            }
            var token = json.optJSONObject("data")?.optString("token").orEmpty()
            if (token.isBlank()) {
                // 若响应未提供 token，尝试从 Cookie 中提取
                token = tokenFromHost(domain).orEmpty()
                if (token.isBlank()) token = tokenFromHost("picaapi.picacomic.com").orEmpty()
            }
            if (token.isBlank()) {
                val code = json.optInt("code")
                val err = json.optString("error")
                val msg = json.optString("message")
                println("PICACG LOGIN(NATIVE) JSON: code=$code, error=$err, message=$msg, token_blank=true")
                val msgLower = msg.lowercase(java.util.Locale.ROOT)
                val errLower = err.lowercase(java.util.Locale.ROOT)
                if (code == 401 || msgLower.contains("password") || errLower.contains("password")) {
                    throw org.skepsun.kototoro.parsers.exception.ParseException("Wrong password", baseUrl)
                }
                throw org.skepsun.kototoro.parsers.exception.ParseException(msg.ifBlank { "Login failed without token" }, baseUrl)
            }
            authTokenMemory = token
            context.cookieJar.insertCookies(domain, "authorization=$token")
            context.cookieJar.insertCookies(domain, "token=$token")
            if (!domain.equals("picaapi.picacomic.com", ignoreCase = true)) {
                context.cookieJar.insertCookies("picaapi.picacomic.com", "authorization=$token")
                context.cookieJar.insertCookies("picaapi.picacomic.com", "token=$token")
            }
            return true
        }

        val res = try {
            http.httpPost(
                url = "$baseUrl/$path".toHttpUrl(),
                body = body,
                extraHeaders = headers,
            )
        } catch (e: org.jsoup.HttpStatusException) {
            // 不进行任何重试或域名切换，直接转换为可读错误
            if (e.statusCode == 401) {
                throw ParseException("Wrong password", baseUrl)
            }
            val msg: String = e.message?.let { m ->
                val idx = m.indexOf("\"message\":")
                if (idx >= 0) m.substring(idx).take(200) else m
            } ?: ""
            throw ParseException(msg.ifBlank { "Login failed (${e.statusCode})" }, baseUrl)
        }

        if (res.code !in 200..299) {
            if (res.code == 401) {
                throw ParseException("Wrong password", baseUrl)
            }
            val msg = runCatching { res.parseJson().optString("message").orEmpty() }.getOrDefault("")
            throw ParseException(msg.ifBlank { "Login failed (${res.code})" }, baseUrl)
        }

        val json = res.parseJson()
        var token = json.optJSONObject("data")?.optString("token").orEmpty()

        if (token.isBlank()) {
            // 若响应未提供 token，尝试从 Cookie 中提取（不进行任何再次提交）
            token = tokenFromHost(domain).orEmpty()
            if (token.isBlank()) {
                token = tokenFromHost("picaapi.picacomic.com").orEmpty()
            }
        }

        if (token.isBlank()) {
            val code = json.optInt("code")
            val err = json.optString("error")
            val msg = json.optString("message")
            runCatching {
                println("PICACG LOGIN RESP JSON: code=$code, error=$err, message=$msg, token_blank=true")
            }
            val msgLower = msg.lowercase(Locale.ROOT)
            val errLower = err.lowercase(Locale.ROOT)
            if (code == 401 || msgLower.contains("password") || errLower.contains("password")) {
                throw ParseException("Wrong password", baseUrl)
            }
            throw ParseException(msg.ifBlank { "Login failed without token" }, baseUrl)
        }

        authTokenMemory = token
        context.cookieJar.insertCookies(domain, "authorization=$token")
        context.cookieJar.insertCookies(domain, "token=$token")
        if (!domain.equals("picaapi.picacomic.com", ignoreCase = true)) {
            context.cookieJar.insertCookies("picaapi.picacomic.com", "authorization=$token")
            context.cookieJar.insertCookies("picaapi.picacomic.com", "token=$token")
        }
        return true
    }

    override suspend fun fetchFavorites(): List<Manga> {
        val t = getAuthToken()
        if (t.isNullOrBlank()) throw AuthRequiredException(source)
        val results = mutableListOf<Manga>()
        var page = 1
        while (true) {
            val path = "users/favourite?page=$page&s="
            val resp = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
            if (resp.code == 401) throw AuthRequiredException(source)
            if (!resp.isSuccessful) break
            val json = resp.parseJson()
            val comics = json.optJSONObject("data")
                ?.optJSONObject("comics")
                ?: break
            val docs = comics.optJSONArray("docs") ?: JSONArray()
            if (docs.length() == 0) break
            for (i in 0 until docs.length()) {
                val c = docs.optJSONObject(i) ?: continue
                val cid = c.optString("_id").ifEmpty { c.optString("id") }
                if (cid.isEmpty()) continue
                val title = c.optString("title")
                val cover = c.optJSONObject("thumb")?.let { thumb ->
                    val server = thumb.optString("fileServer")
                    val pathImg = thumb.optString("path")
                    if (server.isNotEmpty() && pathImg.isNotEmpty()) "$server/static/$pathImg" else ""
                }.orEmpty()
                val tagsArr = c.optJSONArray("tags") ?: JSONArray()
                val tags = mutableSetOf<MangaTag>().apply {
                    for (ti in 0 until tagsArr.length()) {
                        val n = tagsArr.optString(ti)
                        if (n.isNotEmpty()) add(MangaTag(n, n, source))
                    }
                }
                val authorsArr = c.optJSONArray("author") ?: JSONArray()
                val authors = mutableSetOf<String>().apply {
                    for (ai in 0 until authorsArr.length()) {
                        val a = authorsArr.optString(ai)
                        if (a.isNotEmpty()) add(a)
                    }
                }
                results.add(
                    Manga(
                        id = generateUid(cid),
                        url = cid,
                        publicUrl = "https://$domain/comic/$cid",
                        coverUrl = cover,
                        title = title,
                        altTitles = emptySet(),
                        rating = RATING_UNKNOWN,
                        tags = tags,
                        authors = authors,
                        state = null,
                        source = source,
                        contentRating = ContentRating.ADULT,
                    )
                )
            }
            val pages = comics.optInt("pages", page)
            if (page >= pages) break
            page += 1
            delay(200)
        }
        return results
    }

    override suspend fun addFavorite(manga: Manga): Boolean {
        val t = getAuthToken()
        if (t.isNullOrBlank()) throw AuthRequiredException(source)
        val path = "comics/${manga.url}/favourite"
        val headers = buildApiHeaders("POST", path)
        val resp = webClient.httpPost("$baseUrl/$path".toHttpUrl(), JSONObject(), headers)
        if (resp.code == 401) throw AuthRequiredException(source)
        return resp.isSuccessful
    }

    override suspend fun removeFavorite(manga: Manga): Boolean {
        // Picacg 使用同一接口 toggle，传空体即可取消
        return addFavorite(manga)
    }

    private data class NativeResponse(val code: Int, val body: String, val headers: Map<String, String>)

    private fun httpPostNative(url: String, headers: Headers, bodyJson: JSONObject): NativeResponse {
    val conn = (java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection)
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.useCaches = false

    // 1. 补充 curl 风格的默认请求头（关键）
    val defaultHeaders = mutableMapOf(
        "User-Agent" to "curl/7.88.1", // 模拟 curl 的 UA
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Content-Type" to "application/json; charset=UTF-8" // 明确 JSON 格式
    )
    // 合并传入的 headers（传入的优先级更高，可覆盖默认值）
    val allHeaders = defaultHeaders + headers.toMap() // 假设 headers 可转 Map，若不可则手动遍历

    // 2. 设置所有头（包括默认头）
    allHeaders.forEach { (name, value) ->
        conn.setRequestProperty(name, value)
    }

    // 3. 配置超时（对齐 curl 默认，避免无限等待）
    conn.connectTimeout = 30000 // 30 秒连接超时
    conn.readTimeout = 30000 // 30 秒读取超时

    // 4. 写入请求体 + 补充 Content-Length
    val jsonStr = bodyJson.toString()
    val bytes = jsonStr.toByteArray(Charsets.UTF_8)
    conn.setRequestProperty("Content-Length", bytes.size.toString()) // 关键：添加内容长度
    conn.outputStream.use { os -> os.write(bytes) }

    // 5. 处理 Cookie（可选，若需要会话持久化，可添加 CookieHandler）
    CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))

    // 后续状态码、响应体处理不变...
    val status = runCatching { conn.responseCode }.getOrDefault(-1)
    val respBody = runCatching {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        stream?.use { s -> s.readBytes().toString(Charsets.UTF_8) } ?: ""
    }.getOrDefault("")
    val hdrs = mutableMapOf<String, String>()
    runCatching {
        conn.headerFields?.forEach { (k, v) ->
            if (k != null && v != null) hdrs[k] = v.joinToString(",")
        }
    }
    conn.disconnect()
    return NativeResponse(status, respBody, hdrs)
}

    // 登录请求是否携带 authorization 头的环境开关（已与 Python 对齐，默认禁用）
    private fun includeAuthOnLogin(): Boolean {
        val v = runCatching { System.getenv("PICACG_LOGIN_INCLUDE_AUTH")?.trim()?.lowercase(Locale.ROOT) }.getOrNull()
        return when (v) {
            "1", "true", "yes" -> true
            else -> false
        }
    }

    // 提供直接读取当前授权 token 的方法（不依赖 Cookie）
    fun getAuthToken(): String? = authTokenMemory ?: token()

    private fun sortParam(order: SortOrder): String = when (order) {
        SortOrder.NEWEST, SortOrder.RELEVANCE, SortOrder.ADDED, SortOrder.UPDATED -> "dd"
        SortOrder.NEWEST_ASC, SortOrder.ADDED_ASC, SortOrder.UPDATED_ASC -> "da"
        SortOrder.POPULARITY -> "ld"
        SortOrder.RATING -> "vd"
        else -> "dd"
    }

    private fun leaderboardTag(order: SortOrder): String? = when (order) {
        SortOrder.POPULARITY_TODAY -> "H24"
        SortOrder.POPULARITY_WEEK -> "D7"
        SortOrder.POPULARITY_MONTH -> "D30"
        else -> null
    }

    // ===== Static categories (Picacg category page in JS) - Updated based on availability test =====
    private val staticCategories: List<String> = listOf(
        // High availability categories (10k+ comics)
        "短篇",      // 98,758 comics
        "同人",      // 67,079 comics
        "全彩",      // 30,308 comics
        "生肉",      // 26,995 comics
        "長篇",      // 24,311 comics
        "純愛",      // 20,489 comics
        
        // Medium availability categories (5k-10k comics)
        "CG雜圖",    // 17,620 comics
        "非人類",    // 13,306 comics
        "耽美花園",  // 12,699 comics
        "強暴",      // 10,108 comics
        "Cosplay",   // 8,326 comics
        "NTR",       // 7,778 comics
        "人妻",      // 6,961 comics
        "Fate",      // 6,744 comics
        "妹妹系",    // 6,448 comics
        "姐姐系",    // 5,782 comics
        "單行本",    // 5,307 comics
        "後宮閃光",  // 5,192 comics
        "百合花園",  // 5,059 comics
        "艦隊收藏",  // 4,966 comics
        "扶他樂園",  // 4,893 comics
        "重口地帶",  // 4,648 comics
        "禁書目錄",  // 4,259 comics
        "偽娘哲學",  // 4,184 comics
        "英語 ENG",  // 3,973 comics
        "SM",        // 3,813 comics
        
        // Low availability categories (1k-3k comics)
        "東方",      // 3,449 comics
        "性轉換",    // 1,638 comics
        "足の恋",    // 1,466 comics
        "Love Live", // 1,463 comics
        "碧藍幻想",  // 1,124 comics
        "歐美",      // 1,111 comics
        "WEBTOON",   // 1,066 comics
        
        // Very low availability categories (<1k comics)
        "圓神領域",  // 547 comics
        "SAO 刀劍神域", // 455 comics
        "嗶咔漢化",  // 168 comics
        
        // Special categories (dynamic content)
        "大家都在看",  // 40 comics
        "大濕推薦",   // 20 comics
        "那年今天",   // 3 comics
        "官方都在看",  // 3 comics
    )

    private val staticCategoryTags: Set<MangaTag> = staticCategories.mapNotNullToSet { t ->
        t.takeIf { it.isNotBlank() }?.let { MangaTag(title = it, key = it.lowercase(Locale.ROOT), source = source) }
    }
    init {
        println("PICACG TAGS INIT: staticCategories=${staticCategories.size}, staticTags=${staticCategoryTags.size}")
    }

    private fun isStaticCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return staticCategories.any { it.equals(name, ignoreCase = false) }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val tagSelected = filter.tags.oneOrThrowIfMany()
        val query = tagSelected?.title ?: filter.query
        val lb = leaderboardTag(order)
        return when {
            // 分类标签：命中静态类别时按分类接口拉取
            (tagSelected != null && isStaticCategory(tagSelected.title)) -> {
                val cat = java.net.URLEncoder.encode(tagSelected.title, "UTF-8")
                val path = "comics?page=$page&c=$cat&s=${sortParam(order)}"
                val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
                if (res.code == 401) throw AuthRequiredException(source)
                val root = res.parseJson()
                val docs = root.optJSONObject("data")?.optJSONObject("comics")?.optJSONArray("docs")
                    ?: JSONArray()
                val out = mutableListOf<Manga>()
                for (i in 0 until docs.length()) {
                    val jo = docs.optJSONObject(i) ?: continue
                    out.add(parseManga(jo))
                }
                out
            }
            query?.isNotBlank() == true -> {
                val path = "comics/advanced-search?page=$page"
                val bodyJson = JSONObject().apply {
                    put("keyword", query)
                    put("sort", sortParam(order))
                    // 根据picacg.js的实现，动态标签搜索只需要keyword和sort参数
                    // 不需要添加categories字段，否则会导致搜索失败
                }
                val res = httpPostNative(
                    url = "$baseUrl/$path",
                    headers = buildApiHeaders("POST", path),
                    bodyJson = bodyJson,
                )
                
                if (res.code == 401) throw AuthRequiredException(source)
                val root = JSONObject(res.body as String)
                val docs = root.optJSONObject("data")?.optJSONObject("comics")?.optJSONArray("docs")
                    ?: JSONArray()
                val out = mutableListOf<Manga>()
                for (i in 0 until docs.length()) {
                    val jo = docs.optJSONObject(i) ?: continue
                    out.add(parseManga(jo))
                }
                out
            }
            lb != null -> {
                if (page > 1) return emptyList()
                val path = "comics/leaderboard?tt=$lb&ct=VC"
                val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
                if (res.code == 401) throw AuthRequiredException(source)
                val root = res.parseJson()
                val arr = root.optJSONObject("data")?.optJSONArray("comics") ?: JSONArray()
                val out = mutableListOf<Manga>()
                for (i in 0 until arr.length()) {
                    val jo = arr.optJSONObject(i) ?: continue
                    out.add(parseManga(jo))
                }
                out
            }
            else -> {
                val path = "comics?page=$page&s=${sortParam(order)}"
                val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
                if (res.code == 401) throw AuthRequiredException(source)
                val root = res.parseJson()
                val docs = root.optJSONObject("data")?.optJSONObject("comics")?.optJSONArray("docs")
                    ?: JSONArray()
                val out = mutableListOf<Manga>()
                for (i in 0 until docs.length()) {
                    val jo = docs.optJSONObject(i) ?: continue
                    out.add(parseManga(jo))
                }
                out
            }
        }
    }

    // 删除网页模式兜底与 manhuabika.com 抓取逻辑

    private fun parseManga(jo: JSONObject): Manga {
        val idStr = jo.optString("_id")
        val id = generateUid(idStr)
        val title = jo.optString("title")
        val author = jo.optString("author")
        val thumb = jo.optJSONObject("thumb")
        val cover = thumb?.optString("fileServer").orEmpty().let { f ->
            val p = thumb?.optString("path").orEmpty()
            if (f.isNotBlank() && p.isNotBlank()) "$f/static/$p" else null
        }
        val tagsArr = jo.optJSONArray("tags") ?: JSONArray()
        val catsArr = jo.optJSONArray("categories") ?: JSONArray()
        val tagSet = ArrayMap<String, MangaTag>()
        tagsArr.forEachString { t ->
            if (t.isNotBlank()) tagSet[t] = MangaTag(title = t, key = t.lowercase(Locale.ROOT), source = source)
        }
        catsArr.forEachString { t ->
            if (t.isNotBlank()) tagSet[t] = MangaTag(title = t, key = t.lowercase(Locale.ROOT), source = source)
        }
        val rating = guessContentRating(tagSet.keys) ?: ContentRating.ADULT
        return Manga(
            id = id,
            title = title,
            altTitles = emptySet(),
            url = idStr,
            publicUrl = "$baseUrl/comics/$idStr",
            rating = RATING_UNKNOWN,
            contentRating = rating,
            coverUrl = cover,
            tags = tagSet.values.toSet(),
            state = null,
            authors = if (author.isNotBlank()) setOf(author) else emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        var idStr = manga.url
        if (idStr.startsWith("http://") || idStr.startsWith("https://")) {
            // 兼容从公开链接传入的情况：尝试从 URL 获取漫画 ID
            runCatching {
                val u = idStr.toHttpUrl()
                idStr = u.queryParameter("id")
                    ?: u.queryParameter("cid")
                    ?: u.pathSegments.lastOrNull().orEmpty()
            }.getOrElse { /* ignore */ }
        }
        val path = "comics/$idStr"
        val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
        if (res.code == 401) throw AuthRequiredException(source)
        if (res.code !in 200..299) throw ParseException("Picacg API error: ${res.code}", baseUrl)
        val root = res.parseJson()
        val info = root.optJSONObject("data")?.optJSONObject("comic") ?: throw ParseException(
            "Unexpected response",
            baseUrl,
        )
        val title = info.optString("title")
        val author = info.optString("author")
        val desc = info.optString("description")
        val thumb = info.optJSONObject("thumb")
        val cover = thumb?.optString("fileServer").orEmpty().let { f ->
            val p = thumb?.optString("path").orEmpty()
            if (f.isNotBlank() && p.isNotBlank()) "$f/static/$p" else null
        }
        val catsArr = info.optJSONArray("categories") ?: JSONArray()
        val tagsArr = info.optJSONArray("tags") ?: JSONArray()
        val tagSet = ArrayMap<String, MangaTag>()
        catsArr.forEachString { t -> if (t.isNotBlank()) tagSet[t] = MangaTag(t, t.lowercase(Locale.ROOT), source) }
        tagsArr.forEachString { t -> if (t.isNotBlank()) tagSet[t] = MangaTag(t, t.lowercase(Locale.ROOT), source) }
        val rating = guessContentRating(tagSet.keys) ?: manga.contentRating ?: ContentRating.ADULT

        // Load chapters (eps)
        val chapters = coroutineScope {
            val all = mutableListOf<MangaChapter>()
            var i = 1
            while (true) {
                val epPath = "comics/$idStr/eps?page=$i"
                val epRes = webClient.httpGet("$baseUrl/$epPath", buildApiHeaders("GET", epPath))
                if (epRes.code == 401) throw AuthRequiredException(source)
                val epRoot = epRes.parseJson()
                val eps = epRoot.optJSONObject("data")?.optJSONObject("eps")?.optJSONArray("docs") ?: JSONArray()
                if (eps.length() == 0) break
                for (idx in 0 until eps.length()) {
                    val e = eps.getJSONObject(idx)
                    val order = e.optInt("order")
                    val titleEp = e.optString("title").ifBlank { order.toString() }
                    val chId = generateUid("$idStr:$order")
                    all.add(
                        MangaChapter(
                            id = chId,
                            title = titleEp,
                            number = order.toFloat(),
                            volume = 0,
                            url = "$idStr/$order",
                            scanlator = null,
                            uploadDate = 0L,
                            branch = null,
                            source = source,
                        )
                    )
                }
                val pages = epRoot.optJSONObject("data")?.optJSONObject("eps")?.optInt("pages") ?: i
                if (i >= pages) break
                i++
            }
            all.sortBy { it.title }
            all
        }

        return manga.copy(
            title = title,
            authors = if (author.isNotBlank()) setOf(author) else emptySet(),
            description = desc,
            coverUrl = cover ?: manga.coverUrl,
            tags = tagSet.values.toSet(),
            chapters = chapters,
            contentRating = rating,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val idStr = chapter.url.substringBefore('/')
        val order = chapter.url.substringAfter('/').toIntOrNull() ?: 1
        val pages = mutableListOf<MangaPage>()
        var i = 1
        while (true) {
            val path = "comics/$idStr/order/$order/pages?page=$i"
            val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
            if (res.code == 401) throw AuthRequiredException(source)
            val root = res.parseJson()
            val docs = root.optJSONObject("data")?.optJSONObject("pages")?.optJSONArray("docs") ?: JSONArray()
            for (idx in 0 until docs.length()) {
                val p = docs.getJSONObject(idx)
                val media = p.optJSONObject("media")
                val fileServer = media?.optString("fileServer").orEmpty()
                val pathMedia = media?.optString("path").orEmpty()
                val img = if (fileServer.isNotBlank() && pathMedia.isNotBlank()) "$fileServer/static/$pathMedia" else continue
                pages.add(
                    MangaPage(
                        id = generateUid("$idStr:$order:$i:$idx"),
                        url = img,
                        preview = null,
                        source = source,
                    )
                )
            }
            val totalPages = root.optJSONObject("data")?.optJSONObject("pages")?.optInt("pages") ?: i
            if (i >= totalPages) break
            i++
        }
        return pages
    }

    private fun guessContentRating(tagNames: Collection<String>): ContentRating? {
        if (tagNames.isEmpty()) return null
        val lower = tagNames.map { it.lowercase(Locale.ROOT) }
        val adultKeys = setOf("r18", "18+", "紳士", "绅士", "工口", "h漫", "本子", "成人", "限制", "情色", "里番", "nsfw", "smut")
        return if (lower.any { s -> adultKeys.any { key -> s.contains(key) } }) ContentRating.ADULT else null
    }

    // Helpers for JSON arrays of strings
    private inline fun JSONArray.forEachString(block: (String) -> Unit) {
        for (i in 0 until this.length()) {
            val s = this.optString(i)
            if (s != null) block(s)
        }
    }
 
   private suspend fun fetchAvailableKeywords(): Set<MangaTag> {
    val path = "keywords"
    val res = webClient.httpGet("$baseUrl/$path", buildApiHeaders("GET", path))
    val root = res.parseJson()
    val arr: JSONArray = when {
        root.optJSONArray("keywords") != null -> root.optJSONArray("keywords")
        root.optJSONObject("data")?.optJSONArray("keywords") != null -> root.optJSONObject("data")!!.optJSONArray("keywords")
        root.optJSONArray("keys") != null -> root.optJSONArray("keys")
        root.optJSONObject("data")?.optJSONArray("keys") != null -> root.optJSONObject("data")!!.optJSONArray("keys")
        root.optJSONArray("data") != null -> root.optJSONArray("data")
        else -> JSONArray()
    }
    val out = mutableSetOf<MangaTag>()
    for (i in 0 until arr.length()) {
        val kw = arr.optString(i).orEmpty()
        if (kw.isNotBlank()) {
            out.add(MangaTag(title = kw, key = kw.lowercase(Locale.ROOT), source = source))
        }
    }
    // 确保“最近更新”在列表中（作为默认选项），即使接口未返回
    if (out.none { it.title == "最近更新" }) {
        out.add(MangaTag(title = "最近更新", key = "最近更新", source = source))
    }
    return out
}
}
