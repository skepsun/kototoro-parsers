package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
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
        val doc = webClient.httpGet(("https://$domain" + chapter.url).toHttpUrl()).parseHtml()
        
        // Find iframe
        val iframe = doc.selectFirst("iframe")
        val iframeSrc = iframe?.attr("src")
        
        if (iframeSrc.isNullOrBlank()) {
            throw Exception("Video iframe not found")
        }
        
        // Fetch iframe content
        val iframeContent = webClient.httpGet(iframeSrc.toHttpUrl()).parseHtml().html()
        
        // Extract parameters
        val urlPattern = Regex("""var\s+url\s*=\s*"([^"]+)"""")
        val tPattern = Regex("""var\s+t\s*=\s*"([^"]+)"""")
        val keyPattern = Regex("""var\s+key\s*=\s*OKOK\("([^"]+)"\)""")
        
        val urlVal = urlPattern.find(iframeContent)?.groupValues?.get(1)
        val tVal = tPattern.find(iframeContent)?.groupValues?.get(1)
        val keyVal = keyPattern.find(iframeContent)?.groupValues?.get(1)
        
        if (urlVal.isNullOrBlank() || tVal.isNullOrBlank() || keyVal.isNullOrBlank()) {
            throw Exception("Failed to extract API parameters from iframe")
        }
        
        // Decode key
        val decodedKey = decodeKey(keyVal)
        
        if (decodedKey.isBlank()) {
            throw Exception("Failed to decode key")
        }
        
        // Call API
        val apiUrl = "https://hhjx.hhplayer.com/api.php"
        val headers = Headers.Builder()
            .add("Referer", iframeSrc)
            .add("Origin", "https://hhjx.hhplayer.com")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build()
            
        val formData = mapOf(
            "url" to urlVal,
            "t" to tVal,
            "key" to decodedKey,
            "act" to "0",
            "play" to "1"
        )
            
        val response = webClient.httpPost(apiUrl.toHttpUrl(), formData, headers).parseJson()
        val videoUrl = response.getString("url")
        
        if (videoUrl.isBlank()) {
            throw Exception("No video URL returned from API")
        }

        return listOf(
            ContentPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = null,
                source = source
            )
        )
    }

    private fun decodeKey(t: String): String {
        val ee = mapOf(
            "0Oo0o0Oo" to "a", "1O0bO001" to "b", "1OoCcO1" to "c", "3O0dO0O3" to "d", "4OoEeO4" to "e",
            "5O0fO0O5" to "f", "6OoGgO6" to "g", "7O0hO0O7" to "h", "8OoIiO8" to "i", "9O0jO0O9" to "j",
            "0OoKkO0" to "k", "1O0lO0O1" to "l", "2OoMmO2" to "m", "3O0nO0O3" to "n", "4OoOoO4" to "o",
            "5O0pO0O5" to "p", "6OoQqO6" to "q", "7O0rO0O7" to "r", "8OoSsO8" to "s", "9O0tOoO9" to "t",
            "0OoUuO0" to "u", "1O0vO0O1" to "v", "2OoWwO2" to "w", "3O0xO0O3" to "x", "4OoYyO4" to "y",
            "5O0zO0O5" to "z", "0OoAAO0" to "A", "1O0BBO1" to "B", "2OoCCO2" to "C", "3O0DDO3" to "D",
            "4OoEEO4" to "E", "5O0FFO5" to "F", "6OoGGO6" to "G", "7O0HHO7" to "H", "8OoIIO8" to "I",
            "9O0JJO9" to "J", "0OoKKO0" to "K", "1O0LLO1" to "L", "2OoMMO2" to "M", "3O0NNO3" to "N",
            "4OoOOO4" to "O", "5O0PPO5" to "P", "6OoQQO6" to "Q", "7O0RRO7" to "R", "8OoSSO8" to "S",
            "9O0TTO9" to "T", "0OoUO0" to "U", "1O0VVO1" to "V", "2OoWWO2" to "W", "3O0XXO3" to "X",
            "4OoYYO4" to "Y", "5O0ZZO5" to "Z"
        )
        
        var n = ""
        try {
            val o = String(java.util.Base64.getDecoder().decode(t))
            var i = 0
            while (i < o.length) {
                var l = o[i].toString()
                for ((k, v) in ee) {
                    if (o.startsWith(k, i)) {
                        l = v
                        i += k.length - 1
                        break
                    }
                }
                n += l
                i++
            }
        } catch (e: Exception) {
            return ""
        }
        return n
    }
}
