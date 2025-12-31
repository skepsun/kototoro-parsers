package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.net.URLDecoder
import java.util.EnumSet
import okhttp3.Headers

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
@MangaSourceParser("MEMO", "MemoJAV", "en", type = ContentType.HENTAI_VIDEO)
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
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = fetchCategories(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val candidates = buildListUrls(page, filter)
        val headers = getRequestHeaders()
        val seen = LinkedHashSet<String>()
        logDebug("list page=$page candidates=${candidates.joinToString()}")
        for (url in candidates) {
            val response = runCatching { webClient.httpGet(url, headers) }.getOrNull() ?: continue
            val doc = response.parseHtml()
            val items = parseListFromDoc(doc, seen)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()

        val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: manga.title

        val codePattern = Regex("""([A-Z]+(?:-[A-Z]+)*-\d+)""")
        val codeMatch = codePattern.find(title)
        val code = codeMatch?.value ?: ""
        val cleanTitle = if (code.isNotEmpty()) title.replace(code, "").trim() else title

        val metaCover = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")?.toAbsoluteUrlOrNull(domain)
            ?: doc.selectFirst("img[src*=/cover/], img[src*=/poster/], .video-poster img[src]")?.attrOrNull("src")?.toAbsoluteUrlOrNull(domain)

        val info = parseInfoBlocks(doc)
        val description = info["Content"] ?: info["Description"] ?: info["Synopsis"] ?: if (code.isNotEmpty()) "番號: $code" else null
        val label = info["Label"]?.let { MangaTag(it, it, source) }
        val series = info["Series"]?.let { MangaTag(it, it, source) }
        val director = info["Director"]?.let { MangaTag(it, it, source) }
        val studio = info["Studio"]?.let { MangaTag(it, it, source) }

        val categories = doc.select("a[href*=/categories/]").mapNotNull { toTag(it) }.toSet()
        val actresses = doc.select("a[href*=/actress/], a[href*=/actresses/]").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }.toSet()

        val tags = buildSet {
            addAll(categories)
            label?.let { add(it) }
            series?.let { add(it) }
            director?.let { add(it) }
            studio?.let { add(it) }
        }

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
            description = description?.takeIf { it.isNotBlank() },
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            authors = if (actresses.isNotEmpty()) actresses else manga.authors,
            coverUrl = metaCover ?: manga.coverUrl,
            largeCoverUrl = metaCover ?: manga.largeCoverUrl,
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
    
    private fun buildListUrls(page: Int, filter: MangaListFilter): List<String> {
        if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            val urls = mutableListOf("https://$domain/search?q=$q")
            if (page > 1) {
                urls += "https://$domain/search?q=$q&page=$page"
                urls += "https://$domain/search/page/$page/?q=$q"
            }
            return urls
        }
        val tag = filter.tags.firstOrNull()
        if (tag != null) {
            val base = "https://$domain/categories/${tag.key}"
            if (page > 1) {
                return listOf(
                    "$base/page-$page",
                    "$base/page/$page/",
                    "$base/page-$page/",
                    base,
                    "$base/",
                    "$base/page-1",
                )
            }
            return listOf(base, "$base/", "$base/page-1")
        }

        if (page > 1) {
            return listOf(
                "https://$domain/video/page-$page",
                "https://$domain/video/page/$page/",
                "https://$domain/video/?page=$page",
                "https://$domain/video/",
                "https://$domain/video/page-1",
            )
        }
        return listOf("https://$domain/video/", "https://$domain/video/page-1")
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun parseListFromDoc(doc: Document, seen: MutableSet<String>): List<Manga> {
        val items = ArrayList<Manga>(pageSize)
        val videoItems = doc.select("div.video-item, div.item, article.video, div.card, div.module-card, .card-body, li.video")

        fun addItem(href: String, img: org.jsoup.nodes.Element?, titleCandidate: String?): Boolean {
            val videoId = href.substringAfterLast("/").substringBefore("?").substringBefore(".html")
            if (videoId.isBlank() || !seen.add(videoId)) return false
            val coverUrl = (img?.attrOrNull("data-src")
                ?: img?.attrOrNull("data-lazy-src")
                ?: img?.attrOrNull("src"))
                ?.toAbsoluteUrlOrNull(domain)
                ?: ""
            val title = titleCandidate
                ?: img?.attrOrNull("alt")
                ?: href.substringAfterLast("/").ifBlank { videoId.uppercase() }
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
                ),
            )
            return true
        }

        for (item in videoItems) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = link.attrOrNull("href") ?: continue
            val img = item.selectFirst("img")
            val title = img?.attrOrNull("alt")
                ?: link.attrOrNull("title")
                ?: item.selectFirst("h3, h2, .title")?.text()?.trim()
            addItem(href, img, title)
        }

        if (items.isEmpty()) {
            doc.select("a[href*=/video/]").forEach { link ->
                val href = link.attrOrNull("href") ?: return@forEach
                val img = link.selectFirst("img")
                val title = link.attrOrNull("title") ?: link.text().trim().ifBlank { null }
                addItem(href, img, title)
            }
        }

        return items
    }

    private fun logDebug(msg: String) {
        runCatching { println("[Memo] $msg") }
    }

    private suspend fun fetchCategories(): Set<MangaTag> = runCatching {
        val doc = webClient.httpGet("https://$domain/categories/", getRequestHeaders()).parseHtml()
        doc.select("a[href*=/categories/]").mapNotNullToSet { toTag(it) }
    }.getOrDefault(emptySet())

    private fun toTag(element: Element): MangaTag? {
        val key = element.attrOrNull("href")?.substringAfterLast("/")?.substringBefore("?")?.trim()
        val title = element.text().trim()
        if (key.isNullOrBlank() || title.isEmpty()) return null
        return MangaTag(title = title, key = key, source = source)
    }

    private fun parseInfoBlocks(doc: Document): Map<String, String> {
        val infoMap = LinkedHashMap<String, String>()
        doc.select("dl").forEach { dl ->
            val dtList = dl.select("dt")
            val ddList = dl.select("dd")
            val count = minOf(dtList.size, ddList.size)
            for (i in 0 until count) {
                val key = dtList[i].text().trim().removeSuffix(":")
                val value = ddList[i].text().trim()
                if (key.isNotBlank() && value.isNotBlank()) infoMap[key] = value
            }
        }
        doc.select("table tr").forEach { tr ->
            val th = tr.selectFirst("th") ?: tr.selectFirst("td")
            val td = tr.select("td").getOrNull(1)
            val key = th?.text()?.trim()?.removeSuffix(":")
            val value = td?.text()?.trim()
            if (!key.isNullOrBlank() && !value.isNullOrBlank()) infoMap[key] = value
        }
        doc.select("li").forEach { li ->
            val strong = li.selectFirst("strong, b, label")
            val key = strong?.text()?.trim()?.removeSuffix(":")
            val value = strong?.let { li.text().removePrefix(it.text()).trim() }
            if (!key.isNullOrBlank() && !value.isNullOrBlank()) infoMap[key] = value
        }
        return infoMap
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
