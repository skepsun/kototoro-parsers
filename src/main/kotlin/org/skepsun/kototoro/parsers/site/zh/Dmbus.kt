package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.json.JSONObject
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet
import java.util.Locale
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 动漫巴士视频解析器
 * 网站: https://dmbus.cc/
 */
@ContentSourceParser("DMBUS", "动漫巴士", "zh", type = ContentType.VIDEO)
internal class Dmbus(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.DMBUS, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("dmbus.cc")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // 按时间
        SortOrder.POPULARITY,   // 按人气
        SortOrder.RATING        // 按评分
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = true,  // 启用多选
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        // 类型 - 国漫、日漫、欧美、电影
        val categories = listOf(
            ContentTag("国漫", "cat:1", source),
            ContentTag("日漫", "cat:2", source),
            ContentTag("欧美", "cat:3", source),
            ContentTag("电影", "cat:4", source)
        )
        
        // 类别 (Tags) - 题材标签
        val genres = listOf(
            "全部", "奇幻", "战斗", "玄幻", "穿越", "科幻", "武侠", "热血", "耽美", "搞笑", 
            "动态漫画", "冒险", "恋爱", "校园", "后宫", "治愈", "百合", "机战", "悬疑", 
            "推理", "恐怖", "运动", "魔法", "神魔", "励志", "历史", "真人"
        ).map { ContentTag(it, "genre:$it", source) }

        // 时间 - 使用 tags
        val years = (2025 downTo 2015).map { it.toString() }.map { year ->
            ContentTag(year, "year:$year", source)
        }

        val allTags = (categories + genres + years).toSet()
        val tagGroups = listOf(
            ContentTagGroup("地区", categories.toSet()),
            ContentTagGroup("题材", genres.toSet()),
            ContentTagGroup("年份", years.toSet()),
        )

        return ContentListFilterOptions(
            availableTags = allTags,
            tagGroups = tagGroups,
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        .add("Referer", "https://www.google.com/") // 模拟从Google跳转
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        return when {
            !filter.query.isNullOrEmpty() -> {
                // 搜索功能
                val url = "https://$domain/s----------.html?wd=${filter.query.urlEncoded()}&page=$page"
                parseSearchResults(webClient.httpGet(url, getRequestHeaders()).parseHtml())
            }
            else -> {
                // URL format: /show-{cat}--{sort}-{genre}--{year}-.html
                var catId = "1" // Default to Guoman
                var genre = ""
                var year = ""
                
                // Extract category, genre, and year from tags
                filter.tags.forEach { tag ->
                    when {
                        tag.key.startsWith("cat:") -> catId = tag.key.removePrefix("cat:")
                        tag.key.startsWith("year:") -> year = tag.key.removePrefix("year:")
                        tag.key.startsWith("genre:") -> {
                            val g = tag.key.removePrefix("genre:")
                            if (g != "全部") {
                                genre = g
                            }
                        }
                    }
                }
                
                // Map sort order
                val sort = when (order) {
                    SortOrder.UPDATED -> "time"      // 按时间
                    SortOrder.POPULARITY -> "hits"   // 按人气
                    SortOrder.RATING -> "score"      // 按评分
                    else -> "time"
                }
                
                val encodedGenre = if (genre.isNotEmpty()) java.net.URLEncoder.encode(genre, "UTF-8") else ""
                val url = "https://$domain/show-$catId--$sort-$encodedGenre--$year-.html"
                val pageUrl = if (page > 1) "$url?page=$page" else url

                parseCategoryList(webClient.httpGet(pageUrl.toHttpUrl(), getRequestHeaders()).parseHtml())
            }
        }
    }

    private fun parseSearchResults(doc: Document): List<Content> {
        return doc.select(".video-item, .list-item, .item, .search-result-item, .stui-vodlist__thumb, .fed-list-item, .module-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            if (!href.contains("/v/") && !href.contains("/p/")) return@mapNotNull null
            
            val title = item.selectFirst(".title, .name, h3, h2, .video-title")?.text()?.trim() 
                ?: link.attr("title")?.trim() 
                ?: link.text().trim()
                ?: ""
            
            val cover = item.selectFirst("img[src], img[data-src]")?.attrAsAbsoluteUrlOrNull("src")
                ?: item.selectFirst("img[data-src]")?.attrAsAbsoluteUrlOrNull("data-src")
                ?: item.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src")

            Content(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = cover ?: "",
                largeCoverUrl = null,
                authors = emptySet(),
                tags = emptySet(),
                state = null,
                description = null,
                contentRating = ContentRating.SAFE,
                source = source,
                rating = RATING_UNKNOWN,
            )
        }
    }

    private fun parseCategoryList(doc: Document): List<Content> {
        return doc.select(".v_list .item, .video-list .item, .list-container .item, .videos .item, .video-item, .stui-vodlist__thumb, .fed-list-item, .module-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            if (!href.contains("/v/") && !href.contains("/p/")) return@mapNotNull null
            
            val title = item.selectFirst(".title, .name, h3, .video-name")?.text()?.trim() 
                ?: link.attr("title")?.trim() 
                ?: link.text().trim()
                ?: ""
            
            val cover = item.selectFirst("[data-bg]")?.attrAsAbsoluteUrlOrNull("data-bg")
                ?: item.selectFirst("img[src], img[data-src]")?.attrAsAbsoluteUrlOrNull("src")
                ?: item.selectFirst("img[data-src]")?.attrAsAbsoluteUrlOrNull("data-src")
                ?: item.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src")

            Content(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = cover ?: "",
                largeCoverUrl = null,
                authors = emptySet(),
                tags = emptySet(),
                state = null,
                description = null,
                contentRating = ContentRating.SAFE,
                source = source,
                rating = RATING_UNKNOWN,
            )
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val title = doc.selectFirst(".v_title")?.text() ?: manga.title
        val description = doc.select("#intro").text().replace("剧情：", "").trim()
        val cover = doc.selectFirst(".v_content .cover img")?.attr("src") ?: manga.coverUrl
        
        val chapters = mutableListOf<ContentChapter>()
        
        // Parse sources (tabs)
        val sources = doc.select(".tab_control.play_from li").map { it.text() }
        val chapterLists = doc.select("#play_list .play_list")
        
        if (sources.isNotEmpty() && chapterLists.isNotEmpty()) {
            for (i in sources.indices) {
                if (i >= chapterLists.size) break
                val sourceName = sources[i]
                val list = chapterLists[i]
                
                val sourceChapters = list.select("li").map { li ->
                    val a = li.selectFirst("a")
                    val url = a?.attr("href") ?: ""
                    val name = a?.text() ?: ""
                    // Extract number from title, e.g. "第25话" -> 25.0
                    val numberMatcher = Regex("(\\d+)").find(name)
                    val number = numberMatcher?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    
                    ContentChapter(
                        id = generateUid(url),
                        title = name,
                        number = number,
                        volume = 0,
                        url = url,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = sourceName, // Group by source
                        source = source
                    )
                }
                // Reverse chapters to be Newest First (descending order)
                // The site lists them as 1, 2, 3... so we reverse to get ...3, 2, 1
                chapters.addAll(sourceChapters.reversed())
            }
        } else {
            // Fallback for single list if structure is different
             doc.select(".play_list li").forEach { li ->
                val a = li.selectFirst("a")
                val url = a?.attr("href") ?: ""
                val name = a?.text() ?: ""
                val numberMatcher = Regex("(\\d+)").find(name)
                val number = numberMatcher?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                
                chapters.add(ContentChapter(
                    id = generateUid(url),
                    title = name,
                    number = number,
                    volume = 0,
                    url = url,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                ))
            }
            chapters.reverse()
        }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            tags = emptySet(),
            chapters = chapters,
            contentRating = ContentRating.SAFE,
        )
    }

    private fun parseVideoUrls(doc: Document): List<String> {
        val videoUrls = mutableListOf<String>()
        
        // 方法1: 直接解析video标签
        doc.select("video source[src]").forEach { source ->
            source.attr("src").takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
        }
        
        // 方法2: 解析iframe并解码
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("hhplayer.com")) {
                // 需要进一步获取iframe内容并解码
                // 注意：这里不能直接发起网络请求，因为getPages是同步的，但我们可以返回iframe地址，
                // 或者尝试在这里进行简单的解码如果可能。
                // 由于需要发起网络请求获取iframe内容，这里我们返回iframe地址，
                // 但通常Kotatsu不支持直接播放iframe页面。
                // 我们需要实现一个更复杂的逻辑，或者在getPages中发起请求。
                // 由于getPages是suspend函数，我们可以发起请求。
            }
            src.takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
        }
        
        return videoUrls.distinct().filter { url ->
            url.isNotBlank() && (url.startsWith("http") || url.startsWith("data:"))
        }
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val pageUrl = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(pageUrl.toHttpUrl()).parseHtml()

        // 章节页通过 iframe 引入 hhplayer 播放器
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        if (iframeSrc.isNullOrBlank()) {
            throw ParseException("视频播放器 iframe 缺失", pageUrl)
        }
        val iframeUrl = when {
            iframeSrc.startsWith("//") -> "https:$iframeSrc"
            iframeSrc.startsWith("http") -> iframeSrc
            else -> "https://$domain$iframeSrc"
        }

        // 播放器页面把播放参数以 JSON 打在 window.__HHJX_BOOTSTRAP__（新版协议；
        // 旧版 var url/t/key = OKOK(...) 混淆式脚本已废弃，无需再解码）
        val iframeContent = webClient.httpGet(iframeUrl.toHttpUrl()).parseRaw()
        val bootstrap = Regex("""window\.__HHJX_BOOTSTRAP__\s*=\s*(\{.*?\});""")
            .find(iframeContent)?.groupValues?.get(1)
            ?: throw ParseException("无法从播放器页面提取播放参数", iframeUrl)
        val boot = JSONObject(bootstrap)
        val hhUrl = boot.optString("url")
        val hhT = boot.opt("t")
        val hhKey = boot.optString("key")
        if (hhUrl.isBlank() || hhT == null || hhKey.isBlank()) {
            throw ParseException("播放参数不完整", iframeUrl)
        }

        // 调用 hhplayer 的 JSON 解析接口换取直链
        val apiHost = "https://${iframeUrl.toHttpUrl().host}"
        val apiUrl = "$apiHost/api/parse"
        val body = JSONObject().apply {
            put("url", hhUrl)
            put("t", hhT)
            put("key", hhKey)
            put("client_fallback", false)
        }
        val headers = Headers.Builder()
            .add("Referer", iframeUrl)
            .add("Origin", apiHost)
            .build()
        val result = webClient.httpPost(apiUrl.toHttpUrl(), body, headers).parseJson()
        if (result.optInt("code") != 200) {
            throw ParseException("站点解析接口未返回成功", apiUrl)
        }
        val videoUrl = result.optString("url")
        if (videoUrl.isBlank()) {
            throw ParseException("站点解析接口未返回播放地址", apiUrl)
        }
        if (result.optString("ext") == "youku") {
            // 优酷线路需要在浏览器端解析 CNA/签名参数，无头环境无法完成
            throw ParseException("该章节为优酷线路，暂不支持直接解析", apiUrl)
        }

        return listOf(
            ContentPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = null,
                source = source,
            )
        )
    }
}
