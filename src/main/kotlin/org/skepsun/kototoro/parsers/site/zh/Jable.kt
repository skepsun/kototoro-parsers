package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Jable - 成人视频网站
 * 
 * 网站: https://jable.tv/
 * 
 * 技术特点:
 * - 使用 Cloudflare 保护
 * - 视频 URL 在 JavaScript 变量 hlsUrl 中
 * - 简单的 m3u8 提取
 * 
 * 页面结构:
 * - 最近更新: https://jable.tv/latest-updates/
 * - 新作发布: https://jable.tv/new-release/
 * - 女优列表: https://jable.tv/models/
 * - 具体女优: https://jable.tv/s1/models/{name}/
 * - 主题列表: https://jable.tv/categories/
 * - 具体主题: https://jable.tv/categories/{name}/
 * - 标签作品: https://jable.tv/tags/{name}/
 * 
 * 实现状态:
 * - ✅ 视频列表解析
 * - ✅ 视频详情解析
 * - ✅ 视频 URL 提取
 * - ✅ Cloudflare 处理
 * - ✅ 搜索功能
 * - ⚠️ 分类/女优/标签浏览（需要UI支持）
 */
// @Broken("Requires manual Cloudflare Challenge completion on first access")
@MangaSourceParser("JABLE", "Jable", "zh", type = ContentType.HENTAI_VIDEO)
internal class Jable(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.JABLE, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("jable.tv")

    // 使用浏览器 User-Agent 避免 CF 检测
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // 添加浏览器特征请求头
    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>()
        
        // 选择器: div.video-item 或类似结构
        val videoItems = doc.select("div[class*=video], article[class*=video]")
        
        for (item in videoItems) {
            val link = item.selectFirst("a[href*=/videos/]") ?: continue
            val href = link.attr("href")
            
            if (href.isBlank()) continue
            
            val videoId = href.substringAfterLast("/videos/").substringBefore("/")
            if (videoId.isBlank() || !seen.add(videoId)) continue
            
            val img = item.selectFirst("img")
            val coverUrl = img?.attr("data-src") ?: img?.attr("src") ?: ""
            
            // 多策略提取标题
            val title = item.selectFirst("h6, h5, h4, .title, .video-title, .card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: item.selectFirst("a[href*=/videos/]")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: link.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: videoId.uppercase()
            
            val duration = item.selectFirst("span[class*=duration], div[class*=duration]")?.text()?.trim() ?: ""
            
            items.add(
                Manga(
                    id = generateUid(videoId),
                    url = "/videos/$videoId/",
                    publicUrl = href.toAbsoluteUrl(domain),
                    title = title,
                    coverUrl = coverUrl,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.ADULT,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    largeCoverUrl = null,
                    description = if (duration.isNotEmpty()) "时长: $duration" else null,
                    chapters = null,
                    source = source,
                )
            )
        }
        // 直接返回结果，不再自动触发 CF 验证
        // 用户可通过刷新重试，或手动在设置中打开浏览器验证
        return items
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()
        
        // 提取标题
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val title = ogTitle ?: doc.selectFirst("h1")?.text() ?: manga.title
        
        // 提取番号和标题
        val codePattern = Regex("""([A-Z]+(?:-[A-Z]+)*-\d+)""")
        val codeMatch = codePattern.find(title)
        val code = codeMatch?.value ?: ""
        val cleanTitle = if (code.isNotEmpty()) title.replace(code, "").trim() else title
        
        // 构建描述
        val description = if (code.isNotEmpty()) "番號: $code" else null
        
        // 提取标签
        val tags = doc.select("a[href*=/tags/], a[href*=/categories/]").mapNotNullToSet { elem ->
            val tagName = elem.text().trim()
            if (tagName.isNotEmpty()) {
                MangaTag(
                    key = elem.attr("href").substringAfterLast('/'),
                    title = tagName,
                    source = source,
                )
            } else null
        }
        
        // 提取封面
        val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: manga.coverUrl
        
        // 创建单个章节
        val chapter = MangaChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )
        
        return manga.copy(
            title = cleanTitle,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        if (chapter.url.contains(".m3u8")) {
            return listOf(
                MangaPage(
                    id = generateUid(chapter.url),
                    url = chapter.url,
                    preview = null,
                    source = source,
                ),
            )
        }
        
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(fullUrl, getRequestHeaders())
        val doc = response.parseHtml()
        val videoUrl = extractVideoUrl(doc) ?: return emptyList()
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        return listOf(
            MangaPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = poster,
                source = source,
            ),
        )
    }
    
    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val base = StringBuilder("https://").append(domain)
        
        if (!filter.query.isNullOrBlank()) {
            // 搜索 - 使用路径格式分页: /search/{query}/{page}/
            base.append("/search/").append(filter.query).append("/")
            if (page > 1) base.append(page).append("/")
        } else {
            // 默认使用最近更新页面
            base.append("/latest-updates/")
            if (page > 1) base.append(page).append("/")
        }
        
        return base.toString()
    }
    
    private fun extractVideoUrl(doc: Document): String? {
        val html = doc.outerHtml()
        
        // 策略 1: 从 JavaScript 变量 hlsUrl 提取
        val hlsPattern = Regex("""var\s+hlsUrl\s*=\s*['"]([^'"]+)['"]""")
        val hlsMatch = hlsPattern.find(html)
        if (hlsMatch != null) {
            return hlsMatch.groupValues[1]
        }
        
        // 策略 2: 直接查找 m3u8 URL
        val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        val m3u8Match = m3u8Pattern.find(html)
        if (m3u8Match != null) return m3u8Match.value
        
        // 策略 3: 从 video 标签提取
        val videoSrc = doc.selectFirst("video source")?.attr("src")
        if (!videoSrc.isNullOrBlank() && !videoSrc.startsWith("blob:")) return videoSrc
        
        return null
    }
}
