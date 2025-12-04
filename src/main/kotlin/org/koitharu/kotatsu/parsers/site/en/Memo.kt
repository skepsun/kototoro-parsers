package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.net.URLDecoder
import java.util.EnumSet

/**
 * MemoJAV - 成人视频网站
 * 
 * 网站: https://memojav.com/
 * 
 * 技术特点:
 * - 通过API获取视频信息
 * - 视频URL经过URL编码
 * - API路径: /hls/get_video_info.php
 * - 网站使用JavaScript动态加载内容
 * 
 * 页面结构:
 * - 主页(热门): https://memojav.com/
 * - 视频列表(更新): https://memojav.com/video/
 * - 演员列表: https://memojav.com/actress/
 * - 公司列表: https://memojav.com/studio/
 * - 类别列表: https://memojav.com/categories/
 * - 标签列表: https://memojav.com/label/
 * 
 * 实现状态:
 * - ✅ 视频搜索
 * - ✅ 视频详情解析
 * - ✅ 视频URL提取和解码
 * - ⚠️ 列表浏览需要JavaScript支持（暂不支持）
 * 
 * 注意:
 * - 网站主要内容通过JavaScript动态加载
 * - 当前实现主要支持搜索和直接访问视频页面
 * - 列表浏览功能需要额外的API支持
 */
@MangaSourceParser("MEMO", "MemoJAV", "en", type = ContentType.VIDEO)
internal class Memo(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MEMO, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("memojav.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
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
        // Memo主要通过API工作，需要搜索才能获取视频
        // 如果没有搜索查询，返回空列表
        if (filter.query.isNullOrBlank()) {
            return emptyList()
        }
        
        val url = buildListUrl(page, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>()
        
        // 选择器: 查找视频项 - 需要根据实际网站调整
        val videoItems = doc.select("div.video-item, div.item, article.video, div.card")
        
        for (item in videoItems) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            
            if (href.isBlank()) continue
            
            // 提取视频ID
            val videoId = href.substringAfterLast("/").substringBefore("?").substringBefore(".html")
            if (videoId.isBlank() || !seen.add(videoId)) continue
            
            val img = item.selectFirst("img")
            val coverUrl = img?.attr("data-src") ?: img?.attr("src") ?: ""
            
            val title = img?.attr("alt") ?: link.attr("title") ?: videoId.uppercase()
            
            items.add(
                Manga(
                    id = generateUid(videoId),
                    url = "/$videoId",
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
                    description = null,
                    chapters = null,
                    source = source,
                )
            )
        }
        
        return items
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()
        
        // 提取标题
        val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: manga.title
        
        // 提取番号
        val codePattern = Regex("""([A-Z]+(?:-[A-Z]+)*-\d+)""")
        val codeMatch = codePattern.find(title)
        val code = codeMatch?.value ?: ""
        val cleanTitle = if (code.isNotEmpty()) title.replace(code, "").trim() else title
        
        // 构建描述
        val description = if (code.isNotEmpty()) "番號: $code" else null
        
        // 提取标签
        val tags = doc.select("a[href*=/tag/], a[href*=/category/]").mapNotNullToSet { elem ->
            val tagName = elem.text().trim()
            if (tagName.isNotEmpty()) {
                MangaTag(
                    key = elem.attr("href").substringAfterLast('/'),
                    title = tagName,
                    source = source,
                )
            } else null
        }
        
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
        
        val videoId = chapter.url.substringAfter("/").substringBefore("?")
        val videoUrl = extractVideoUrl(videoId) ?: return emptyList()
        
        return listOf(
            MangaPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = null,
                source = source,
            ),
        )
    }
    
    private fun buildListUrl(page: Int, filter: MangaListFilter): String {
        val base = StringBuilder("https://").append(domain)
        
        if (!filter.query.isNullOrBlank()) {
            base.append("/search?q=").append(filter.query)
            if (page > 1) base.append("&page=").append(page)
        } else {
            base.append("/videos")
            if (page > 1) base.append("?page=").append(page)
        }
        
        return base.toString()
    }
    
    private suspend fun extractVideoUrl(videoId: String): String? {
        try {
            // 构建API URL
            val apiUrl = "https://$domain/hls/get_video_info.php?id=$videoId&sig=NTg1NTczNg&sts=7264825"
            
            val headers = getRequestHeaders().newBuilder()
                .add("Referer", "https://$domain")
                .build()
            
            val response = webClient.httpGet(apiUrl, headers)
            val html = response.parseHtml().outerHtml()
            
            // 从JSON响应中提取URL编码的视频地址
            val urlPattern = Regex(""""url"\s*:\s*"(https?%3A%2F%2F[^"]+)"""")
            val urlMatch = urlPattern.find(html)
            
            if (urlMatch != null) {
                val encodedUrl = urlMatch.groupValues[1]
                // URL 解码
                val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                return decodedUrl
            }
            
            // 备用策略: 直接查找 m3u8 URL
            val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            val m3u8Match = m3u8Pattern.find(html)
            if (m3u8Match != null) return m3u8Match.value
            
        } catch (e: Exception) {
            // API 请求失败
        }
        
        return null
    }
}
