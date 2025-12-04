package org.skepsun.kototoro.parsers.site.zh

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
 * MissAV - 日本成人视频网站
 * 
 * 网站: https://missav.ai/
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
 * 
 * 页面结构:
 * - 首页: div.thumbnail.group 包含视频列表
 * - 详情页: 包含 video.player 播放器和元数据
 * - 视频格式: M3U8 (https://fourhoi.com/{video-id}/playlist.m3u8)
 * 
 * 数据提取:
 * - 视频列表: div.thumbnail.group > a[href]
 * - 视频标题: div.text-secondary a
 * - 缩略图: img[data-src]
 * - 时长: span.absolute
 * - 播放 URL: 从混淆的 JavaScript 中解码（eval 函数）
 *   实际 URL: https://surrit.com/{uuid}/playlist.m3u8
 * - 元数据: div.space-y-2 div (番号、发行日期、系列等)
 * - 标签: a[href*='/genres/']
 * - 演员: a[href*='/actresses/']
 * 
 * 使用方法:
 * 1. 用户首次访问时会提示在浏览器中打开
 * 2. 在浏览器中完成 Cloudflare Challenge
 * 3. 返回应用，Cookie 已保存，可正常使用
 * 4. Cookie 过期时重复步骤 1-3
 * 
 * 注意:
 * - 标记为 @Broken 是因为需要用户手动完成 Cloudflare Challenge
 * - 核心功能待实现
 * - 视频 URL 可能有时效性
 */
// @Broken("Requires manual Cloudflare Challenge completion on first access")
@MangaSourceParser("MISSAV", "MissAV", "zh", type = ContentType.VIDEO)
internal class MissAV(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MISSAV, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("missav.ai")

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
        
        // 选择器: div.thumbnail.group
        val thumbnails = doc.select("div.thumbnail.group")
        
        for (thumbnail in thumbnails) {
            // 提取链接
            val link = thumbnail.selectFirst("a[href*='/']") ?: continue
            val href = link.attr("href")
            
            // 过滤无效链接
            if (href.isBlank() || href == "#" || href == "/") continue
            
            // 提取视频 ID（URL 的最后一部分）
            val videoId = href.substringAfterLast('/')
            if (videoId.isBlank() || !seen.add(videoId)) continue
            
            // 提取图片
            val img = thumbnail.selectFirst("img")
            val coverUrl = img?.attr("data-src") ?: img?.attr("src") ?: ""
            
            // 提取标题
            val titleElem = thumbnail.selectFirst("div.text-secondary a")
            val title = titleElem?.text()?.trim() ?: videoId.uppercase()
            
            // 提取时长
            val durationElem = thumbnail.selectFirst("span.absolute")
            val duration = durationElem?.text()?.trim() ?: ""
            
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
                    description = if (duration.isNotEmpty()) "时长: $duration" else null,
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
        val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
        
        // 提取元数据
        val infoElements = doc.select("div.space-y-2 div")
        val metadata = mutableMapOf<String, String>()
        
        for (elem in infoElements) {
            val text = elem.text().trim()
            if (text.contains(":")) {
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    metadata[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        
        // 提取番号
        val code = metadata["番號"] ?: ""
        
        // 提取发布日期
        val releaseDate = metadata["發行日期"] ?: ""
        
        // 构建描述
        val descriptionParts = mutableListOf<String>()
        if (code.isNotEmpty()) descriptionParts.add("番號: $code")
        if (releaseDate.isNotEmpty()) descriptionParts.add("發行日期: $releaseDate")
        
        // 添加其他元数据
        metadata["系列"]?.let { descriptionParts.add("系列: $it") }
        metadata["發行商"]?.let { descriptionParts.add("發行商: $it") }
        metadata["導演"]?.let { descriptionParts.add("導演: $it") }
        
        val description = descriptionParts.joinToString("\n")
        
        // 提取标签
        val tags = doc.select("a[href*='/genres/']").mapNotNullToSet { elem ->
            val tagName = elem.text().trim()
            if (tagName.isNotEmpty()) {
                MangaTag(
                    key = elem.attr("href").substringAfterLast('/'),
                    title = tagName,
                    source = source,
                )
            } else null
        }
        
        // 提取演员
        val actors = doc.select("a[href*='/actresses/']")
            .mapNotNull { it.text().trim().takeIf { text -> text.isNotEmpty() && !text.contains("女優排行") } }
            .toSet()
        
        // 提取封面
        val coverUrl = doc.selectFirst("video.player")?.attr("data-poster") ?: manga.coverUrl
        
        // 创建单个章节（视频）
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
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = actors,
            tags = tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // 如果 chapter.url 已经是视频 URL，直接返回
        if (chapter.url.contains(".m3u8") || chapter.url.contains(".mp4")) {
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
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(fullUrl, getRequestHeaders())
        
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, fullUrl)
        }
        
        val doc = response.parseHtml()
        val videoUrl = extractVideoUrl(doc, chapter.url) ?: return emptyList()
        
        // 提取预览图
        val poster = doc.selectFirst("video.player")?.attr("data-poster")
        
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
            base.append("/search/").append(filter.query)
            if (page > 1) base.append("?page=").append(page)
        } else {
            base.append("/")
            if (page > 1) base.append("?page=").append(page)
        }
        
        return base.toString()
    }
    
    private suspend fun extractVideoUrl(doc: Document, chapterUrl: String): String? {
        val html = doc.outerHtml()
        
        // 策略 1: 从混淆的 JavaScript 中提取 UUID
        // MissAV 使用 eval() 混淆视频 URL，但 UUID 在替换表中
        // 模式: m3u8|{uuid-parts}|com|surrit|https|video
        val uuidPattern = Regex("""m3u8\|([a-f0-9\|]+)\|com\|surrit\|https\|video""")
        val uuidMatch = uuidPattern.find(html)
        
        if (uuidMatch != null) {
            try {
                // 提取 UUID 部分并反转
                val uuidParts = uuidMatch.groupValues[1].split('|').reversed()
                val uuid = uuidParts.joinToString("-")
                
                // 构建 playlist URL
                val playlistUrl = "https://surrit.com/$uuid/playlist.m3u8"
                
                // 获取最高质量的流
                return getHighestQualityStream(playlistUrl) ?: playlistUrl
            } catch (e: Exception) {
                // UUID 提取失败，继续尝试其他策略
            }
        }
        
        // 策略 2: 直接查找 surrit.com 的 URL
        val surritPattern = Regex("""https://surrit\.com/[a-f0-9-]+/playlist\.m3u8""")
        val surritMatch = surritPattern.find(html)
        if (surritMatch != null) {
            val playlistUrl = surritMatch.value
            return getHighestQualityStream(playlistUrl) ?: playlistUrl
        }
        
        // 策略 3: 从 <video> 标签的 <source> 提取
        val videoSrc = doc.selectFirst("video source")?.attr("src")
        if (!videoSrc.isNullOrBlank() && !videoSrc.startsWith("blob:")) return videoSrc
        
        // 策略 4: 从 <video> 标签的 src 属性提取（排除 blob URL）
        val directSrc = doc.selectFirst("video")?.attr("src")
        if (!directSrc.isNullOrBlank() && !directSrc.startsWith("blob:")) return directSrc
        
        // 策略 5: 从页面 HTML 中用正则提取 m3u8 或 mp4 URL
        val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        val m3u8Match = m3u8Pattern.find(html)
        if (m3u8Match != null) return m3u8Match.value
        
        // 策略 6: 查找 mp4（排除 preview.mp4）
        val mp4Pattern = Regex("""https?://[^\s"'<>]+/[a-z]+-\d+/(?!preview)[^/\s"'<>]+\.mp4""", RegexOption.IGNORE_CASE)
        val mp4Match = mp4Pattern.find(html)
        return mp4Match?.value
    }
    
    private suspend fun getHighestQualityStream(playlistUrl: String): String? {
        try {
            val response = webClient.httpGet(playlistUrl, getRequestHeaders())
            val playlistContent = response.body?.string() ?: return null
            
            // 解析 m3u8 playlist，查找最高质量的流
            val streamPattern = Regex("""#EXT-X-STREAM-INF:BANDWIDTH=(\d+),.*?RESOLUTION=(\d+x\d+).*?\n(.+)""")
            val streams = streamPattern.findAll(playlistContent).map { match ->
                val bandwidth = match.groupValues[1].toIntOrNull() ?: 0
                val resolution = match.groupValues[2]
                val url = match.groupValues[3].trim()
                Triple(bandwidth, resolution, url)
            }.toList()
            
            if (streams.isEmpty()) return null
            
            // 按带宽排序，选择最高质量
            val bestStream = streams.maxByOrNull { it.first } ?: return null
            val streamUrl = bestStream.third
            
            // 如果是相对路径，构建完整 URL
            return if (streamUrl.startsWith("http")) {
                streamUrl
            } else {
                val baseUrl = playlistUrl.substringBeforeLast('/')
                "$baseUrl/$streamUrl"
            }
        } catch (e: Exception) {
            // 获取最高质量流失败，返回 null 使用原始 playlist URL
            return null
        }
    }
}
