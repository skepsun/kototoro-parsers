package org.skepsun.kototoro.parsers.site.zh

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.net.URLEncoder
import java.util.EnumSet

@ContentSourceParser("SHENCOU", "神凑轻小说", "zh", type = ContentType.NOVEL)
internal class Shencou(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.SHENCOU, pageSize = 30),
    Interceptor,
    ContentParserAuthProvider,
    ContentParserCredentialsAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("www.shencou.com")
    override val userAgentKey = ConfigKey.UserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val groups = mutableListOf<ContentTagGroup>()

        // Categories (Class 1-12)
        val categoryTags = linkedSetOf(
            ContentTag("全部", "class:0", source),
            ContentTag("电击文库", "class:1", source),
            ContentTag("富士见文库", "class:2", source),
            ContentTag("角川文库", "class:3", source),
            ContentTag("MFJ文库", "class:4", source),
            ContentTag("Fami通文库", "class:5", source),
            ContentTag("GA文库", "class:6", source),
            ContentTag("HJ文库", "class:7", source),
            ContentTag("一迅社", "class:8", source),
            ContentTag("集英社", "class:9", source),
            ContentTag("少女文库", "class:10", source),
            ContentTag("SF文库", "class:11", source),
            ContentTag("讲谈社", "class:12", source),
        )
        groups += ContentTagGroup("分类", categoryTags)

        // Rankings (Sort)
        val rankTags = linkedSetOf(
            ContentTag("默认", "sort:default", source),
            ContentTag("总排行榜", "sort:allvisit", source),
            ContentTag("总推荐榜", "sort:allvote", source),
            ContentTag("月排行榜", "sort:monthvisit", source),
            ContentTag("月推荐榜", "sort:monthvote", source),
            ContentTag("周排行榜", "sort:weekvisit", source),
            ContentTag("周推荐榜", "sort:weekvote", source),
            ContentTag("最新入库", "sort:postdate", source),
            ContentTag("最近更新", "sort:lastupdate", source),
            ContentTag("总收藏榜", "sort:goodnum", source),
            ContentTag("字数排行", "sort:size", source),
        )
        groups += ContentTagGroup("榜单", rankTags)

        return ContentListFilterOptions(
            availableTags = (categoryTags + rankTags).toSet(),
            tagGroups = groups,
        )
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Sec-CH-UA", "\"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"macOS\"")
        .build()
        
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Referer") != null) return chain.proceed(request)
        // Ensure Referer is set for all requests including images
        val newRequest = request.newBuilder()
            .header("Referer", "https://$domain/")
            .build()
        return chain.proceed(newRequest)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            val encodedQuery = try {
                URLEncoder.encode(query, "GBK")
            } catch (e: Exception) {
                query
            }
            val url = "https://$domain/modules/article/search.php?searchtype=articlename&searchkey=$encodedQuery&page=$page"
            // Search often returns a list but sometimes redirects to the book if only 1 result. 
            // Handling list parsing:
            val response = webClient.httpGet(url, getRequestHeaders())
            if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                context.requestBrowserAction(this, url)
            }
            val doc = response.parseHtml()
            val list = parseSearchList(doc)
            if (list.isEmpty()) {
                val bodySnippet = doc.body().outerHtml().take(1000).replace("\n", " ")
                println("Shencou search empty: url=$url code=${response.code} body=$bodySnippet")
            } else {
                println("Shencou search: url=$url code=${response.code} results=${list.size}")
            }
            return list
        }

        // Filtering
        val tagMap = filter.tags.associate { it.key.substringBefore(":") to it.key.substringAfter(":") }
        val sort = tagMap["sort"] ?: "default"
        val clazz = tagMap["class"] ?: "0"

        val url = when {
            sort != "default" -> "https://$domain/modules/article/toplist.php?sort=$sort&page=$page"
            clazz != "0" -> "https://$domain/modules/article/articlelist.php?class=$clazz&page=$page"
            else -> "https://$domain/modules/article/articlelist.php?page=$page" // Fallback to all
        }

        val response = webClient.httpGet(url, getRequestHeaders())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        val doc = response.parseHtml()
        val list = parseExploreList(doc)
        if (list.isEmpty()) {
            val bodySnippet = doc.body().outerHtml().take(1000).replace("\n", " ")
            println("Shencou explore empty: url=$url code=${response.code} body=$bodySnippet")
        } else {
            println("Shencou explore: url=$url code=${response.code} results=${list.size}")
        }
        return list
    }

    private fun parseSearchList(doc: Document): List<Content> {
        val list = mutableListOf<Content>()
        // Search table structure: table tr (skip header) or grid div
        val rows = doc.select("table.grid tr, table tr")
        rows.drop(1).forEach { tr -> // Skip header
            val tds = tr.select("td")
            if (tds.size < 3) return@forEach
            
            // Layout typically: Title, Latest Chapter, Author, Size, Update Time, Status
            val aTitle = tds[0].selectFirst("a") ?: return@forEach
            val href = aTitle.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
            val title = aTitle.text().trim()
            val author = tds.getOrNull(2)?.text()?.trim()
            
            list += Content(
                id = this@Shencou.generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = aTitle.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = sourceContentRating,
                coverUrl = generateCoverUrl(href),
                tags = emptySet(),
                state = null,
                authors = setOfNotNull(author),
                source = this@Shencou.source,
            )
        }
        return list
    }

    private fun parseExploreList(doc: Document): List<Content> {
        val list = mutableListOf<Content>()
        // Grid layout: div containing book info
        doc.select("div[style*=\"width:382px\"]").forEach { div ->
            val aTitle = div.selectFirst("b a") ?: div.selectFirst("a:has(img)") ?: return@forEach
            val href = aTitle.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
            val title = (div.selectFirst("b a")?.text() ?: div.selectFirst("img")?.attr("alt") ?: "").trim()
            if (title.isEmpty()) return@forEach
            
            val infoText = div.text()
            val author = Regex("著作作者：([^·\\s]+)").find(infoText)?.groupValues?.get(1)
            
            list += Content(
                id = this@Shencou.generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = aTitle.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = sourceContentRating,
                coverUrl = div.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src") ?: generateCoverUrl(href),
                tags = emptySet(),
                state = null,
                authors = setOfNotNull(author),
                source = this@Shencou.source,
            )
        }
        if (list.isNotEmpty()) return list

        // Explore table structure: table tr
        val rows = doc.select("table.grid tr, table tr")
        // Check if table layout
        if (rows.isNotEmpty()) {
             rows.drop(1).forEach { tr ->
                val tds = tr.select("td")
                if (tds.size < 3) return@forEach
                 
                val aTitle = tds[0].selectFirst("a") ?: return@forEach
                val href = aTitle.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                val listContent = Content(
                    id = this@Shencou.generateUid(href),
                    title = aTitle.text().trim(),
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = aTitle.absUrl("href"),
                    rating = RATING_UNKNOWN,
                    contentRating = sourceContentRating,
                    coverUrl = generateCoverUrl(href),
                    tags = emptySet(),
                    state = null,
                    authors = setOfNotNull(tds.getOrNull(2)?.text()?.trim()),
                    source = this@Shencou.source,
                )
                list += listContent
             }
        }
        
        return list
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = "https://$domain${manga.url}"
        val response = webClient.httpGet(url, getRequestHeaders())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        val doc = response.parseHtml()
        
        // JSON: #content table tr:nth-of-type(3) ...
        // Replicating typical PC site structure manually or using selectors
        // Usually content is in a table.
        // Title often h1 or in table
        
        // Try finding title/author/desc
        val table = doc.selectFirst("table")
        
        // Fallback or precise selection
        // Cover: 
        val coverUrl = doc.selectFirst("img[src*=\"/files/article/image/\"]")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
        
        // Intro: 
        val desc = table?.text()?.substringAfter("内容简介：")?.substringBefore("本书公告：")?.trim() 
                   ?: doc.body().text() // Fallback
                   
        // Author: often in the text or specific cell
        // Tag extraction if possible:
        val tags = linkedSetOf<ContentTag>()
        // Try parsing meta info text blocks
        val infoText = table?.text().orEmpty()
        val author = Regex("作者：(\\S+)").find(infoText)?.groupValues?.get(1) ?: manga.author
        
        val state = when {
            infoText.contains("已完成") -> ContentState.FINISHED
            infoText.contains("连载中") -> ContentState.ONGOING
            else -> null
        }

        val idStr = Regex("(\\d+)").find(manga.url)?.groupValues?.get(1)
        val chapters = if (idStr != null) {
            val id = idStr.toInt()
            val iid = id / 1000
            val indexUrl = "https://$domain/read/$iid/$id/index.html"
            val indexResponse = webClient.httpGet(indexUrl, getRequestHeaders())
            if (CloudFlareHelper.checkResponseForProtection(indexResponse) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                context.requestBrowserAction(this, indexUrl)
            }
            fetchChapters(indexResponse.parseHtml(), manga.url)
        } else {
            fetchChapters(doc, manga.url)
        }

        return manga.copy(
            coverUrl = coverUrl,
            description = desc?.trim(),
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private fun fetchChapters(doc: Document, mangaUrl: String): List<ContentChapter> {
        val chapters = mutableListOf<ContentChapter>()
        var currentVolume = 0
        
        // Select all elements that might be volume headers or chapter links in order
        // Volume headers are typically in .zjbox h2
        val elements = doc.select(".zjbox h2, .zjlist a, .zjlist4 a, .chapterlist a, td.ccss a")
        
        elements.forEach { el ->
            if (el.tagName() == "h2") {
                // If it's a volume header, increment volume index
                // Note: We check if it's inside a volume header container to be sure
                if (el.closest(".zjbox") != null || el.closest(".tt") != null) {
                    currentVolume++
                }
            } else if (el.tagName() == "a") {
                val href = el.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                val title = el.text().trim()
                if (title.isEmpty()) return@forEach
                
                chapters += ContentChapter(
                    id = this@Shencou.generateUid(href),
                    title = title,
                    number = chapters.size + 1f,
                    volume = currentVolume,
                    url = href,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = this@Shencou.source,
                )
            }
        }
        return chapters
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = "https://$domain${chapter.url}"
        val response = webClient.httpGet(url, getRequestHeaders())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        val doc = response.parseHtml()
        
        // Content extraction
        val rawHtml = doc.html()
        val commentContent = Regex("<!--go-->(.*?)<!--over-->", RegexOption.DOT_MATCHES_ALL)
            .find(rawHtml)?.groupValues?.get(1)
        
        val content = if (commentContent != null) {
            Jsoup.parseBodyFragment(commentContent).body()
        } else {
            doc.selectFirst("#content, #articlecontent, .showtxt, #BookText") ?: doc.body()
        }
        
        // Clean up remaining junk if any (especially for fallback)
        content.select("script, style, .db_div, table, div[align=right], h1, center, [id^=BookSee], #guild, #breadCrumb").remove() 
        content.getElementsMatchingOwnText("上一章|下一章|章节目录|书籍介绍|加入书架|投票推荐").remove()
        
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>body{padding-bottom:50px;}</style></head><body>")
            if (!chapter.title.isNullOrBlank()) {
                append("<h1>${chapter.title}</h1>")
            }
            // Preserve line breaks and normalize spacing aggressively
            val text = content.html()
                .replace(Regex("(<br\\s*/?>([\\s\\n\\r]|&nbsp;)*){2,}"), "<br>")
                .replace(Regex("^([\\s\\n\\r]|&nbsp;|<br\\s*/?>)+"), "") // Remove leading junk
                .replace(Regex("[\\n\\r]+"), "") // Strip all raw newlines to prevent pre-wrap ghost lines
            append(text)
            append("</body></html>")
        }
        
        return listOf(
            ContentPage(
                id = this@Shencou.generateUid(chapter.url),
                url = html.toDataUrl(context),
                preview = null,
                source = this@Shencou.source,
            )
        )
    }

    override val authUrl: String = "https://$domain/login.php"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any { it.name == "jieqiUserInfo" }
    }

    override suspend fun getUsername(): String {
        val cookies = context.cookieJar.getCookies(domain)
        val userInfo = cookies.find { it.name == "jieqiUserInfo" }?.value ?: throw AuthRequiredException(source)
        // jieqiUserInfo is often URL encoded and contains the username
        return try {
            java.net.URLDecoder.decode(userInfo, "GBK").substringAfter("jieqiUserName=").substringBefore("&")
        } catch (e: Exception) {
            "User"
        }
    }

    override suspend fun login(username: String, password: String): Boolean {
        val url = "https://$domain/login.php?do=submit"
        
        val body = mapOf(
            "username" to username,
            "password" to password,
            "usecookie" to "315360000",
            "action" to "login"
        )
        
        val response = try {
            webClient.httpPost(url.toHttpUrl(), body, getRequestHeaders())
        } catch (e: Exception) {
            return false
        }
        
        return isAuthorized()
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
    
    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private fun generateCoverUrl(mangaUrl: String): String {
        // URL pattern: /article/123.html or /files/article/html/0/123/index.html
        // Regex extract number
        val idStr = Regex("(\\d+)").find(mangaUrl)?.groupValues?.get(1) ?: return ""
        val id = idStr.toIntOrNull() ?: return ""
        val iid = id / 1000
        return "https://www.shencou.com/files/article/image/$iid/$id/${id}s.jpg"
    }

    private fun String.toRelativePath(): String {
         return this.replace(Regex("^https?://(www\\.)?shencou\\.com/?"), "/")
            .let { if (it.startsWith("/")) it else "/$it" }
    }
    
    private fun String.toDataUrl(context: ContentLoaderContext): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
