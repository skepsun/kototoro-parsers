package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.util.*
import java.util.Base64
import java.net.URLDecoder
import java.util.EnumSet

/**
 * KanAV - 成人视频网站
 * 
 * 网站: https://kanav.ad/
 * 
 * 技术特点:
 * - 视频URL经过Base64编码和URL编码
 * - 需要解码才能获取真实的m3u8地址
 * - 支持分类浏览和搜索
 * 
 * 分类 (可通过URL直接访问):
 * - 中文字幕: /index.php/vod/type/id/1.html
 * - 日韩有码: /index.php/vod/type/id/2.html
 * - 日韩无码: /index.php/vod/type/id/3.html
 * - 国产AV: /index.php/vod/type/id/4.html
 * - 流出自拍: /index.php/vod/type/id/5.html
 * - 动漫番剧: /index.php/vod/type/id/6.html
 * 
 * 实现状态:
 * - ✅ 视频列表浏览
 * - ✅ 视频搜索
 * - ✅ 视频详情解析
 * - ✅ 视频URL提取和解码
 * - ✅ 分类过滤器（6个分类）
 */
@MangaSourceParser("KANAV", "KanAV", "en", type = ContentType.VIDEO)
internal class KanAV(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KANAV, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("kanav.ad")

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
            availableTags = setOf(
                MangaTag("中文字幕", "1", source),
                MangaTag("日韩有码", "2", source),
                MangaTag("日韩无码", "3", source),
                MangaTag("国产AV", "4", source),
                MangaTag("流出自拍", "5", source),
                MangaTag("动漫番剧", "6", source),
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        
        // 检查 Cloudflare 保护
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        
        val doc = response.parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>()
        
        // 选择器: div.video-item
        val videoItems = doc.select("div.video-item")
        
        for (item in videoItems) {
            // 查找链接
            val link = item.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            
            if (href.isBlank()) continue
            
            // 提取视频ID - 从 /index.php/vod/play/id/95256/sid/1/nid/1.html 提取
            val idMatch = Regex("""/id/(\d+)/""").find(href)
            val videoId = idMatch?.groupValues?.get(1) ?: continue
            
            if (videoId.isBlank() || !seen.add(videoId)) continue
            
            val img = item.selectFirst("img")
            val coverUrl = img?.attr("data-original") ?: img?.attr("src") ?: ""
            
            val title = img?.attr("alt") ?: link.attr("title") ?: videoId
            
            items.add(
                Manga(
                    id = generateUid(videoId),
                    url = href,
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
        
        // 检查 Cloudflare 保护
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, manga.publicUrl)
        }
        
        val doc = response.parseHtml()
        
        // 提取标题
        val title = doc.selectFirst("h1, .page-title, .video-info-title")?.text()?.trim() ?: manga.title
        
        // 提取番号
        val codePattern = Regex("""([A-Z]+(?:-[A-Z]+)*-\d+)""")
        val codeMatch = codePattern.find(title)
        val code = codeMatch?.value ?: ""
        val cleanTitle = if (code.isNotEmpty()) title.replace(code, "").trim() else title
        
        // 构建描述
        val description = if (code.isNotEmpty()) "番號: $code" else null
        
        // 提取标签
        val tags = doc.select("a[href*=/vod/type/], a[href*=/tag/]").mapNotNullToSet { elem ->
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
        
        // 检查 Cloudflare 保护
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, fullUrl)
        }
        
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
    
    private fun buildListUrl(page: Int, filter: MangaListFilter): String {
        val base = StringBuilder("https://").append(domain)
        
        if (!filter.query.isNullOrBlank()) {
            // 搜索
            base.append("/index.php/vod/search.html?wd=").append(filter.query)
            base.append("&by=time_add")
            if (page > 1) base.append("&page=").append(page)
        } else if (filter.tags.isNotEmpty()) {
            // 分类浏览
            val tag = filter.tags.first()
            base.append("/index.php/vod/type/id/").append(tag.key).append(".html")
            if (page > 1) base.append("?page=").append(page)
        } else {
            // 主页 - 显示所有视频
            base.append("/")
            if (page > 1) base.append("?page=").append(page)
        }
        
        return base.toString()
    }
    
    private fun extractVideoUrl(doc: Document): String? {
        val html = doc.outerHtml()
        
        // 策略 1: 从 JSON 中提取编码的 URL
        val urlPattern = Regex(""""url"\s*:\s*"([A-Za-z0-9+/=]+)"""")
        val urlMatch = urlPattern.find(html)
        
        if (urlMatch != null) {
            try {
                val encodedUrl = urlMatch.groupValues[1]
                // Base64 解码
                val decodedBytes = Base64.getDecoder().decode(encodedUrl)
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                // URL 解码
                val finalUrl = URLDecoder.decode(decodedStr, "UTF-8")
                return finalUrl
            } catch (e: Exception) {
                // 解码失败，继续尝试其他策略
            }
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
