package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
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
@MangaSourceParser("COSXPLAY", "Cosxplay", "en", type = ContentType.HENTAI_VIDEO)
internal class Cosxplay(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COSXPLAY, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("cosxplay.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // Newest (latest)
        SortOrder.POPULARITY,   // Best (popular) - also used for most-viewed
        SortOrder.RATING,       // Most viewed (most-viewed)
        SortOrder.ALPHABETICAL, // Longest (longest)
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .set("User-Agent", context.getDefaultUserAgent())
            .set("Referer", "https://$domain/")
            .build()
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = setOf(
            MangaTag(title = "2B", key = "7841-nier-automata", source = source),
            MangaTag(title = "ASMR", key = "18231-18321-asmr", source = source),
            MangaTag(title = "Ahegao", key = "4063-ahegao", source = source),
            MangaTag(title = "Anal", key = "11104-anal", source = source),
            MangaTag(title = "Anime", key = "12814-anime", source = source),
            MangaTag(title = "BBW", key = "19001-bbw", source = source),
            MangaTag(title = "Big boobs", key = "11117-big-boobs", source = source),
            MangaTag(title = "Bondage", key = "10570-bondage", source = source),
            MangaTag(title = "Bunny", key = "8103-bunnies", source = source),
            MangaTag(title = "Creampie", key = "11113-creampie", source = source),
            MangaTag(title = "D.va", key = "95-overwatch", source = source),
            MangaTag(title = "Dildo", key = "11118-dildo", source = source),
            MangaTag(title = "Feet", key = "17809-feet", source = source),
            MangaTag(title = "Femboy", key = "71061-femboy", source = source),
            MangaTag(title = "Furry", key = "16652-furry", source = source),
            MangaTag(title = "Genshin", key = "70946-genshin-impact", source = source),
            MangaTag(title = "Halloween", key = "17220-halloween", source = source),
            MangaTag(title = "Harley Quinn", key = "7776-harley-quinn", source = source),
            MangaTag(title = "Hinata", key = "7828-naruto/12508-hinata", source = source),
            MangaTag(title = "JOI", key = "11121-joi", source = source),
            MangaTag(title = "Japanese", key = "11115-asian/12547-japanese", source = source),
            MangaTag(title = "Jinx", key = "61118-jinx", source = source),
            MangaTag(title = "Kigurumi", key = "5231-kigurumi", source = source),
            MangaTag(title = "Latex", key = "5230-latex", source = source),
            MangaTag(title = "Lesbian", key = "11114-lesbian", source = source),
            MangaTag(title = "Maid", key = "19621-uniform/13908-maid", source = source),
            MangaTag(title = "Makima", key = "73136-makima", source = source),
            MangaTag(title = "Masturbation", key = "17807-masturbation", source = source),
            MangaTag(title = "Naruto", key = "7828-naruto", source = source),
            MangaTag(title = "Neko", key = "11101-neko-porn", source = source),
            MangaTag(title = "Nezuko", key = "12814-anime/59215-nezuko", source = source),
            MangaTag(title = "Nun", key = "17862-nun", source = source),
            MangaTag(title = "Nurse", key = "19621-uniform/13154-nurse", source = source),
            MangaTag(title = "One Piece", key = "12814-anime/70920-one-piece", source = source),
            MangaTag(title = "POV", key = "11119-pov", source = source),
            MangaTag(title = "Poison Ivy", key = "7881-poison-ivy", source = source),
            MangaTag(title = "Pokemon", key = "2166-pokemon", source = source),
            MangaTag(title = "Public", key = "17216-public", source = source),
            MangaTag(title = "Rem", key = "20911-rem-ram", source = source),
            MangaTag(title = "Sakura", key = "7828-naruto/21040-sakura-haruno", source = source),
            MangaTag(title = "Solo", key = "16982-solo", source = source),
            MangaTag(title = "Succubus", key = "7837-creatures/7833-succubus", source = source),
            MangaTag(title = "Supergirl", key = "2101-supergirl", source = source),
            MangaTag(title = "Superheroines", key = "17328-superheroines", source = source),
            MangaTag(title = "Teen", key = "11120-teen", source = source),
            MangaTag(title = "Tsunade", key = "61046-tsunade", source = source),
            MangaTag(title = "Velma", key = "7832-films/7835-scooby-doo/11125-velma", source = source),
            MangaTag(title = "Wonder Woman", key = "922-wonderwoman", source = source),
        )
        
        return MangaListFilterOptions(
            availableTags = tags,
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
            val link = block.selectFirst("a.thumb") ?: continue
            val href = runCatching { link.attrAsRelativeUrl("href") }.getOrNull() ?: continue
            
            if (!seen.add(href)) continue
            
            val title = block.selectFirst(".title")?.text()?.trim() ?: "Untitled"
            val img = block.selectFirst("img.video-img")
            val thumbnail = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
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
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".p-desc")?.text()
        val metaImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        // 提取标签
        val tags = doc.select(".tags-list a").mapNotNull { tag ->
            val tagText = tag.text().trim()
            if (tagText.isNotBlank()) {
                MangaTag(
                    title = tagText,
                    key = tagText.lowercase().replace(" ", "-"),
                    source = source,
                )
            } else null
        }.toSet()
        
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
            title = metaTitle ?: manga.title,
            description = metaDesc ?: manga.description,
            largeCoverUrl = metaImage ?: manga.largeCoverUrl,
            tags = if (manga.tags.isEmpty()) tags else manga.tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
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
        
        // 处理搜索
        if (!filter.query.isNullOrBlank()) {
            base.append("/?s=").append(filter.query.urlEncoded())
            if (page > 1) base.append("&page=").append(page)
            return base.toString()
        }
        
        // 处理标签过滤
        if (filter.tags.isNotEmpty()) {
            val tag = filter.tags.first()
            base.append("/").append(tag.key).append("/")
            if (page > 1) base.append("page/").append(page).append("/")
            
            // 添加排序参数
            val sortFilter = getSortFilter(order)
            if (sortFilter != null) {
                base.append("?filter=").append(sortFilter)
            }
            return base.toString()
        }
        
        // 默认首页
        base.append("/")
        if (page > 1) base.append("page/").append(page).append("/")
        
        // 添加排序参数
        val sortFilter = getSortFilter(order)
        if (sortFilter != null) {
            base.append("?filter=").append(sortFilter)
        }
        
        return base.toString()
    }
    
    private fun getSortFilter(order: SortOrder): String? {
        return when (order) {
            SortOrder.UPDATED -> "latest"
            SortOrder.RATING -> "most-viewed"
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "longest"  // 使用 ALPHABETICAL 代表 Longest
            else -> null
        }
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
        
        // 策略 3: 从 JS 变量提取 (var videoHigh = "...")
        val script = doc.select("script").html()
        val regexHigh = """var\s+videoHigh\s*=\s*["']([^"']+)["']""".toRegex()
        val regexLow = """var\s+videoLow\s*=\s*["']([^"']+)["']""".toRegex()
        
        val jsVideoUrl = regexHigh.find(script)?.groupValues?.get(1)
            ?: regexLow.find(script)?.groupValues?.get(1)
            
        if (!jsVideoUrl.isNullOrBlank()) return jsVideoUrl

        // 策略 4: 从页面 HTML 中用正则提取 .mp4 URL (最后手段)
        val html = doc.outerHtml()
        val mp4Pattern = Regex("""https?://[^"'\s>]+\.mp4[^"'\s>]*""", RegexOption.IGNORE_CASE)
        val match = mp4Pattern.find(html)
        return match?.value
    }
}
