@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Rawkuma - Japanese RAW manga site
 * URL: https://rawkuma.net/
 */
@ContentSourceParser("RAWKUMA", "Rawkuma", "ja")
internal class Rawkuma(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.RAWKUMA, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("rawkuma.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities get() = ContentListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
    )

    private val searchTitleOnlyKey = ConfigKey.Toggle("search_title_only", "仅搜索标题", true)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(searchTitleOnlyKey)
    }

    // 完整的 genre 列表
    private val defaultGenres = setOf(
        ContentTag("Action", "action", source),
        ContentTag("Adaptions", "adaptions", source),
        ContentTag("Adult", "adult", source),
        ContentTag("Adventure", "adventure", source),
        ContentTag("Animals", "animals", source),
        ContentTag("Comedy", "comedy", source),
        ContentTag("Crime", "crime", source),
        ContentTag("Demons", "demons", source),
        ContentTag("Drama", "drama", source),
        ContentTag("Ecchi", "ecchi", source),
        ContentTag("Fantasy", "fantasy", source),
        ContentTag("Game", "game", source),
        ContentTag("Gender Bender", "gender-bender", source),
        ContentTag("Girls' Love", "girls-love", source),
        ContentTag("Harem", "harem", source),
        ContentTag("Hentai", "hentai", source),
        ContentTag("Historical", "historical", source),
        ContentTag("Horror", "horror", source),
        ContentTag("Isekai", "isekai", source),
        ContentTag("Josei", "josei", source),
        ContentTag("Lolicon", "lolicon", source),
        ContentTag("Magic", "magic", source),
        ContentTag("Martial Arts", "martial-arts", source),
        ContentTag("Mature", "mature", source),
        ContentTag("Mecha", "mecha", source),
        ContentTag("Mystery", "mystery", source),
        ContentTag("Philosophical", "philosophical", source),
        ContentTag("Police", "police", source),
        ContentTag("Psychological", "psychological", source),
        ContentTag("Romance", "romance", source),
        ContentTag("School Life", "school-life", source),
        ContentTag("Sci-fi", "sci-fi", source),
        ContentTag("Seinen", "seinen", source),
        ContentTag("Shotacon", "shotacon", source),
        ContentTag("Shoujo", "shoujo", source),
        ContentTag("Shoujo Ai", "shoujo-ai", source),
        ContentTag("Shounen", "shounen", source),
        ContentTag("Shounen Ai", "shounen-ai", source),
        ContentTag("Slice of Life", "slice-of-life", source),
        ContentTag("Smut", "smut", source),
        ContentTag("Sports", "sports", source),
        ContentTag("Supernatural", "supernatural", source),
        ContentTag("Thriller", "thriller", source),
        ContentTag("Tragedy", "tragedy", source),
        ContentTag("Yaoi", "yaoi", source),
        ContentTag("Yuri", "yuri", source),
    )

    private val genreSlugToId = mapOf(
        "action" to 2,
        "adaptions" to 5114,
        "adult" to 67,
        "adventure" to 3,
        "animals" to 14890,
        "comedy" to 4,
        "crime" to 13850,
        "demons" to 5113,
        "drama" to 5,
        "ecchi" to 31,
        "fantasy" to 6,
        "game" to 1866,
        "gender-bender" to 100,
        "girls-love" to 14375,
        "harem" to 48,
        "hentai" to 5561,
        "historical" to 105,
        "horror" to 54,
        "isekai" to 5110,
        "josei" to 357,
        "lolicon" to 603,
        "magic" to 5111,
        "martial-arts" to 106,
        "mature" to 23,
        "mecha" to 35,
        "mystery" to 24,
        "philosophical" to 15090,
        "police" to 13851,
        "psychological" to 25,
        "romance" to 42,
        "school-life" to 15,
        "sci-fi" to 16,
        "seinen" to 32,
        "shotacon" to 706,
        "shoujo" to 113,
        "shoujo-ai" to 328,
        "shounen" to 7,
        "shounen-ai" to 175,
        "slice-of-life" to 43,
        "smut" to 1402,
        "sports" to 143,
        "supernatural" to 12,
        "thriller" to 13821,
        "thriller-2" to 13822,
        "tragedy" to 26,
        "yaoi" to 207,
        "yuri" to 626
    )

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableTags = defaultGenres,
    )

    override fun getRequestHeaders(): Headers = headersWithReferer("https://$domain/")

    private fun headersWithReferer(referer: String) = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9,ja;q=0.8")
        .add("Referer", referer)
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 优先使用 WordPress REST API
        fetchListViaApi(page, order, filter)?.let { list ->
            logDebug("list page=$page order=$order query='${filter.query.orEmpty()}' api_size=${list.size}")
            return list
        }

        // 备选方案：原来的 Ajax 接口
        fetchListViaAjax(page, order, filter)?.let { list ->
            logDebug("list page=$page order=$order query='${filter.query.orEmpty()}' ajax_size=${list.size}")
            return list
        }

        // 最后的追溯方案：解析 HTML
        val candidates = listOf(
            buildLibraryUrl(page, order, filter) to "https://$domain/library/",
            buildContentPageUrl(page, order, filter) to "https://$domain/manga/",
            buildFallbackUrl(page, order, filter) to "https://$domain/manga/"
        )

        for ((idx, candidate) in candidates.withIndex()) {
            val (url, referer) = candidate
            val list = runCatching {
                val doc = webClient.httpGet(url.toHttpUrl(), headersWithReferer(referer)).parseHtml()
                parseContentList(doc)
            }.getOrElse { emptyList() }
            val altSuffix = if (idx > 0) " (alt$idx)" else ""
            logDebug("list page=$page order=$order query='${filter.query.orEmpty()}' tag=${filter.tags.firstOrNull()?.key} size=${list.size} url=$url$altSuffix")
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private suspend fun fetchListViaApi(page: Int, order: SortOrder, filter: ContentListFilter): List<Content>? {
        // API 方式需要分类 ID。如果存在无法映射为 ID 的 slug，回退到 Ajax/Web 方式以保证过滤生效。
        if (filter.tags.isNotEmpty() && filter.tags.any { !genreSlugToId.containsKey(it.key.lowercase()) }) {
            return null
        }

        val apiUrl = buildApiUrl(page, order, filter)
        val response = runCatching {
            webClient.httpGet(apiUrl.toHttpUrl(), getRequestHeaders())
        }.getOrElse { return null }

        if (response.code != 200) return null

        val jsonArray = runCatching { response.parseJsonArray() }.getOrElse { return null }
        val mangaList = mutableListOf<Content>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            mangaList.add(parseContentJson(item))
        }

        return mangaList
    }

    private fun parseContentJson(item: JSONObject): Content {
        val publicUrl = item.getString("link")
        val relativeUrl = publicUrl.toRelativeUrl(domain)
        val title = item.getJSONObject("title").getString("rendered").unescapeHtml()
        
        // 封面路径探测：1. Embedded, 2. Nested Meta, 3. Top-level Meta
        val coverUrl = item.optJSONObject("_embedded")
            ?.optJSONArray("wp:featuredmedia")
            ?.optJSONObject(0)
            ?.optString("source_url")
            ?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("meta")
                ?.optJSONObject("meta")
                ?.optString("thumbnail")
                ?.takeIf { !it.isNullOrBlank() }
            ?: item.optJSONObject("meta")
                ?.optString("thumbnail")
                ?.takeIf { !it.isNullOrBlank() }
            ?: item.optString("featured_image_url")
                ?.takeIf { it.isNotBlank() }
            ?: ""

        val terms = parseTerms(item)
        val statusText = item.optString("status")
        val state = when {
            statusText.contains("publish") -> ContentState.ONGOING
            else -> null
        }

        return Content(
            id = generateUid(relativeUrl),
            url = relativeUrl,
            publicUrl = publicUrl,
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = terms.tags,
            authors = terms.authors,
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    private data class ParsedTerms(val tags: Set<ContentTag>, val authors: Set<String>)

    private fun parseTerms(item: JSONObject): ParsedTerms {
        val tags = mutableSetOf<ContentTag>()
        val authors = mutableSetOf<String>()

        // 1. 从 _embedded 获取 (最全)
        val embedded = item.optJSONObject("_embedded")
        val wpTerms = embedded?.optJSONArray("wp:term")
        if (wpTerms != null) {
            for (i in 0 until wpTerms.length()) {
                val termArray = wpTerms.optJSONArray(i) ?: continue
                for (j in 0 until termArray.length()) {
                    val term = termArray.getJSONObject(j)
                    val taxonomy = term.optString("taxonomy")
                    val name = term.getString("name").unescapeHtml()
                    val slug = term.getString("slug").lowercase()
                    
                    when (taxonomy) {
                        "genre" -> tags.add(ContentTag(name, slug, source))
                        "series-author", "artist" -> authors.add(name)
                    }
                }
            }
        }

        // 2. 备选方案：从 meta.meta.tax 获取
        if (tags.isEmpty() && authors.isEmpty()) {
            val taxArray = item.optJSONObject("meta")?.optJSONObject("meta")?.optJSONArray("tax")
            if (taxArray != null) {
                for (i in 0 until taxArray.length()) {
                    val term = taxArray.getJSONObject(i)
                    val taxonomy = term.optString("taxonomy")
                    val name = term.optString("name").unescapeHtml()
                    val slug = term.optString("slug")
                    if (name.isBlank()) continue
                    
                    when (taxonomy) {
                        "genre" -> tags.add(ContentTag(name, slug, source))
                        "series-author", "artist" -> authors.add(name)
                    }
                }
            }
        }

        return ParsedTerms(tags, authors)
    }

    private fun buildApiUrl(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
        append("https://$domain/wp-json/wp/v2/manga?")
        append("page=$page")
        append("&per_page=$pageSize")
        
        if (!filter.query.isNullOrBlank()) {
            append("&search=${filter.query!!.urlEncoded()}")
            if (config[searchTitleOnlyKey]) {
                append("&search_columns=post_title") // 限制仅搜索标题
            }
        }
        
        val genreIds = filter.tags.mapNotNull { genreSlugToId[it.key.lowercase()] }.joinToString(",")
        if (genreIds.isNotEmpty()) {
            append("&genre=$genreIds")
        }
        
        val (orderParam, orderBy) = mapOrder(order)
        // API params: orderby (date, id, title), order (asc, desc)
        val apiOrderBy = when (orderBy) {
            "popular" -> "date" // API 不直接支持 Popularity，回退到 Date
            "alphabet" -> "title"
            "newest" -> "id"
            else -> "date"
        }
        append("&orderby=$apiOrderBy")
        append("&order=$orderParam")
        append("&_embed") // 包含更多信息
    }

    private fun String.unescapeHtml(): String = Jsoup.parse(this).text()

    private suspend fun fetchListViaAjax(page: Int, order: SortOrder, filter: ContentListFilter): List<Content>? {
        val (orderParam, orderBy) = mapOrder(order)
        val form = mapOf(
            "action" to "advanced_search",
            "search_term" to filter.query.orEmpty(),
            // 实测 Ajax 分页读取 page 参数，附带 the_page 兼容
            "page" to page.toString(),
            "paged" to page.toString(),
            "the_page" to page.toString(),
            "the_genre" to filter.tags.joinToString(",") { it.key },
            "the_author" to "",
            "the_artist" to "",
            "the_exclude" to "",
            "the_type" to "",
            "the_status" to "",
            "project" to "0",
            "order" to orderParam,
            "orderby" to orderBy,
        )
        val ajaxHeaders = Headers.Builder()
            .add("User-Agent", UserAgents.CHROME_DESKTOP)
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.9,ja;q=0.8")
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/library/")
            .add("Cache-Control", "no-cache")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Upgrade-Insecure-Requests", "1")
            .build()

        val result = runCatching {
            webClient.httpPost("https://$domain/wp-admin/admin-ajax.php".toHttpUrl(), form, ajaxHeaders)
        }.getOrElse { return null }.parseRaw()

        val list = parseAjaxHtml(result)
        if (list != null) return list

        // 若首次请求被 CF 拦截（返回空或挑战页），预热一次 library，再重试一次
        if (!warmupDone) {
            warmupDone = true
            runCatching { webClient.httpGet("https://$domain/library/".toHttpUrl(), headersWithReferer("https://$domain/")) }
            val retryHtml = runCatching {
                webClient.httpPost("https://$domain/wp-admin/admin-ajax.php".toHttpUrl(), form, ajaxHeaders)
            }.getOrElse { return emptyList() }.parseRaw()
            return parseAjaxHtml(retryHtml) ?: emptyList()
        }

        return emptyList()
    }

    private fun parseAjaxHtml(html: String): List<Content>? {
        if (html.isBlank() || html.contains("No results", ignoreCase = true)) return emptyList()
        if (html.contains("challenge-platform", ignoreCase = true) || html.contains("cf-chl-bypass", ignoreCase = true)) {
            return null
        }
        val doc = Jsoup.parse(html, "https://$domain/")
        return parseContentList(doc)
    }

    private companion object {
        var warmupDone = false
    }

    private fun buildLibraryUrl(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
        append("https://$domain/library/?")
        append("the_page=$page")
        append("&the_genre=${filter.tags.joinToString(",") { it.key }}")
        append("&the_author=&the_artist=&the_exclude=&the_type=&the_status=")
        append("&search_term=${filter.query.orEmpty().urlEncoded()}")
        val (orderParam, orderBy) = mapOrder(order)
        append("&project=0&order=$orderParam&orderby=$orderBy")
    }

    private fun buildFallbackUrl(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
        append("https://$domain/manga/?page=$page")

        if (!filter.query.isNullOrEmpty()) {
            append("&search_term=${filter.query!!.urlEncoded()}")
        }

        val (_, orderBy) = mapOrder(order)
        append("&order=$orderBy")

        filter.tags.firstOrNull()?.let { tag ->
            append("&the_genre=${tag.key}")
        }
    }

    private fun buildContentPageUrl(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
        append("https://$domain/manga/page/$page/")

        if (!filter.query.isNullOrEmpty()) {
            append("?s=${filter.query!!.urlEncoded()}")
        } else {
            append("?")
        }

        val (_, orderBy) = mapOrder(order)
        append("order=$orderBy")

        filter.tags.firstOrNull()?.let { tag ->
            append("&the_genre=${tag.key}")
        }
    }

    private fun mapOrder(order: SortOrder): Pair<String, String> = when (order) {
        SortOrder.POPULARITY -> "desc" to "popular"
        SortOrder.NEWEST -> "desc" to "newest"
        SortOrder.ALPHABETICAL -> "asc" to "alphabet"
        else -> "desc" to "updated"
    }

    private fun parseContentList(doc: Document): List<Content> {
        // 网站使用 Tailwind CSS，漫画项在 grid 中
        // 每个项目有 a.w-full.h-full 链接到漫画详情页
        val items = doc.select("a.w-full.h-full, div.bsx a, .listupd .bsx a, article a[href*='/manga/']")
            .filter { it.attr("href").contains("/manga/") && !it.attr("href").contains("/chapter-") }
            .distinctBy { it.attr("href") }
        
        return items.mapNotNull { element ->
            try {
                parseContentItem(element)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseContentItem(element: Element): Content {
        val href = element.attrAsAbsoluteUrl("href")
        val relativeUrl = href.toRelativeUrl(domain)
        
        // 封面图片
        val img = element.selectFirst("img")
        val coverUrl = img?.let {
            it.attrOrNull("data-src") ?: it.attrOrNull("data-lazy-src") ?: it.attr("src")
        }.orEmpty()
        
        // 标题可能在当前元素内或兄弟元素
        val title = element.attrOrNull("title")
            ?: element.attrOrNull("data-title")
            ?: element.selectFirst("h1, h2, h3, .series-title, .tt, .font-medium, .manga-title")
                ?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: element.text().trim()
        
        return Content(
            id = generateUid(relativeUrl),
            url = relativeUrl,
            publicUrl = href,
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val apiUrl = "https://$domain/wp-json/wp/v2/manga?slug=$slug&_embed"
        
        val apiContent = runCatching {
            val response = webClient.httpGet(apiUrl.toHttpUrl(), getRequestHeaders())
            if (response.code == 200) {
                val array = response.parseJsonArray()
                if (array.length() > 0) array.getJSONObject(0) else null
            } else null
        }.getOrNull()

        if (apiContent != null) {
            return getDetailsFromApi(manga, apiContent)
        }

        return getDetailsFromHtml(manga)
    }

    private suspend fun getDetailsFromApi(manga: Content, item: JSONObject): Content {
        val title = item.getJSONObject("title").getString("rendered").unescapeHtml()
        val description = item.getJSONObject("content").getString("rendered")
            .let { Jsoup.parse(it).text().trim() }
        
        val cover = item.optJSONObject("_embedded")
            ?.optJSONArray("wp:featuredmedia")
            ?.optJSONObject(0)
            ?.optString("source_url")
            ?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("meta")
                ?.optJSONObject("meta")
                ?.optString("thumbnail")
                ?.takeIf { !it.isNullOrBlank() }
            ?: manga.coverUrl

        val altTitle = item.optJSONObject("meta")?.optJSONObject("meta")?.optString("alternative_title")
        val altTitles = if (!altTitle.isNullOrEmpty()) setOf(altTitle) else manga.altTitles

        val terms = parseTerms(item)
        val mangaId = item.getInt("id").toString()
        val chapters = fetchChaptersViaAjax(mangaId)

        return manga.copy(
            title = title,
            altTitles = altTitles,
            description = description,
            coverUrl = cover,
            tags = terms.tags,
            authors = terms.authors,
            chapters = chapters,
        )
    }

    private suspend fun getDetailsFromHtml(manga: Content): Content {
        val url = manga.url.let { if (it.startsWith("http")) it else "https://$domain$it" }
        val doc = webClient.httpGet(url.toHttpUrl(), getRequestHeaders()).parseHtml()
        
        val title = doc.selectFirst("meta[property=og:title]")?.attrOrNull("content")
            ?.substringBefore(" - ", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?: doc.select("h1")
                .firstOrNull { !it.text().contains("last updates", ignoreCase = true) }
                ?.text()?.trim()
            ?: manga.title
        
        val altTitle = doc.selectFirst("h1 + div, h1 + span, .alternative, .other-name")?.text()?.trim()
        val altTitles = if (!altTitle.isNullOrEmpty()) setOf(altTitle) else manga.altTitles
        
        val description = doc.selectFirst(".synopsis, .description, div[itemprop=description], .entry-content p")?.text()?.trim()
        
        val cover = doc.selectFirst("img.attachment-post-thumbnail, .thumb img, img[itemprop=image]")?.let {
            it.attrOrNull("data-src") ?: it.attrOrNull("data-lazy-src") ?: it.attr("src")
        } ?: manga.coverUrl
        
        val authors = doc.select("a[href*='/author/'], span:contains(Author) + a, .author a").mapNotNull { 
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.toSet()
        
        val tags = doc.select("a[href*='/genre/']").mapNotNull { 
            val name = it.text().trim()
            val key = it.attr("href").substringAfter("/genre/").removeSuffix("/").lowercase()
            if (name.isNotEmpty() && key.isNotEmpty()) ContentTag(name, key, source) else null
        }.toSet()
        
        val statusText = doc.selectFirst("span:contains(Status) + span, .status, .imptdt:contains(Status)")?.text()?.lowercase()
        val state = when {
            statusText?.contains("ongoing") == true -> ContentState.ONGOING
            statusText?.contains("completed") == true -> ContentState.FINISHED
            else -> manga.state
        }
        
        val mangaId = doc.selectFirst("input#manga_id, input[name=manga_id]")?.attr("value")
            ?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
            ?: extractContentIdFromHx(doc)
            ?: extractContentIdFromScript(doc)
        
        val chapters = if (!mangaId.isNullOrEmpty()) {
            fetchChaptersViaAjax(mangaId)
        } else {
            parseChaptersFromHtml(doc)
        }
        
        return manga.copy(
            title = title,
            altTitles = altTitles,
            description = description,
            coverUrl = cover,
            authors = authors.ifEmpty { manga.authors },
            tags = tags.ifEmpty { manga.tags },
            state = state,
            chapters = chapters,
        )
    }

    private fun extractContentIdFromHx(doc: Document): String? {
        val hxAttr = doc.selectFirst("#chapter-list[hx-get], [data-hx-get], [hx-get]")
            ?.attrOrNull("hx-get")
            ?: doc.selectFirst("#chapter-list")?.attrOrNull("data-hx-get")
        if (!hxAttr.isNullOrBlank()) {
            Regex("manga_id=([0-9]+)").find(hxAttr)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun extractContentIdFromScript(doc: Document): String? {
        // 尝试从 JavaScript 中提取 manga_id
        val scripts = doc.select("script").map { it.html() }
        for (script in scripts) {
            val match = Regex("manga_id[\"'\\s:=]+(\\d+)").find(script)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private suspend fun fetchChaptersViaAjax(mangaId: String): List<ContentChapter> {
        val chapters = mutableListOf<ContentChapter>()
        val seenUrls = mutableSetOf<String>()
        var page = 1
        val maxPages = 50 // 安全限制
        
        while (page <= maxPages) {
            val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php?manga_id=$mangaId&page=$page&action=chapter_list"
            val response = webClient.httpGet(ajaxUrl.toHttpUrl(), getRequestHeaders())
            val html = response.parseRaw()
            
            if (html.isBlank() || html.contains("No chapters") || html.trim() == "0") break
            
            val doc = org.jsoup.Jsoup.parse(html, "https://$domain/")
            val containers = doc.select("div[data-chapter-number], li[data-chapter-number], article[data-chapter-number]")
            val chapterElements = if (containers.isNotEmpty()) {
                containers.mapNotNull { container ->
                    container.selectFirst("a[href*='/chapter-']")?.let { it to container }
                }
            } else {
                doc.select("a[href*='/chapter-']").map { it to null }
            }.distinctBy { it.first.attr("href") }
            logDebug("ajax page=$page elements=${chapterElements.size}")
            
            if (chapterElements.isEmpty()) break
            
            var addedOnPage = 0
            chapterElements.forEach { (element, container) ->
                buildChapter(element, container, chapters.size + 1f, seenUrls)?.let { chapter ->
                    chapters.add(chapter)
                    addedOnPage++
                }
            }
            if (addedOnPage == 0) break
            
            page++
        }
        
        // 按章节号升序排列
        return chapters.distinctBy { it.url }.sortedBy { it.number }
    }

    private fun parseDate(dateText: String?): Long {
        if (dateText.isNullOrEmpty()) return 0L
        return try {
            val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
            dateFormat.parse(dateText)?.time ?: 0L
        } catch (e: Exception) {
            try {
                // 尝试解析相对时间
                when {
                    dateText.contains("hour") -> System.currentTimeMillis() - 3600_000L
                    dateText.contains("day") -> {
                        val days = Regex("(\\d+)").find(dateText)?.value?.toLongOrNull() ?: 1
                        System.currentTimeMillis() - days * 86400_000L
                    }
                    dateText.contains("week") -> {
                        val weeks = Regex("(\\d+)").find(dateText)?.value?.toLongOrNull() ?: 1
                        System.currentTimeMillis() - weeks * 7 * 86400_000L
                    }
                    else -> 0L
                }
            } catch (e: Exception) { 0L }
        }
    }

    private fun buildChapter(
        element: Element,
        container: Element?,
        fallbackNumber: Float,
        seenUrls: MutableSet<String> = mutableSetOf(),
    ): ContentChapter? {
        val href = element.attrOrNull("href")?.toAbsoluteUrl(domain) ?: return null
        if (href.isBlank()) return null
        val relativeUrl = href.toRelativeUrl(domain)
        if (!seenUrls.add(relativeUrl)) return null
        val resolvedContainer = container ?: element.takeIf { it.hasAttr("data-chapter-number") }
            ?: element.parents().firstOrNull { it.hasAttr("data-chapter-number") }
        
        val dateText = element.selectFirst("time, span.chapterdate, .chapterdate")?.text()?.trim()
        val uploadDate = parseDate(dateText)
        
        val rawTitle = extractChapterTitle(element, resolvedContainer)
        val parentDataNum = resolvedContainer?.attrOrNull("data-chapter-number")
        val selfDataNum = element.attrOrNull("data-num")
        val chapterNumber = extractChapterNumber(href, element, rawTitle, resolvedContainer) ?: fallbackNumber
        val title = chooseChapterTitle(rawTitle, chapterNumber)
        val slug = href.substringAfter("/chapter-").substringBefore("/")
        logDebug("chapter href=$href rel=$relativeUrl slug=$slug rawTitle='$rawTitle' data-num=$selfDataNum parent-data-num=$parentDataNum number=$chapterNumber title='$title'")
        
        return ContentChapter(
            id = generateUid(relativeUrl),
            title = title,
            number = chapterNumber,
            volume = 0,
            url = relativeUrl,
            uploadDate = uploadDate,
            source = source,
            scanlator = null,
            branch = null,
        )
    }

    private fun extractChapterTitle(element: Element, container: Element?): String {
        val directTitle = element.selectFirst(".chapter-name, .chapternum, .chapter-number")?.text()?.trim()
        if (!directTitle.isNullOrEmpty()) return directTitle
        
        val containerTitle = container?.selectFirst("span")?.text()?.trim()
        if (!containerTitle.isNullOrEmpty()) return containerTitle
        
        element.select("span").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .firstOrNull { it.contains("chapter", ignoreCase = true) || it.startsWith("ch", ignoreCase = true) }
            ?.let { return it }
        
        val dateText = element.selectFirst("time, span.chapterdate, .chapterdate")?.text()?.trim().orEmpty()
        val combinedText = element.text().trim()
        return if (dateText.isNotEmpty()) combinedText.replace(dateText, "").trim() else combinedText
    }

    private fun extractChapterNumber(href: String, element: Element, rawTitle: String, container: Element?): Float? {
        container?.attrOrNull("data-chapter-number")?.toFloatOrNull()?.let { return it }

        element.attrOrNull("data-num")?.toFloatOrNull()?.let { return it }
        
        href.substringAfter("/chapter-", "").substringBefore("/")
            .substringBefore(".").replace("-", ".").toFloatOrNull()
            ?.let { return it }
        
        if (rawTitle.isNotBlank()) {
            Regex("ch(?:apter)?\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(rawTitle)
                ?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
            Regex("([0-9]+(?:\\.[0-9]+)?)").find(rawTitle)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        }
        
        return null
    }

    private fun formatChapterTitle(chapterNumber: Float): String {
        val chapterInt = chapterNumber.toInt()
        return if (chapterNumber == chapterInt.toFloat()) "Chapter $chapterInt" else "Chapter $chapterNumber"
    }

    private fun chooseChapterTitle(rawTitle: String, chapterNumber: Float): String {
        if (rawTitle.isBlank()) return formatChapterTitle(chapterNumber)
        val rawNumber = Regex("([0-9]+(?:\\.[0-9]+)?)").find(rawTitle)?.groupValues?.get(1)?.toFloatOrNull()
        val chapterInt = chapterNumber.toInt()
        val rawInt = rawNumber?.toInt()
        return if (rawNumber != null && rawInt != chapterInt) {
            formatChapterTitle(chapterNumber)
        } else {
            rawTitle
        }
    }

    private fun logDebug(msg: String) {
        kotlin.runCatching { println("[Rawkuma] $msg") }
    }

    private fun parseChaptersFromHtml(doc: Document): List<ContentChapter> {
        // 章节链接格式: /manga/[slug]/chapter-[number].[id]/
        val containers = doc.select("div[data-chapter-number], li[data-chapter-number], article[data-chapter-number]")
        val chapterElements = if (containers.isNotEmpty()) {
            containers.mapNotNull { container ->
                container.selectFirst("a[href*='/chapter-']")?.let { it to container }
            }
        } else {
            doc.select("a[href*='/chapter-'], .chbox a, .eplister a, ul.clap li a")
                .filter { it.attr("href").contains("/chapter-") }
                .map { it to null }
        }.distinctBy { it.first.attr("href") }
        
        val seenUrls = mutableSetOf<String>()
        return chapterElements.mapIndexedNotNull { index, (element, container) ->
            buildChapter(element, container, index + 1f, seenUrls)
        }.sortedBy { it.number } // 升序排列
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.let { if (it.startsWith("http")) it else "https://$domain$it" }
        val doc = webClient.httpGet(url.toHttpUrl(), getRequestHeaders()).parseHtml()
        
        // 图片可能来自 rcdn.kyut.dev 或其他 CDN
        val images = doc.select("img[src*='rcdn.kyut.dev'], img[src*='cdn'], #readerarea img, .entry-content img, article img")
            .filter { 
                val src = it.attr("src")
                src.contains(".jpg") || src.contains(".png") || src.contains(".webp") || src.contains("/uploads/")
            }
            .distinctBy { it.attr("src") }
        
        return images.mapIndexed { index, img ->
            val imgUrl = img.attrOrNull("data-src") ?: img.attrOrNull("data-lazy-src") ?: img.attr("src")
            ContentPage(
                id = generateUid("${chapter.url}-$index"),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }
}
