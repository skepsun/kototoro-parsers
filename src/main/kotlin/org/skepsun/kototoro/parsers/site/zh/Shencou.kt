package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.util.*
import java.net.URLEncoder
import java.util.EnumSet

/**
 * 神凑轻小说文库（wowenku.com）。
 *
 * 站点路径约定：
 * - 书籍页 `/books/read_{id}.html`，简介在「内容简介：」与「本书公告：」之间；
 * - 目录页 `/read/{id/1000}/{id}/index.html`，卷标题为 `.zjbox h2`，章节为 `.zjlist4 a`；
 * - 章节页 `/read/{id/1000}/{id}/{chapterId}.html`，正文包裹在 `<!--go-->` / `<!--over-->` 中。
 *
 * 旧版 `/article/{id}.html` 等地址已失效，站点会返回 200 + 自己的「404错误，页面不存在，或文章已删除」
 * 提示页，必须识别出来，否则简介和正文会被这段警告文本填满。
 */
@ContentSourceParser("SHENCOU", "神凑轻小说", "zh", type = ContentType.NOVEL)
internal class Shencou(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.SHENCOU, pageSize = 30),
    Interceptor,
    ContentParserAuthProvider,
    ContentParserCredentialsAuthProvider {

    // shencou.com 已迁移到 wowenku.com（前者仍是 Cloudflare 后面的旧镜像），默认使用新域名
    override val configKeyDomain = ConfigKey.Domain("www.wowenku.com", "www.shencou.com")

    // Cloudflare 会拦非浏览器 UA，默认给一个真实 Edge 桌面 UA，用户可在来源设置里覆盖
    override val userAgentKey = ConfigKey.UserAgent(BROWSER_UA)

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

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .addBrowserClientHints(config[userAgentKey])
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
        val url = detailsUrl(manga)
        val doc = fetchDocument(url)
        // 站点对已删除/不存在的作品返回 200 + 自己的 404 提示页，不能当成简介解析
        if (isSiteErrorPage(doc)) {
            throw NotFoundException("神凑轻小说：作品不存在或已被删除", url)
        }
        val details = parseDetails(doc, manga)
        val chapters = manga.articleId()?.let { id ->
            try {
                fetchChapters(id)
            } catch (_: NotFoundException) {
                // 作品能打开但目录页缺失时，至少保留简介
                emptyList()
            }
        }.orEmpty()
        return details.copy(chapters = chapters)
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        val text = doc.body()?.text().orEmpty()
        val coverUrl = doc.selectFirst("img[src*=\"/files/article/image/\"]")
            ?.attrAsAbsoluteUrlOrNull("src")
            ?: manga.coverUrl
        val description = doc.extractDescription()
        val author = AUTHOR_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val state = parseState(text)
        return manga.copy(
            coverUrl = coverUrl,
            description = description ?: manga.description,
            authors = author?.let { setOf(it) } ?: manga.authors,
            state = state ?: manga.state,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = "https://$domain${chapter.url}"
        val doc = fetchDocument(url)
        val html = when {
            isSiteErrorPage(doc) -> buildMessageHtml("该章节不存在或已被删除")
            else -> parseChapterHtml(doc, chapter) ?: buildMessageHtml("无法解析该章节内容")
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

    /**
     * 正文在 `<!--go-->` … `<!--over-->` 之间；解析失败返回 null，由调用方决定如何提示，
     * 避免把站点的错误页当作正文展示。
     */
    internal fun parseChapterHtml(doc: Document, chapter: ContentChapter): String? {
        val raw = doc.html()
        val fragment = GO_REGEX.find(raw)?.groupValues?.get(1)
            ?: doc.selectFirst("#content, #articlecontent, .showtxt, #BookText")?.html()
            ?: return null
        val content = Jsoup.parseBodyFragment(fragment, doc.baseUri()).body()
        content.select("script, style, ins, iframe, .db_div, center").remove()
        content.getElementsMatchingOwnText("上一章|下一章|章节目录|书籍介绍|加入书架|投票推荐").remove()
        content.select("img[src]").forEach { img ->
            val absolute = img.attrAsAbsoluteUrlOrNull("src") ?: return@forEach
            img.attr("src", absolute)
            img.attr("referrerpolicy", "no-referrer")
        }

        val body = content.html()
            .replace(Regex("(<br\\s*/?>([\\s\\n\\r]|&nbsp;)*){2,}"), "<br>")
            .replace(Regex("^([\\s\\n\\r]|&nbsp;|<br\\s*/?>)+"), "") // Remove leading junk
            .replace(Regex("[\\n\\r]+"), "") // Strip all raw newlines to prevent pre-wrap ghost lines
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>body{padding:16px;padding-bottom:50px;line-height:1.8;}</style></head><body>")
            if (!chapter.title.isNullOrBlank()) {
                append("<h1>${chapter.title}</h1>")
            }
            append(body)
            append("</body></html>")
        }
    }

    private suspend fun fetchChapters(articleId: Int): List<ContentChapter> {
        val indexUrl = "https://$domain/read/${articleId / 1000}/$articleId/index.html"
        val doc = fetchDocument(indexUrl)
        if (isSiteErrorPage(doc)) return emptyList()
        return parseChapters(doc)
    }

    internal fun parseChapters(doc: Document): List<ContentChapter> {
        val chapters = mutableListOf<ContentChapter>()
        var currentVolume = 0
        // Volume headers (.zjbox h2) and chapter links come in document order
        doc.select(".zjbox h2, .zjlist a, .zjlist4 a, .chapterlist a, td.ccss a").forEach { el ->
            when (el.tagName()) {
                "h2" -> currentVolume++
                "a" -> {
                    val href = el.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                    val title = el.text().trim()
                    if (title.isEmpty() || !href.startsWith("/read/")) return@forEach
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
        }
        return chapters
    }

    private suspend fun fetchDocument(url: String): Document {
        val response = webClient.httpGet(url, getRequestHeaders())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        return response.parseHtml()
    }

    private fun detailsUrl(manga: Content): String {
        val id = manga.articleId()
        return if (id != null) "https://$domain/books/read_$id.html" else manga.url.toAbsoluteUrl(domain)
    }

    private fun Content.articleId(): Int? {
        val match = ARTICLE_ID_REGEX.find(url) ?: FALLBACK_ID_REGEX.find(url) ?: return null
        return match.groupValues[1].toIntOrNull()
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

        try {
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
        val id = Regex("(\\d+)").find(mangaUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        val iid = id / 1000
        return "https://$domain/files/article/image/$iid/$id/${id}s.jpg"
    }

    internal fun isSiteErrorPage(doc: Document): Boolean {
        if (doc.title().contains("404", ignoreCase = true)) return true
        val text = doc.body()?.text().orEmpty()
        return text.contains("404错误，页面不存在") ||
            text.contains("页面不存在，或文章已删除") ||
            text.contains("该文章不存在")
    }

    private fun Document.extractDescription(): String? {
        val text = body()?.text().orEmpty()
        val start = text.indexOf("内容简介：").takeIf { it >= 0 } ?: return null
        val rest = text.substring(start + "内容简介：".length)
        val end = DESCRIPTION_END_MARKERS.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val description = if (end != null) rest.substring(0, end) else rest
        return description.trim().takeIf { it.isNotEmpty() }
    }

    private fun parseState(text: String): ContentState? {
        val raw = STATE_REGEX.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return when {
            raw.contains("连载") -> ContentState.ONGOING
            raw.contains("完") -> ContentState.FINISHED
            else -> null
        }
    }

    private fun String.toRelativePath(): String {
        val url = toHttpUrlOrNull() ?: return if (startsWith("/")) this else "/$this"
        return buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { append('?').append(it) }
        }
    }

    private fun String.toDataUrl(context: ContentLoaderContext): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun buildMessageHtml(message: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8"/>
        <style>body{font-family:sans-serif;padding:16px;line-height:1.8;}</style>
        </head><body><h1>提示</h1><p>$message</p></body></html>
    """.trimIndent()

    private companion object {
        // Cloudflare 放行的真实浏览器 UA；用户可在来源设置里覆盖
        private const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        private val GO_REGEX = Regex("<!--go-->(.*?)<!--over-->", RegexOption.DOT_MATCHES_ALL)
        private val AUTHOR_REGEX = Regex("""作者：([^\s&·]{1,20})""")
        private val STATE_REGEX = Regex("""写作进度：([^\s]{1,12})""")
        private val ARTICLE_ID_REGEX = Regex("""(?:read_|/article/|/read/\d+/)(\d+)""")
        private val FALLBACK_ID_REGEX = Regex("""(\d+)""")
        private val DESCRIPTION_END_MARKERS = listOf("本书公告：", "最新章节：")
    }
}
