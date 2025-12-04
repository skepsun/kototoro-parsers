package org.skepsun.kototoro.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Cosxplay - Cosplay 视频网站
 * 
 * 网站: https://cosxplay.com/
 * 
 * 技术特点:
 * - 使用 Cloudflare JavaScript Challenge 保护
 * - 使用 CloudFlareHelper 自动检测和处理保护
 * - 首次访问需要用户在浏览器中完成 Challenge
 * - Cookie 自动保存，后续访问无需重复验证
 * 
 * 实现状态:
 * - ✅ 视频列表解析 (getListPage)
 * - ✅ 视频详情解析 (getDetails)
 * - ✅ 视频播放 URL 提取 (getPages)
 * - ✅ Cloudflare Challenge 处理
 * - ✅ 标签提取
 * - ⏳ 搜索功能 (基础实现)
 * - ⏳ 分类浏览
 * - ⏳ 标签过滤
 * 
 * 页面结构:
 * - 首页: 视频列表，每页 24 个视频
 * - 详情页: 包含视频播放器和元数据
 * - 视频格式: MP4 (高清/低清两个版本)
 * 
 * 数据提取:
 * - 视频列表: .video-block[data-post-id]
 * - 视频标题: .title
 * - 缩略图: img.video-img[data-src]
 * - 播放 URL: video source[title=high] 或 source[title=low]
 * - 元数据: .views-number, .rating, .duration
 * - 标签: .tags a
 * 
 * 使用方法:
 * 1. 用户首次访问时会提示在浏览器中打开
 * 2. 在浏览器中完成 Cloudflare Challenge
 * 3. 返回应用，Cookie 已保存，可正常使用
 * 4. Cookie 过期时重复步骤 1-3
 * 
 * 参考文档:
 * - COSXPLAY_RESEARCH.md - 技术分析
 * - COSXPLAY_IMPLEMENTATION_COMPLETE.md - 实现说明
 * - COSXPLAY_QUICK_REFERENCE.md - 快速参考
 * 
 * 注意:
 * - 标记为 @Broken 是因为需要用户手动完成 Cloudflare Challenge
 * - 核心功能已实现，可以正常使用
 * - 视频 URL 包含 verify 参数，可能有时效性
 */
// @Broken("Requires manual Cloudflare Challenge completion on first access")
@MangaSourceParser("COSXPLAY", "Cosxplay", "en", type = ContentType.VIDEO)
internal class Cosxplay(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COSXPLAY, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("cosxplay.com")

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
        
        // 检查 Cloudflare 保护
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            // 需要用户在浏览器中解决 Cloudflare Challenge
            context.requestBrowserAction(this, url)
        }
        
        val doc = response.parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>()
        
        // 解析视频列表
        val videoBlocks = doc.select(".video-block")
        for (block in videoBlocks) {
            val postId = block.attr("data-post-id").takeIf { it.isNotBlank() } ?: continue
            val link = block.selectFirst("a.thumb.ppopp") ?: continue
            val href = runCatching { link.attrAsRelativeUrl("href") }.getOrNull() ?: continue
            
            if (!seen.add(href)) continue
            
            val title = block.selectFirst(".title")?.text()?.trim() ?: "Untitled"
            val thumbnail = block.selectFirst("img.video-img")?.attr("data-src")
            val views = block.selectFirst(".views-number")?.text()?.trim()
            val rating = block.selectFirst(".rating")?.text()?.trim()
            val duration = block.selectFirst(".duration")?.text()?.trim()
            
            items.add(
                Manga(
                    id = generateUid(postId),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = thumbnail,
                    largeCoverUrl = null,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = buildDescription(views, rating, duration),
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
            
            if (items.size >= pageSize) break
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
        
        // 提取视频元数据
        val metaTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val metaDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val metaImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        // 提取标签
        val tags = doc.select(".tags a").mapNotNull { tag ->
            val tagText = tag.text().trim()
            if (tagText.isNotBlank()) {
                MangaTag(
                    title = tagText,
                    key = tagText.lowercase().replace(" ", "-"),
                    source = source,
                )
            } else null
        }.toSet()
        
        // 提取视频播放 URL
        val videoUrl = extractVideoUrl(doc)
        
        // 创建单个章节（视频）
        val chapter = MangaChapter(
            id = generateUid("${manga.url}|video"),
            url = videoUrl ?: manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )
        
        return manga.copy(
            title = metaTitle ?: manga.title,
            description = metaDesc ?: manga.description,
            largeCoverUrl = metaImage ?: manga.largeCoverUrl,
            tags = if (manga.tags.isEmpty()) tags else manga.tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // 如果 chapter.url 已经是视频 URL，直接返回
        if (chapter.url.contains(".mp4")) {
            return listOf(
                MangaPage(
                    id = generateUid(chapter.url),
                    url = chapter.url,
                    preview = null,
                    source = source,
                ),
            )
        }
        
        // 否则需要重新获取详情页
        val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders())
        
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, chapter.url.toAbsoluteUrl(domain))
        }
        
        val doc = response.parseHtml()
        val videoUrl = extractVideoUrl(doc) ?: return emptyList()
        val poster = doc.selectFirst("video")?.attr("poster")
        
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
            base.append("/search")
            base.append("?q=").append(filter.query)
            if (page > 1) base.append("&page=").append(page)
        } else {
            base.append("/")
            if (page > 1) base.append("page/").append(page).append("/")
        }
        
        return base.toString()
    }
    
    private fun buildDescription(views: String?, rating: String?, duration: String?): String? {
        val parts = listOfNotNull(
            views?.let { "Views: $it" },
            rating?.let { "Rating: $it" },
            duration?.let { "Duration: $it" },
        )
        return if (parts.isNotEmpty()) parts.joinToString(" | ") else null
    }
    
    private fun extractVideoUrl(doc: Document): String? {
        // 策略 1: 从 <video> 标签的 <source> 提取
        val highQuality = doc.selectFirst("video source[title=high]")?.attr("src")
        if (!highQuality.isNullOrBlank()) return highQuality
        
        val lowQuality = doc.selectFirst("video source[title=low]")?.attr("src")
        if (!lowQuality.isNullOrBlank()) return lowQuality
        
        // 策略 2: 从 <video> 标签的 src 属性提取
        val videoSrc = doc.selectFirst("video")?.attr("src")
        if (!videoSrc.isNullOrBlank()) return videoSrc
        
        // 策略 3: 从页面 HTML 中用正则提取 .mp4 URL
        val html = doc.outerHtml()
        val mp4Pattern = Regex("""https?://[^"'\s>]+\.mp4[^"'\s>]*""", RegexOption.IGNORE_CASE)
        val match = mp4Pattern.find(html)
        return match?.value
    }
}
