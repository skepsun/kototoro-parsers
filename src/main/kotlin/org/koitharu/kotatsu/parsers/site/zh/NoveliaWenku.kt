package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.ArrayList
import java.util.EnumSet

/**
 * Novelia文库小说 - 支持EPUB下载
 * 
 * 下载API：
 * - 端点：/api/wenku/{novelId}/file/{volumeId}
 * - 参数：mode, translationsMode, translations, filename
 * - 认证：需要登录Cookie
 * 
 * 功能：
 * 1. 列表浏览（需要登录）
 * 2. 小说详情（需要登录）
 * 3. EPUB下载（需要登录）
 * 
 * 注意：
 * - 所有API调用都需要有效的登录会话
 * - 下载URL已实现，但需要用户先在WebView中登录
 * - 支持中日对照和纯中文两种模式
 * - 默认使用Sakura翻译
 */
@MangaSourceParser("NOVELIA_WENKU", "轻小说机翻(文库)", "zh", type = ContentType.NOVEL)
internal class NoveliaWenku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.NOVELIA_WENKU, pageSize = 24), 
    MangaParserAuthProvider, 
    MangaParserCredentialsAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("n.novelia.cc")
    
    // 登录域名（独立的认证服务器）
    private val authDomain = "auth.novelia.cc"
    
    // 内存中的JWT token
    private var authTokenMemory: String? = null

    override fun getRequestHeaders(): Headers {
        val builder = super.getRequestHeaders().newBuilder()
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .add("Accept", "application/json")
        
        // 优先使用内存中的API登录token
        val token = authTokenMemory ?: getTokenFromCookies()
        
        if (token != null) {
            builder.add("Authorization", "Bearer $token")
        }
        
        return builder.build()
    }
    
    /**
     * 从Cookie中获取token（兼容旧的WebView登录方式）
     */
    private fun getTokenFromCookies(): String? {
        val cookies = context.cookieJar.getCookies(domain)
        return cookies.firstOrNull { 
            it.name.lowercase().contains("token") || 
            it.name.lowercase().contains("jwt") ||
            it.name.lowercase().contains("auth")
        }?.value
    }

    /**
     * 登录URL - 指向认证服务器
     */
    override val authUrl: String = "https://$authDomain/?app=n&theme=system"

    /**
     * 检查是否已登录
     */
    override suspend fun isAuthorized(): Boolean {
        val token = authTokenMemory ?: getTokenFromCookies()
        return token?.isNotBlank() == true
    }

    /**
     * 获取登录用户名
     * 从JWT token的payload中解析用户名
     */
    override suspend fun getUsername(): String {
        val token = authTokenMemory ?: getTokenFromCookies()
        if (token.isNullOrBlank()) {
            throw AuthRequiredException(source)
        }
        
        // 解析JWT token获取用户名
        return try {
            // JWT格式: header.payload.signature
            val parts = token.split(".")
            if (parts.size != 3) {
                throw ParseException("Invalid JWT token format", authUrl)
            }
            
            // 解码payload (Base64)
            val payload = parts[1]
            val paddedPayload = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = java.util.Base64.getDecoder().decode(paddedPayload)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            
            // 从payload中提取用户名
            json.optString("sub", "").ifEmpty {
                json.optString("username", "").ifEmpty {
                    "Novelia User"
                }
            }
        } catch (e: Exception) {
            throw ParseException("Failed to parse username from token: ${e.message}", authUrl)
        }
    }
    
    /**
     * API登录实现
     * 
     * 登录端点: https://auth.novelia.cc/api/v1/auth/login
     * 请求方法: POST
     * 请求体: {"username": "xxx", "password": "xxx", "app": "n"}
     * 响应: 直接返回JWT token字符串（不是JSON）
     */
    override suspend fun login(username: String, password: String): Boolean {
        val loginUrl = "https://$authDomain/api/v1/auth/login"
        
        // 构建登录请求体
        val bodyJson = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("app", "n")  // n = novelia wenku
        }
        
        // 使用原生HTTP请求（类似PicacgParser）
        val conn = java.net.URL(loginUrl).openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            
            // 写入请求体（必须在设置头之前准备好）
            val jsonStr = bodyJson.toString()
            val bytes = jsonStr.toByteArray(Charsets.UTF_8)
            
            // 设置请求头（简化，只保留必需的）
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("User-Agent", "curl/8.7.1")  // 使用curl的UA，已验证可用
            
            // 写入请求体
            conn.outputStream.use { os -> 
                os.write(bytes)
                os.flush()
            }
            
            // 获取响应
            val status = conn.responseCode
            val responseBody = if (status in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }
            
            // 检查响应状态
            if (status !in 200..299) {
                throw ParseException("Login failed (HTTP $status): $responseBody", loginUrl)
            }
            
            // 响应直接是JWT token字符串（不是JSON）
            val token = responseBody.trim()
            
            if (token.isBlank()) {
                throw ParseException("Login response is empty", loginUrl)
            }
            
            // 验证token格式（JWT应该有3个部分）
            if (token.split(".").size != 3) {
                throw ParseException("Invalid token format: $token", loginUrl)
            }
            
            // 保存token到内存
            authTokenMemory = token
            
            // 同时保存到Cookie（用于持久化）
            context.cookieJar.insertCookies(domain, "authorization=$token")
            context.cookieJar.insertCookies(domain, "token=$token")
            
            return true
            
        } finally {
            conn.disconnect()
        }
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = buildFilterTags(),
        )
    }

    private fun buildFilterTags(): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        
        // 等级过滤
        tags += MangaTag("等级: 轻小说", "level:0", source)
        tags += MangaTag("等级: 轻文学", "level:1", source)
        tags += MangaTag("等级: 文学", "level:2", source)
        tags += MangaTag("等级: 非小说", "level:3", source)
        tags += MangaTag("等级: R18男性向", "level:4", source)
        tags += MangaTag("等级: R18女性向", "level:5", source)
        
        return tags
    }

    /**
     * 获取文库小说列表
     * API: GET /api/wenku?page={page}&pageSize={pageSize}&query={query}&level={level}
     * 
     * 注意：需要登录认证，R18内容（level=4,5）需要额外的授权头
     */
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val apiPage = page - 1  // API使用0-based页码
        
        // 解析等级过滤
        val levelFilter = filter.tags.firstOrNull { it.key.startsWith("level:") }
            ?.key?.substringAfter(":")?.toIntOrNull() ?: 0
        
        val apiUrl = buildString {
            append("https://$domain/api/wenku")
            append("?page=$apiPage")
            append("&pageSize=$pageSize")
            if (!filter.query.isNullOrBlank()) {
                append("&query=${filter.query.urlEncoded()}")
            }
            append("&level=$levelFilter")
        }
        
        // Cookie会自动从CookieJar发送
        // 如果未登录，API会返回401/403错误
        val json = webClient.httpGet(apiUrl).parseJson()
        
        return parseNovelList(json)
    }

    /**
     * 解析小说列表
     */
    private fun parseNovelList(json: JSONObject): List<Manga> {
        val result = ArrayList<Manga>()
        val items = json.optJSONArray("items") ?: return result
        
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            
            val id = item.optString("id", "")
            if (id.isEmpty()) continue
            
            val title = item.optString("titleZh", item.optString("title", ""))
            if (title.isEmpty()) continue
            
            val cover = item.optString("cover", "")
            
            result += Manga(
                id = generateUid("/wenku/$id"),
                url = "/wenku/$id",
                publicUrl = "https://$domain/wenku/$id",
                title = title,
                altTitles = emptySet(),
                coverUrl = cover.ifEmpty { null },
                largeCoverUrl = cover.ifEmpty { null },
                authors = emptySet(),
                tags = emptySet(),
                description = null,
                rating = RATING_UNKNOWN,
                contentRating = null,
                state = null,
                source = source,
            )
        }
        
        return result
    }

    /**
     * 获取小说详情
     * API: GET /api/wenku/{id}
     */
    override suspend fun getDetails(manga: Manga): Manga {
        val novelId = manga.url.substringAfterLast("/")
        val apiUrl = "https://$domain/api/wenku/$novelId"
        
        val json = try {
            webClient.httpGet(apiUrl).parseJson()
        } catch (e: Exception) {
            // 如果API调用失败，返回原manga
            return manga
        }
        
        return parseNovelDetail(manga, json, novelId)
    }

    /**
     * 解析小说详情
     */
    private fun parseNovelDetail(manga: Manga, json: JSONObject, novelId: String): Manga {
        val title = json.optString("titleZh", json.optString("title", manga.title))
        val cover = json.optString("cover", "")
        
        // 作者
        val authorsArray = json.optJSONArray("authors")
        val authors = if (authorsArray != null) {
            (0 until authorsArray.length()).mapNotNull { authorsArray.optString(it) }.toSet()
        } else {
            emptySet()
        }
        
        // 简介
        val introduction = json.optString("introduction", "")
        
        // 出版社
        val publisher = json.optString("publisher", "")
        
        // 等级
        val level = json.optString("level", "")
        
        // 关键词作为标签
        val keywordsArray = json.optJSONArray("keywords")
        val tags = if (keywordsArray != null) {
            (0 until keywordsArray.length()).mapNotNull { 
                val keyword = keywordsArray.optString(it)
                if (keyword.isNotEmpty()) MangaTag(keyword, keyword, source) else null
            }.toSet()
        } else {
            emptySet()
        }
        
        // 生成章节列表
        // 注意：文库小说的"章节"实际上是EPUB卷
        val chapters = ArrayList<MangaChapter>()
        
        // novelId从参数传入，确保不为空
        
        // 中文卷（纯中文）- 默认显示
        val volumesZh = json.optJSONArray("volumeZh")
        if (volumesZh != null) {
            for (i in 0 until volumesZh.length()) {
                val volumeId = volumesZh.optString(i, "")
                if (volumeId.isEmpty()) continue
                
                // 构建下载URL（纯中文版本）
                val epubDownloadUrl = buildEpubDownloadUrl(novelId, volumeId, true)
                
                // 调试日志
                println("NoveliaWenku: Building chapter URL (ZH) - novelId=$novelId, volumeId=$volumeId")
                
                chapters += MangaChapter(
                    id = generateUid("/wenku/$novelId/${volumeId.urlEncoded()}/zh"),
                    title = "$volumeId [EPUB]",  // 简化标题，不显示"(中文)"
                    number = (i + 1).toFloat(),
                    volume = 0,  // 使用volume=0，与中日对照版本区分
                    url = "/wenku/$novelId/${volumeId.urlEncoded()}/zh",
                    scanlator = "EPUB下载",
                    uploadDate = 0,
                    branch = null,  // 默认分支，不显示branch标签
                    source = source,
                )
            }
        }
        
        // 日文卷（中日对照）- 可选显示
        val volumesJp = json.optJSONArray("volumeJp")
        if (volumesJp != null) {
            for (i in 0 until volumesJp.length()) {
                val vol = volumesJp.optJSONObject(i) ?: continue
                val volumeId = vol.optString("volumeId", "")
                if (volumeId.isEmpty()) continue
                
                val total = vol.optInt("total", 0)
                val youdao = vol.optInt("youdao", 0)
                val gpt = vol.optInt("gpt", 0)
                val sakura = vol.optInt("sakura", 0)
                
                // 章节标题显示翻译进度
                val translationInfo = buildString {
                    if (sakura > 0) append("Sakura:$sakura ")
                    if (gpt > 0) append("GPT:$gpt ")
                    if (youdao > 0) append("有道:$youdao ")
                }.trim()
                
                val chapterTitle = if (translationInfo.isNotEmpty()) {
                    "$volumeId ($translationInfo) [EPUB]"
                } else {
                    "$volumeId (日文原文) [EPUB]"
                }
                
                // 构建下载URL（中日对照版本）
                val epubDownloadUrl = buildEpubDownloadUrl(novelId, volumeId, false)
                
                // 调试日志
                println("NoveliaWenku: Building chapter URL - novelId=$novelId, volumeId=$volumeId")
                
                chapters += MangaChapter(
                    id = generateUid("/wenku/$novelId/${volumeId.urlEncoded()}"),
                    title = chapterTitle,
                    number = (i + 1).toFloat(),
                    volume = 1,  // 使用volume=1，与纯中文版本区分
                    url = "/wenku/$novelId/${volumeId.urlEncoded()}",
                    scanlator = "EPUB下载",
                    uploadDate = 0,
                    branch = "中日对照",  // 使用branch区分
                    source = source,
                )
            }
        }
        
        val description = buildString {
            if (introduction.isNotEmpty()) {
                append(introduction)
                append("\n\n")
            }
            if (publisher.isNotEmpty()) {
                append("出版社: $publisher\n")
            }
            if (level.isNotEmpty()) {
                append("等级: $level\n")
            }
        }.trim()
        
        return manga.copy(
            title = title,
            coverUrl = cover.ifEmpty { manga.coverUrl },
            largeCoverUrl = cover.ifEmpty { manga.largeCoverUrl },
            authors = authors,
            tags = tags,
            description = description.ifEmpty { manga.description },
            chapters = chapters,
        )
    }

    /**
     * 获取章节内容（EPUB）
     * 
     * 文库小说的"章节"实际上是EPUB文件
     * 
     * 下载API格式：
     * /api/wenku/{novelId}/file/{volumeId}?mode={mode}&translationsMode={mode}&filename={filename}&translations={trans}
     * 
     * 参数说明：
     * - mode: 语言模式 (jp/zh/zh-jp/jp-zh)
     * - translationsMode: 翻译模式 (priority/parallel)
     * - translations: 翻译源 (sakura/gpt/youdao/baidu)
     * - filename: 生成的文件名
     * 
     * 注意：需要登录认证才能下载
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/").filter { it.isNotEmpty() }
        
        println("========================================")
        println("NoveliaWenku.getPages CALLED")
        println("URL: ${chapter.url}")
        println("Source: ${chapter.source}")
        println("Parts: $parts")
        println("========================================")
        
        // 检查是否是本地EPUB章节（已下载的）- NEW ARCHITECTURE
        // 新架构使用epub://协议：epub://{manga_id}/chapter/{index}
        if (chapter.url.startsWith("epub://")) {
            println("========================================")
            println("NoveliaWenku.getPages: LOCAL EPUB DETECTED (NEW ARCHITECTURE)")
            println("URL: ${chapter.url}")
            println("Returning empty list - content will be loaded by NovelContentLoader")
            println("========================================")
            // 本地EPUB章节由NovelContentLoader处理
            // 返回空列表，避免使用漫画阅读器
            return emptyList()
        }
        
        // 向后兼容：检查旧格式的本地EPUB章节
        if (chapter.url.startsWith("file://") && chapter.url.contains("#chapter/")) {
            println("========================================")
            println("NoveliaWenku.getPages: LEGACY EPUB FORMAT DETECTED")
            println("URL: ${chapter.url}")
            println("Please re-download this manga to use the new EPUB architecture")
            println("========================================")
            // 旧格式不再支持，返回空列表
            return emptyList()
        }
        
        if (parts.size < 3) {
            println("NoveliaWenku.getPages: Invalid URL format")
            return emptyList()
        }
        
        val novelId = parts[1]
        val volumeIdEncoded = parts[2]
        val isZh = parts.size > 3 && parts[3] == "zh"
        
        // URL解码volumeId（因为在构建章节URL时进行了编码）
        val volumeId = volumeIdEncoded.urlDecode()
        
        println("NoveliaWenku.getPages: volumeIdEncoded=$volumeIdEncoded, volumeId=$volumeId")
        
        // 构建EPUB下载URL
        val epubUrl = buildEpubDownloadUrl(novelId, volumeId, isZh)
        
        println("NoveliaWenku.getPages: Returning EPUB page for download")
        
        // 返回EPUB标记页面（用于下载）
        return listOf(
            MangaPage(
                id = generateUid(chapter.url),
                url = epubUrl,
                preview = "EPUB",
                source = source,
            ),
        )
    }
    
    /**
     * 检查章节是否可以在线阅读
     * EPUB章节不支持在线阅读，只能下载
     */
    fun isReadableOnline(chapter: MangaChapter): Boolean {
        // EPUB章节不支持在线阅读
        return false
    }

    /**
     * 构建EPUB下载URL
     * 
     * 根据调试发现的API格式构建下载链接
     * 
     * 重要：volumeId必须包含.epub后缀，否则API会返回400/500错误
     */
    private fun buildEpubDownloadUrl(novelId: String, volumeId: String, isZh: Boolean): String {
        // 确保volumeId包含.epub后缀
        val volumeIdWithEpub = if (volumeId.endsWith(".epub", ignoreCase = true)) {
            volumeId
        } else {
            "$volumeId.epub"
        }
        
        // 根据是否是中文版本选择不同的参数
        val mode = "zh" // 强制使用中文
        val translationsMode = "priority"  // 优先模式
        val translations = "sakura"  // 默认使用Sakura翻译
        
        // 生成文件名：{mode}.{translationPrefix}{translationCode}.{volumeId}
        // Y = priority (优先), B = parallel (并列)
        // 翻译代码首字母：s(sakura), g(gpt), y(youdao), b(baidu)
        val translationPrefix = if (translationsMode == "priority") "Y" else "B"
        val translationCode = when (translations) {
            "sakura" -> "s"
            "gpt" -> "g"
            "youdao" -> "y"
            "baidu" -> "b"
            else -> "s"
        }
        
        // 注意：filename中的volumeId必须包含.epub后缀
        val filename = "$mode.$translationPrefix$translationCode.$volumeIdWithEpub"
        
        // 构建完整的下载URL
        // 注意：服务器不接受URL编码的volumeId，必须使用原始字符
        return buildString {
            append("https://$domain/api/wenku/$novelId/file/$volumeIdWithEpub")
            append("?mode=$mode")
            append("&translationsMode=$translationsMode")
            append("&filename=${filename.urlEncoded()}")
            append("&translations=$translations")
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        // 直接返回EPUB下载URL
        return page.url
    }
}
