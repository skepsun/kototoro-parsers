package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.ArrayList
import java.util.Base64
import java.util.EnumSet
import java.util.LinkedHashSet
import okhttp3.Cookie
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies

/**
 * Novelia 轻小说机翻机器人 - 网络小说
 * 
 * 注意：这是一个SPA网站，需要通过API获取数据
 * 当前实现为基础框架，需要根据实际API调整
 */
@ContentSourceParser("NOVELIA", "轻小说机翻机器人", "zh", type = ContentType.HENTAI_NOVEL)
internal class Novelia(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.NOVELIA, pageSize = 20),
    ContentParserAuthProvider,
    ContentParserCredentialsAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("n.novelia.cc")
    private val authDomain = "auth.novelia.cc"
    private var authTokenMemory: String? = null

    override val authUrl: String = "https://$authDomain/?app=n&theme=system"

    private fun normalizeToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var token = raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        token = token.trim('"')
        return token.ifBlank { null }
    }

    private fun getTokenFromCookies(): String? {
        val cookies = mutableListOf<Cookie>()
        cookies += context.cookieJar.getCookies(domain)
        if (authDomain != domain) {
            cookies += context.cookieJar.getCookies(authDomain)
        }
        return cookies.firstOrNull {
            val name = it.name.lowercase()
            name.contains("token") || name.contains("auth") || name.contains("jwt")
        }?.value
    }

    private fun saveToken(token: String) {
        authTokenMemory = token
        context.cookieJar.insertCookies(domain, "authorization=$token", "token=$token")
        context.cookieJar.insertCookies(authDomain, "authorization=$token", "token=$token")
    }

    override suspend fun isAuthorized(): Boolean {
        return normalizeToken(authTokenMemory ?: getTokenFromCookies()) != null
    }

    override suspend fun getUsername(): String {
        val token = normalizeToken(authTokenMemory ?: getTokenFromCookies()) ?: throw AuthRequiredException(source)
        return try {
            val parts = token.split(".")
            if (parts.size != 3) throw ParseException("Invalid JWT token format", authUrl)
            val payload = parts[1]
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = Base64.getDecoder().decode(padded)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            json.optString("sub", "").ifEmpty { json.optString("username", "").ifEmpty { "Novelia User" } }
        } catch (e: Exception) {
            throw ParseException("Failed to parse username from token: ${e.message}", authUrl)
        }
    }

    override suspend fun login(username: String, password: String): Boolean {
        val loginUrl = "https://$authDomain/api/v1/auth/login"
        val bodyJson = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("app", "n")
        }

        val conn = java.net.URL(loginUrl).openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false

            val jsonStr = bodyJson.toString()
            val bytes = jsonStr.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("User-Agent", "curl/8.7.1")

            conn.outputStream.use { os ->
                os.write(bytes)
                os.flush()
            }

            val status = conn.responseCode
            val responseBody = if (status in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }

            if (status !in 200..299) {
                throw ParseException("Login failed (HTTP $status): $responseBody", loginUrl)
            }

            val token = normalizeToken(responseBody.trim())
                ?: throw ParseException("Login response is empty", loginUrl)
            if (token.split(".").size != 3) {
                throw ParseException("Invalid token format: $token", loginUrl)
            }
            saveToken(token)
            return true
        } finally {
            conn.disconnect()
        }
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override fun getRequestHeaders(): Headers {
        val token = normalizeToken(authTokenMemory ?: getTokenFromCookies())
        return super.getRequestHeaders().newBuilder()
            .add("Accept", "application/json")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .apply {
                if (!token.isNullOrBlank()) {
                    add("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tagGroups = buildFilterTagGroups()
        val allTags = tagGroups.flatMapTo(LinkedHashSet()) { it.tags }
        return ContentListFilterOptions(
            availableTags = allTags,
            tagGroups = tagGroups,
        )
    }

    private fun buildFilterTagGroups(): List<ContentTagGroup> {
        val providerTags = linkedSetOf(
            ContentTag("来源: 全部", "provider:", source),
            ContentTag("来源: Syosetu", "provider:syosetu", source),
            ContentTag("来源: Kakuyomu", "provider:kakuyomu", source),
            ContentTag("来源: Hameln", "provider:hameln", source),
            ContentTag("来源: Pixiv", "provider:pixiv", source),
            ContentTag("来源: Novelup", "provider:novelup", source),
            ContentTag("来源: Alphapolis", "provider:alphapolis", source),
        )

        val typeTags = linkedSetOf(
            ContentTag("类型: 全部", "type:0", source),
            ContentTag("类型: 连载中", "type:1", source),
            ContentTag("类型: 短篇", "type:2", source),
            ContentTag("类型: 完结", "type:3", source),
        )

        val levelTags = linkedSetOf(
            ContentTag("等级: 全部", "level:0", source),
            ContentTag("等级: 一般向", "level:1", source),
            ContentTag("等级: R18", "level:2", source),
        )

        val translateTags = linkedSetOf(
            ContentTag("翻译: 全部", "translate:0", source),
            ContentTag("翻译: 已翻译", "translate:1", source),
            ContentTag("翻译: 未翻译", "translate:2", source),
        )

        return listOf(
            ContentTagGroup("来源", providerTags),
            ContentTagGroup("类型", typeTags),
            ContentTagGroup("等级", levelTags),
            ContentTagGroup("翻译", translateTags),
        )
    }

    /**
     * 获取小说列表
     * 
     * API端点：/api/novel
     * 参数：
     * - page: 页码（从0开始）
     * - pageSize: 每页数量
     * - query: 搜索关键词
     * - provider: 来源站点（kakuyomu,syosetu,novelup,hameln,pixiv,alphapolis）
     * - type: 类型（0=全部, 1=连载中, 2=短篇, 3=完结）
     * - level: 等级（0=全部，1=一般向，2=R18）
     * - translate: 翻译状态（0=全部, 1=已翻译, 2=未翻译）
     * - sort: 排序方式（0=最新更新，1=点击，2=相关）
     */
    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 构建API URL
        val apiPage = page - 1  // API使用0-based页码
        
        // 解析过滤器
        val providers = parseProviderFilter(filter)
        val typeFilter = parseTypeFilter(filter)
        val levelFilter = parseLevelFilter(filter)
        val translateFilter = parseTranslateFilter(filter)
        val searchQuery = filter.query.orEmpty()
        
        val sortOrder = when (order) {
            SortOrder.UPDATED -> 0
            SortOrder.POPULARITY -> 1
            SortOrder.RELEVANCE -> 2
            else -> 0
        }
        
        val url = buildString {
            append("https://").append(domain).append("/api/novel")
            append("?page=").append(apiPage)
            append("&pageSize=").append(pageSize)
            append("&query=").append(searchQuery.urlEncoded())
            append("&provider=").append(providers)
            append("&type=").append(typeFilter)
            append("&level=").append(levelFilter)
            append("&translate=").append(translateFilter)
            append("&sort=").append(sortOrder)
        }
        
        val requiresAuth = levelFilter == 0 || levelFilter == 2
        if (requiresAuth && normalizeToken(authTokenMemory ?: getTokenFromCookies()).isNullOrBlank()) {
            throw AuthRequiredException(source)
        }
        val resp = try {
            webClient.httpGet(url, getRequestHeaders())
        } catch (e: AuthRequiredException) {
            if (requiresAuth) throw e else null
        }
        val effectiveResp = resp ?: return emptyList()
        if (effectiveResp.code == 401 && requiresAuth) throw AuthRequiredException(source)
        if (!effectiveResp.isSuccessful) return emptyList()
        val json = effectiveResp.parseJson()
        return parseNovelList(json)
    }

    /**
     * 解析来源站点过滤器
     */
    private fun parseProviderFilter(filter: ContentListFilter): String {
        val providerTag = filter.tags.firstOrNull { it.key.startsWith("provider:") }
        return if (providerTag != null) {
            val provider = providerTag.key.substringAfter("provider:")
            if (provider.isEmpty()) {
                // 全部来源
                "kakuyomu,syosetu,novelup,hameln,pixiv,alphapolis"
            } else {
                provider
            }
        } else {
            // 默认全部来源
            "kakuyomu,syosetu,novelup,hameln,pixiv,alphapolis"
        }
    }

    /**
     * 解析类型过滤器
     */
    private fun parseTypeFilter(filter: ContentListFilter): Int {
        val typeTag = filter.tags.firstOrNull { it.key.startsWith("type:") }
        return if (typeTag != null) {
            typeTag.key.substringAfter("type:").toIntOrNull() ?: 0
        } else {
            0  // 默认全部
        }
    }

    /**
     * 解析等级过滤器
     */
    private fun parseLevelFilter(filter: ContentListFilter): Int {
        val levelTag = filter.tags.firstOrNull { it.key.startsWith("level:") }
        return if (levelTag != null) {
            levelTag.key.substringAfter("level:").toIntOrNull() ?: 0
        } else {
            1  // 默认一般向，避免未登录时必须授权
        }
    }

    /**
     * 解析翻译状态过滤器
     */
    private fun parseTranslateFilter(filter: ContentListFilter): Int {
        val translateTag = filter.tags.firstOrNull { it.key.startsWith("translate:") }
        return if (translateTag != null) {
            translateTag.key.substringAfter("translate:").toIntOrNull() ?: 0
        } else {
            0  // 默认全部
        }
    }

    /**
     * 解析小说列表JSON
     */
    private fun parseNovelList(json: JSONObject): List<Content> {
        val result = ArrayList<Content>()
        
        // API返回格式：{ items: [...], pageNumber: 500 }
        val items = json.optJSONArray("items") ?: return result
        
        for (i in 0 until items.length()) {
            val novel = items.optJSONObject(i) ?: continue
            
            val providerId = novel.optString("providerId", "")
            val novelId = novel.optString("novelId", "")
            if (providerId.isEmpty() || novelId.isEmpty()) continue
            
            val url = "/novel/$providerId/$novelId"
            
            // 标题（优先中文）
            val titleZh = novel.optString("titleZh", "")
            val titleJp = novel.optString("titleJp", "")
            val title = titleZh.ifEmpty { titleJp }
            
            if (title.isEmpty()) continue
            
            // 类型（连载中/短篇/完结等）
            val type = novel.optString("type", "")
            val state = when {
                type.contains("完结", ignoreCase = true) || type.contains("完成", ignoreCase = true) -> ContentState.FINISHED
                type.contains("连载", ignoreCase = true) -> ContentState.ONGOING
                type.contains("短篇", ignoreCase = true) -> ContentState.FINISHED
                else -> null
            }
            
            result += Content(
                id = generateUid(url),
                url = url,
                publicUrl = url.toAbsoluteUrl(domain),
                title = title,
                altTitles = if (titleZh.isNotEmpty() && titleJp.isNotEmpty()) setOf(titleJp) else emptySet(),
                coverUrl = null,  // 网络小说没有封面
                largeCoverUrl = null,
                authors = emptySet(),  // 列表API不包含作者信息
                tags = emptySet(),
                description = null,  // 列表API不包含简介
                rating = RATING_UNKNOWN,
                contentRating = null,
                state = state,
                source = source,
            )
        }
        
        return result
    }

    /**
     * 获取小说详情
     */
    override suspend fun getDetails(manga: Content): Content {
        // 从URL中提取provider和novelId
        // URL格式: /novel/hameln/232822
        val parts = manga.url.split("/")
        if (parts.size < 4) return manga
        
        val provider = parts[2]
        val novelId = parts[3]
        
        // 调用API获取详情
        val apiUrl = "https://$domain/api/novel/$provider/$novelId"
        
        return try {
            val json = webClient.httpGet(apiUrl, getRequestHeaders()).parseJson()
            
            // 检测可用的翻译版本
            val tocArray = json.optJSONArray("toc") ?: JSONArray()
            val availableBranches = detectAvailableBranches(provider, novelId, tocArray)
            
            parseNovelDetail(manga, json, provider, novelId, availableBranches)
        } catch (e: Exception) {
            // API不可用时返回原始manga
            manga
        }
    }

    /**
     * 解析小说详情JSON
     */
    private fun parseNovelDetail(manga: Content, json: JSONObject, provider: String, novelId: String, availableBranches: List<String>): Content {
        // 标题
        val titleZh = json.optString("titleZh", "")
        val titleJp = json.optString("titleJp", "")
        val title = titleZh.ifEmpty { titleJp.ifEmpty { manga.title } }
        
        // 作者
        val authorsArray = json.optJSONArray("authors")
        val authors = if (authorsArray != null && authorsArray.length() > 0) {
            val authorsList = mutableSetOf<String>()
            for (i in 0 until authorsArray.length()) {
                val authorObj = authorsArray.optJSONObject(i)
                val authorName = authorObj?.optString("name", "")
                if (!authorName.isNullOrEmpty()) {
                    authorsList.add(authorName)
                }
            }
            authorsList
        } else {
            manga.authors
        }
        
        // 简介
        val introductionZh = json.optString("introductionZh", "")
        val introductionJp = json.optString("introductionJp", "")
        val description = introductionZh.ifEmpty { introductionJp.ifEmpty { manga.description } }
        
        // 状态
        val type = json.optString("type", "")
        val state = when {
            type.contains("完结", ignoreCase = true) || type.contains("完成", ignoreCase = true) -> ContentState.FINISHED
            type.contains("连载", ignoreCase = true) -> ContentState.ONGOING
            else -> null
        }
        
        // 标签
        val keywordsArray = json.optJSONArray("keywords")
        val tags = if (keywordsArray != null && keywordsArray.length() > 0) {
            val tagsList = mutableSetOf<ContentTag>()
            for (i in 0 until keywordsArray.length()) {
                val keyword = keywordsArray.optString(i, "")
                if (keyword.isNotEmpty()) {
                    tagsList.add(ContentTag(keyword, keyword, source))
                }
            }
            tagsList
        } else {
            manga.tags
        }
        
        // 解析章节列表 (toc = Table of Contents)
        // Novelia提供多个翻译版本，使用branch来区分
        val tocArray = json.optJSONArray("toc") ?: JSONArray()
        val chapters = ArrayList<ContentChapter>()
        
        // 只为可用的翻译版本创建章节列表
        for (branch in availableBranches) {
            var chapterNumber = 1f
            
            for (i in 0 until tocArray.length()) {
                val tocItem = tocArray.optJSONObject(i) ?: continue
                
                // 获取章节ID（API使用chapterId字段）
                val chapterId = tocItem.optString("chapterId", "")
                
                // 只有有chapterId的才是真正的章节
                if (chapterId.isNotEmpty()) {
                    // 获取标题
                    val chapterTitleZh = tocItem.optString("titleZh", "")
                    val chapterTitleJp = tocItem.optString("titleJp", "")
                    val chapterTitle = chapterTitleZh.ifEmpty { chapterTitleJp }
                    
                    // 获取创建时间
                    val createAt = tocItem.optLong("createAt", 0) * 1000  // 转换为毫秒
                    
                    // 在URL中编码翻译版本
                    val chapterUrl = "/novel/$provider/$novelId/$chapterId?branch=$branch"
                    
                    chapters += ContentChapter(
                        id = generateUid(chapterUrl),
                        title = chapterTitle,
                        number = chapterNumber++,
                        volume = 0,
                        url = chapterUrl,
                        scanlator = null,
                        uploadDate = createAt,
                        branch = branch,  // 使用branch区分翻译版本
                        source = source,
                    )
                }
            }
        }
        
        return manga.copy(
            title = title,
            authors = authors,
            description = description,
            state = state,
            tags = tags,
            chapters = chapters,
        )
    }

    /**
     * 检测可用的翻译版本
     * 通过检查第一章来确定哪些翻译版本可用
     */
    private suspend fun detectAvailableBranches(provider: String, novelId: String, tocArray: JSONArray): List<String> {
        // 找到第一个有效章节
        var firstChapterId: String? = null
        for (i in 0 until tocArray.length()) {
            val tocItem = tocArray.optJSONObject(i) ?: continue
            val chapterId = tocItem.optString("chapterId", "")
            if (chapterId.isNotEmpty()) {
                firstChapterId = chapterId
                break
            }
        }
        
        // 如果没有章节，返回默认分组
        if (firstChapterId == null) {
            return listOf("GPT翻译", "日文原文")
        }
        
        // 请求第一章内容，检测可用的翻译版本
        return try {
            val apiUrl = "https://$domain/api/novel/$provider/$novelId/chapter/$firstChapterId"
            val json = webClient.httpGet(apiUrl, getRequestHeaders()).parseJson()
            
            val availableBranches = mutableListOf<String>()
            
            // 按优先级顺序检查各个翻译版本
            val branchChecks = listOf(
                "gptParagraphs" to "GPT翻译",
                "sakuraParagraphs" to "Sakura翻译",
                "baiduParagraphs" to "百度翻译",
                "youdaoParagraphs" to "有道翻译",
                "paragraphs" to "日文原文"
            )
            
            for ((field, branchName) in branchChecks) {
                val paras = json.optJSONArray(field)
                if (paras != null && paras.length() > 0) {
                    availableBranches.add(branchName)
                }
            }
            
            // 如果没有任何可用版本，至少返回日文原文
            if (availableBranches.isEmpty()) {
                availableBranches.add("日文原文")
            }
            
            availableBranches
        } catch (e: Exception) {
            // 如果检测失败，返回默认分组
            listOf("GPT翻译", "Sakura翻译", "日文原文")
        }
    }

    /**
     * 获取章节内容
     */
    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val content = getChapterContent(chapter) ?: return listOf(createErrorPage("内容为空"))
        val dataUrl = content.html.toDataUrl()
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = dataUrl,
                preview = null,
                source = source,
            )
        )
    }

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        // 从 URL 中提取信息
        // URL格式: /novel/hameln/389053/1?branch=GPT翻译
        val urlParts = chapter.url.split("?")
        val pathParts = urlParts[0].split("/")
        
        if (pathParts.size < 5) {
            return NovelChapterContent(
                html = buildErrorHtml("Invalid chapter URL"),
                images = emptyList()
            )
        }
        
        val provider = pathParts[2]
        val novelId = pathParts[3]
        val chapterId = pathParts[4]
        
        // 从 URL 参数中提取翻译版本
        val branch = chapter.branch ?: "GPT翻译"
        
        // 调用 API 获取章节内容
        val apiUrl = "https://$domain/api/novel/$provider/$novelId/chapter/$chapterId"
        
        return try {
            val json = webClient.httpGet(apiUrl, getRequestHeaders()).parseJson()
            
            val titleZh = json.optString("titleZh", chapter.title ?: "")
            val titleJp = json.optString("titleJp", chapter.title ?: "")
            
            // 根据 branch 选择对应的翻译版本
            val paragraphsArray = when (branch) {
                "GPT翻译" -> json.optJSONArray("gptParagraphs")
                "Sakura翻译" -> json.optJSONArray("sakuraParagraphs")
                "百度翻译" -> json.optJSONArray("baiduParagraphs")
                "有道翻译" -> json.optJSONArray("youdaoParagraphs")
                "日文原文" -> json.optJSONArray("paragraphs")
                else -> json.optJSONArray("gptParagraphs")
            }
            
            if (paragraphsArray == null || paragraphsArray.length() == 0) {
                return NovelChapterContent(
                    html = buildErrorHtml("章节内容为空或该翻译版本不可用"),
                    images = emptyList()
                )
            }
            
            // 将段落数组转换为文本
            val paragraphs = ArrayList<String>()
            for (i in 0 until paragraphsArray.length()) {
                val para = paragraphsArray.optString(i, "")
                if (para.isNotEmpty()) {
                    paragraphs.add(para)
                }
            }
            
            // 根据翻译版本选择标题
            val title = if (branch == "日文原文") titleJp else titleZh
            
            // 构建 HTML
            val html = buildChapterHtml(title, paragraphs, branch)
            NovelChapterContent(html = html, images = emptyList())
        } catch (e: Exception) {
            NovelChapterContent(
                html = buildErrorHtml("加载失败: ${e.message}"),
                images = emptyList()
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        return page.url
    }

    /**
     * 构建章节HTML（段落列表版本）
     */
    private fun buildChapterHtml(title: String, paragraphs: List<String>, branch: String = ""): String {
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n")
            append("<style>\n")
            append(
                "body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;padding:16px;margin:0;" +
                    "line-height:1.9;font-size:1.05rem;}\n" +
                    "h1{font-size:1.3rem;margin-bottom:0.5rem;}\n" +
                    ".branch-info{color:#666;font-size:0.9rem;margin-bottom:1rem;}\n" +
                    "p{margin:0 0 1rem;text-indent:2em;display:block;}\n" +
                    "p.no-indent{text-indent:0;}\n"
            )
            append("</style>\n</head>\n<body>\n")
            append("<h1>").append(title).append("</h1>\n")
            
            // 显示翻译版本信息
            if (branch.isNotEmpty()) {
                append("<div class=\"branch-info\">").append(branch).append("</div>\n")
            }
            
            // 输出段落
            for (para in paragraphs) {
                // 注意：不要使用trim()，因为段落可能只包含全角空格
                val content = para.replace("\r\n", "\n").replace("\r", "\n")
                
                // 检查是否为空行（只包含空白字符）
                if (content.isBlank()) {
                    // 空行
                    append("<p class=\"no-indent\">&nbsp;</p>\n")
                } else {
                    val trimmed = content.trim()
                    if (trimmed.startsWith("【") || trimmed.startsWith("「")) {
                        // 特殊格式（插图标记、对话）不缩进
                        append("<p class=\"no-indent\">").append(trimmed).append("</p>\n")
                    } else {
                        // 普通段落，首行缩进
                        append("<p>").append(trimmed).append("</p>\n")
                    }
                }
            }
            
            append("</body>\n</html>")
        }
    }

    private fun buildErrorHtml(message: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8"/>
        <style>body{font-family:sans-serif;padding:16px;}</style>
        </head><body><h1>错误</h1><p>$message</p></body></html>
    """.trimIndent()

    private fun createErrorPage(message: String): ContentPage {
        val html = buildErrorHtml(message)
        return ContentPage(
            id = generateUid(message),
            url = html.toDataUrl(),
            preview = null,
            source = source,
        )
    }

    /**
     * 将HTML转换为Data URL
     */
    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
