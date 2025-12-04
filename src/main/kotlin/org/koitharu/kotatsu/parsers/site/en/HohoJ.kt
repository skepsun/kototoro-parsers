package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * HohoJ - 成人视频网站
 * 
 * 网站: https://hohoj.tv/
 * 
 * 技术特点:
 * - 视频URL在JavaScript变量videoSrc中
 * - 简单的m3u8提取
 * - 支持分类和多种排序方式
 * 
 * 分类 (type) - 可通过URL参数指定:
 * - all: 全部 (默认)
 * - censored: 有码
 * - chinese: 中文字幕
 * - uncensored: 无码
 * - europe: 欧美
 * 
 * URL示例:
 * - 全部-最新: /search?type=all&order=latest&p=1
 * - 中文字幕-热门: /search?type=chinese&order=popular&p=1
 * 
 * 注意: 当前实现使用type=all，分类浏览需要在URL中指定
 * 
 * 排序 (order):
 * - latest: 最新 (UPDATED)
 * - popular: 热门 (POPULARITY)
 * - likes: 点赞 (RATING)
 * - views: 播放量 (暂不支持)
 * 
 * 实现状态:
 * - ✅ 视频列表浏览
 * - ✅ 视频搜索
 * - ✅ 视频详情解析
 * - ✅ 视频URL提取
 * - ✅ 分类支持
 * - ✅ 多种排序
 */
@MangaSourceParser("HOHOJ", "HohoJ", "en", type = ContentType.VIDEO)
internal class HohoJ(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HOHOJ, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("hohoj.tv")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = setOf(
                MangaTag(key = "all", title = "全部", source = source),
                MangaTag(key = "censored", title = "有码", source = source),
                MangaTag(key = "chinese", title = "中文字幕", source = source),
                MangaTag(key = "uncensored", title = "无码", source = source),
                MangaTag(key = "europe", title = "欧美", source = source),
            ),
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>()
        
        // 选择器: div.video-item
        val videoItems = doc.select("div.video-item")
        
        for (item in videoItems) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            
            if (href.isBlank()) continue
            
            // 提取视频ID - 从 /video?id=3400 格式提取
            val videoIdMatch = Regex("""[?&]id=(\d+)""").find(href)
            val videoId = videoIdMatch?.groupValues?.get(1) ?: continue
            
            if (videoId.isBlank() || !seen.add(videoId)) continue
            
            val img = item.selectFirst("img")
            val coverUrl = img?.attr("src") ?: img?.attr("data-src") ?: ""
            
            val title = img?.attr("alt") ?: item.selectFirst("div.video-item-title")?.text()?.trim() ?: videoId
            
            items.add(
                Manga(
                    id = generateUid(videoId),
                    url = "/embed?id=$videoId",
                    publicUrl = "https://$domain/video?id=$videoId",
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
        val videoId = manga.url.substringAfter("id=")
        val detailUrl = "https://$domain/video?id=$videoId"
        val response = webClient.httpGet(detailUrl, getRequestHeaders())
        val doc = response.parseHtml()
        
        // 提取标题
        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: manga.title
        
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
        
        val videoId = chapter.url.substringAfter("id=")
        val embedUrl = "https://$domain/embed?id=$videoId"
        val referer = "https://$domain/video?id=$videoId"
        
        val headers = getRequestHeaders().newBuilder()
            .add("Referer", referer)
            .build()
        
        val response = webClient.httpGet(embedUrl, headers)
        val doc = response.parseHtml()
        val videoUrl = extractVideoUrl(doc) ?: return emptyList()
        
        return listOf(
            MangaPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = null,
                source = source,
            ),
        )
    }
    
    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val base = StringBuilder("https://").append(domain)
        
        if (!filter.query.isNullOrBlank()) {
            // 搜索
            base.append("/search?text=").append(filter.query)
            if (page > 1) base.append("&page=").append(page)
        } else {
            // 分类浏览 - 使用 /search 端点
            base.append("/search")
            
            // 添加类型参数
            val typeTag = filter.tags.firstOrNull()?.key ?: "all"
            base.append("?type=").append(typeTag)
            
            // 添加排序参数
            when (order) {
                SortOrder.UPDATED -> base.append("&order=latest")
                SortOrder.POPULARITY -> base.append("&order=popular")
                SortOrder.RATING -> base.append("&order=likes")
                else -> base.append("&order=latest")
            }
            
            // 添加页码
            if (page > 1) base.append("&p=").append(page)
        }
        
        return base.toString()
    }
    
    private fun extractVideoUrl(doc: Document): String? {
        val html = doc.outerHtml()
        
        // 策略 1: 从 JavaScript 变量 videoSrc 提取
        val videoSrcPattern = Regex("""var\s+videoSrc\s*=\s*['"]([^'"]+)['"]""")
        val videoSrcMatch = videoSrcPattern.find(html)
        if (videoSrcMatch != null) {
            return videoSrcMatch.groupValues[1]
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
