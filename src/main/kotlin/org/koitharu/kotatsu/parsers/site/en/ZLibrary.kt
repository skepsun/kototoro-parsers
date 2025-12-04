package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Z-Library - 全球最大的电子书图书馆
 * 
 * 网站: https://z-library.sk/
 * 
 * 功能特点:
 * - 支持多语言搜索（所有语言）
 * - 支持 EPUB、PDF、MOBI 等多种格式
 * - 需要登录才能访问和下载
 * - 支持按年份、语言、格式筛选
 * 
 * API 端点:
 * - 搜索: /s/{query}?page={page}
 * - 详情: /book/{id}
 * - 下载: /dl/{id} (需要登录)
 * 
 * 认证:
 * - 需要 SingleLogin 账号
 * - 登录后获取 remix_userid 和 remix_userkey cookies
 * 
 * 实现状态:
 * - ✅ 搜索功能
 * - ✅ 书籍详情
 * - ✅ EPUB 下载链接
 * - ✅ 登录认证
 * - ✅ 多语言支持
 * - ✅ 格式筛选
 */
@MangaSourceParser("ZLIBRARY", "Z-Library", "en", type = ContentType.NOVEL)
internal class ZLibrary(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ZLIBRARY, pageSize = 50),
    MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("zh.z-library.sk")

    override val authUrl: String
        get() = "https://$domain/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.NEWEST,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .add("Referer", "https://$domain/")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    override suspend fun isAuthorized(): Boolean {
        val cookies = context.cookieJar.getCookies(domain)
        return cookies.any { it.name == "remix_userid" } && 
               cookies.any { it.name == "remix_userkey" }
    }

    override suspend fun getUsername(): String {
        val cookies = context.cookieJar.getCookies(domain)
        val userId = cookies.firstOrNull { it.name == "remix_userid" }?.value
        return if (userId != null) "User #$userId" else "Unknown"
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = buildCategoryTags() + buildLanguageTags(),
            availableContentTypes = EnumSet.of(ContentType.NOVEL),
        )
    }

    private fun buildCategoryTags(): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        
        // 主要分类
        tags += MangaTag("� 最Computer Science (计算机)", "cat:173", source)
        tags += MangaTag("📚 Biology (生物学)", "cat:94", source)
        tags += MangaTag("📖 Literature (文学)", "cat:191", source)
        tags += MangaTag("� Sciengce (科学)", "cat:199", source)
        tags += MangaTag("� Histotry (历史)", "cat:201", source)
        tags += MangaTag("🧠 Psychology (心理学)", "cat:203", source)
        tags += MangaTag("� Butsiness (商业)", "cat:205", source)
        tags += MangaTag("⚖️ Law (法律)", "cat:207", source)
        tags += MangaTag("🏥 Medicine (医学)", "cat:209", source)
        tags += MangaTag("🎨 Art (艺术)", "cat:189", source)
        
        return tags
    }

    private fun buildLanguageTags(): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        
        // 主要语言
        tags += MangaTag("English", "lang:english", source)
        tags += MangaTag("中文", "lang:chinese", source)
        tags += MangaTag("日本語", "lang:japanese", source)
        tags += MangaTag("한국어", "lang:korean", source)
        tags += MangaTag("Русский", "lang:russian", source)
        tags += MangaTag("Español", "lang:spanish", source)
        tags += MangaTag("Français", "lang:french", source)
        tags += MangaTag("Deutsch", "lang:german", source)
        tags += MangaTag("Italiano", "lang:italian", source)
        tags += MangaTag("Português", "lang:portuguese", source)
        tags += MangaTag("العربية", "lang:arabic", source)
        tags += MangaTag("Türkçe", "lang:turkish", source)
        tags += MangaTag("Polski", "lang:polish", source)
        tags += MangaTag("Nederlands", "lang:dutch", source)
        tags += MangaTag("Tiếng Việt", "lang:vietnamese", source)
        
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // 支持搜索和浏览两种模式

        val url = buildSearchUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        
        return parseBookList(doc)
    }

    private fun buildSearchUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val base = StringBuilder("https://").append(domain)
        
        // 检查是否有分类标签
        val categoryTag = filter.tags.firstOrNull { it.key.startsWith("cat:") }
        
        if (categoryTag != null) {
            // 分类浏览模式
            val catKey = categoryTag.key.substringAfter("cat:")
            base.append("/category/").append(catKey)
            
            // 添加页码
            if (page > 1) {
                base.append("?page=").append(page)
            }
        } else if (!filter.query.isNullOrBlank()) {
            // 搜索模式
            base.append("/s/").append(filter.query.urlEncoded())
            base.append("?")
            
            // 添加语言筛选
            val languages = filter.tags.filter { it.key.startsWith("lang:") }
            for (lang in languages) {
                val langCode = lang.key.substringAfter("lang:")
                base.append("languages%5B%5D=").append(langCode).append("&")
            }
            
            // 自动添加 EPUB 格式筛选
            base.append("extensions%5B%5D=EPUB&")
            
            // 添加排序
            when (order) {
                SortOrder.NEWEST -> base.append("order=year&")
                SortOrder.UPDATED -> base.append("order=date&")
                SortOrder.POPULARITY -> base.append("order=popular&")
                else -> {} // RELEVANCE 是默认排序
            }
            
            // 添加页码
            if (page > 1) {
                base.append("page=").append(page)
            }
        } else {
            // 默认显示计算机科学分类
            base.append("/category/173")
            if (page > 1) {
                base.append("?page=").append(page)
            }
        }
        
        return base.toString().trimEnd('&', '?')
    }

    private fun parseBookList(doc: Document): List<Manga> {
        val items = ArrayList<Manga>()
        val seen = LinkedHashSet<String>()
        
        // 选择器: z-bookcard 元素
        val bookItems = doc.select("z-bookcard")
        
        for (item in bookItems) {
            val bookId = item.attr("id")
            if (bookId.isBlank() || !seen.add(bookId)) continue
            
            val bookUrl = item.attr("href")
            if (bookUrl.isBlank()) continue
            
            val title = item.selectFirst("div[slot=title]")?.text()?.trim() ?: continue
            
            val coverImg = item.selectFirst("img")
            val coverUrl = coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: ""
            
            // 处理作者 - 过滤非作者信息
            val authorText = item.selectFirst("div[slot=author]")?.text()?.trim() ?: ""
            val authors = if (authorText.isNotEmpty()) {
                filterAuthors(authorText)
            } else {
                emptySet()
            }
            
            val year = item.attr("year")
            val language = item.attr("language")
            val extension = item.attr("extension")
            val filesize = item.attr("filesize")
            val rating = item.attr("rating")
            val publisher = item.attr("publisher")
            
            // 构建描述
            val descParts = mutableListOf<String>()
            if (authors.isNotEmpty()) descParts.add("Author: ${authors.joinToString(", ")}")
            if (publisher.isNotBlank()) descParts.add("Publisher: $publisher")
            if (year.isNotBlank()) descParts.add("Year: $year")
            if (language.isNotBlank()) descParts.add("Language: $language")
            if (extension.isNotBlank()) descParts.add("Format: $extension")
            if (filesize.isNotBlank()) descParts.add("Size: $filesize")
            if (rating.isNotBlank() && rating != "0.0") descParts.add("Rating: $rating")
            
            // 处理封面 URL - 如果是占位图，使用空字符串
            val finalCoverUrl = if (coverUrl.contains("cover-not-exists")) "" else coverUrl
            
            items.add(
                Manga(
                    id = generateUid(bookId),
                    url = bookUrl,
                    publicUrl = "https://$domain$bookUrl",
                    title = title,
                    coverUrl = finalCoverUrl,
                    altTitles = emptySet(),
                    rating = parseRating(rating),
                    contentRating = ContentRating.SAFE,
                    tags = emptySet(),
                    state = null,
                    authors = authors,  // 已经是Set<String>
                    largeCoverUrl = null,
                    description = descParts.joinToString("\n"),
                    chapters = null,
                    source = source,
                ),
            )
        }
        
        return items
    }

    private fun parseRating(ratingStr: String): Float {
        if (ratingStr.isBlank()) return RATING_UNKNOWN
        
        // 格式: "5.0/5.0" 或 "4.5/5"
        val parts = ratingStr.split("/")
        if (parts.size != 2) return RATING_UNKNOWN
        
        return try {
            val rating = parts[0].trim().toFloat()
            val max = parts[1].trim().toFloat()
            
            // 检查是否有效
            if (max <= 0f || rating < 0f || !rating.isFinite() || !max.isFinite()) {
                return RATING_UNKNOWN
            }
            
            // 归一化到 0-5
            val normalized = (rating / max) * 5f
            
            // 确保结果有效
            if (!normalized.isFinite()) {
                return RATING_UNKNOWN
            }
            
            normalized
        } catch (e: Exception) {
            RATING_UNKNOWN
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val detailUrl = "https://$domain${manga.url}"
        val response = webClient.httpGet(detailUrl, getRequestHeaders())
        val doc = response.parseHtml()
        
        // 提取详细信息
        val zcover = doc.selectFirst("z-cover")
        val title = zcover?.attr("title") ?: manga.title
        
        val coverImg = zcover?.selectFirst("img.image")
        val coverUrl = coverImg?.attr("src") ?: manga.coverUrl
        
        // 提取描述
        val descBox = doc.selectFirst("div#bookDescriptionBox")
        val description = descBox?.text()?.trim() ?: manga.description
        
        // 提取详细属性
        val detailsBox = doc.selectFirst("div.bookDetailsBox")
        val properties = mutableMapOf<String, String>()
        
        detailsBox?.select("div[class^=property_]")?.forEach { prop ->
            val label = prop.selectFirst("div.property_label")?.text()?.trim()?.removeSuffix(":")
            val value = prop.selectFirst("div.property_value")?.text()?.trim()
            if (label != null && value != null) {
                properties[label] = value
            }
        }
        
        // 提取作者 - 从z-cover的author属性获取，并过滤非作者信息
        val authorAttr = zcover?.attr("author") ?: ""
        val authors = if (authorAttr.isNotEmpty()) {
            filterAuthors(authorAttr)
        } else {
            manga.authors
        }
        
        // 提取标签/分类
        val categoryDiv = detailsBox?.selectFirst("div.property_categories div.property_value")
        val tags = if (categoryDiv != null) {
            val categoryText = categoryDiv.text().trim()
            setOf(MangaTag(categoryText, categoryText, source))
        } else {
            emptySet()
        }
        
        // 提取文件信息
        val fileDiv = detailsBox?.selectFirst("div.property__file")
        val fileInfo = fileDiv?.text()?.trim()?.split(",")
        val extension = fileInfo?.getOrNull(0)?.split("\n")?.getOrNull(1)?.trim()
        val filesize = fileInfo?.getOrNull(1)?.trim()
        
        // 提取评分
        val ratingDiv = doc.selectFirst("div.book-rating")
        val ratingText = ratingDiv?.text()?.replace("\n", "")?.replace(" ", "") ?: ""
        val rating = parseRating(ratingText)
        
        // 构建完整描述
        val fullDesc = buildString {
            if (!description.isNullOrBlank()) {
                append(description)
                append("\n\n")
            }
            append("--- 书籍信息 ---\n")
            properties.forEach { (key, value) ->
                append("$key: $value\n")
            }
            if (extension != null) append("格式: $extension\n")
            if (filesize != null) append("大小: $filesize\n")
        }
        
        // 提取下载链接
        val downloadBtn = doc.selectFirst("a.btn.addDownloadedBook")
        val downloadUrl = if (downloadBtn != null && !downloadBtn.text().contains("unavailable", ignoreCase = true)) {
            downloadBtn.attr("href")
        } else {
            null
        }
        
        // 创建单个章节（下载链接）
        val chapter = if (downloadUrl != null) {
            MangaChapter(
                id = generateUid("${manga.url}|download"),
                url = downloadUrl,
                title = "Download ${extension ?: "EPUB"}",
                number = 1f,
                uploadDate = 0L,
                volume = 0,
                branch = null,
                scanlator = null,
                source = source,
            )
        } else {
            null
        }
        
        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            description = fullDesc,
            authors = authors,
            tags = tags,
            rating = rating,
            chapters = if (chapter != null) listOf(chapter) else null,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // 检查是否是本地EPUB章节（已下载的）- NEW ARCHITECTURE
        // 新架构使用epub://协议：epub://{manga_id}/chapter/{index}
        if (chapter.url.startsWith("epub://")) {
            // 本地EPUB章节由NovelContentLoader处理
            // 返回空列表，避免使用漫画阅读器
            return emptyList()
        }
        
        // 向后兼容：检查旧格式的本地EPUB章节
        if (chapter.url.startsWith("file://") && chapter.url.contains("#chapter/")) {
            // 旧格式不再支持，返回空列表
            return emptyList()
        }
        
        // 检查是否已登录
        if (!isAuthorized()) {
            // 未登录时抛出认证异常
            throw org.skepsun.kototoro.parsers.exception.AuthRequiredException(source)
        }
        
        // Z-Library 的下载是直接文件下载，不是分页内容
        // 返回下载 URL 作为单个"页面"，标记为EPUB
        val downloadUrl = "https://$domain${chapter.url}"
        
        return listOf(
            MangaPage(
                id = generateUid(downloadUrl),
                url = downloadUrl,
                preview = "EPUB",  // 标记为EPUB，用于下载处理
                source = source,
            ),
        )
    }
    
    override suspend fun getPageUrl(page: MangaPage): String {
        // 直接返回EPUB下载URL
        // Cookie会自动从CookieJar发送
        return page.url
    }
    
    /**
     * 过滤作者字段中的非作者信息
     * 
     * 移除以下类型的内容：
     * - 评论数（如"30 comments"）
     * - 在线书店（Amazon, Barnes & Noble, Bookshop.org）
     * - 邮箱地址（如support@z-lib.fm）
     * - 分类信息（如"Computers - Computer Science"）
     * - 出版社信息（如果与作者重复）
     */
    private fun filterAuthors(authorText: String): Set<String> {
        // 先移除已知的非作者短语（在分割之前）
        var cleaned = authorText
        val nonAuthorPatterns = listOf(
            Regex("Barnes\\s*&\\s*Noble", RegexOption.IGNORE_CASE),
            Regex("Bookshop\\.org", RegexOption.IGNORE_CASE),
            Regex("\\d+\\s*comments?", RegexOption.IGNORE_CASE),
            Regex("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}", RegexOption.IGNORE_CASE),  // 邮箱
        )
        
        for (pattern in nonAuthorPatterns) {
            cleaned = pattern.replace(cleaned, "")
        }
        
        // 分割作者（可能用分号、&、逗号分隔）
        val parts = cleaned.split(Regex("[;&,]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        val filtered = parts.filter { author ->
            val lowerAuthor = author.lowercase()
            
            // 过滤规则
            when {
                // 在线书店
                lowerAuthor.contains("amazon") -> false
                lowerAuthor.contains("barnes") -> false
                lowerAuthor.contains("noble") -> false
                lowerAuthor.contains("bookshop") -> false
                
                // 分类信息（包含" - "的通常是分类）
                author.contains(" - ") -> false
                
                // 太短的名字（可能是缩写或无效数据）
                author.length < 2 -> false
                
                // 纯数字
                author.all { it.isDigit() || it.isWhitespace() } -> false
                
                // 通过所有过滤规则
                else -> true
            }
        }
        
        return filtered.toSet()
    }
}
