@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
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
@MangaSourceParser("RAWKUMA", "Rawkuma", "ja")
internal class Rawkuma(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.RAWKUMA, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("rawkuma.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities get() = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
    )

    // 完整的 genre 列表（从网站 HTML 提取）
    private val defaultGenres = setOf(
        MangaTag("Action", "action", source),
        MangaTag("Adaptions", "adaptions", source),
        MangaTag("Adult", "adult", source),
        MangaTag("Adventure", "adventure", source),
        MangaTag("Animals", "animals", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Crime", "crime", source),
        MangaTag("Demons", "demons", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Ecchi", "ecchi", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Game", "game", source),
        MangaTag("Gender Bender", "gender-bender", source),
        MangaTag("Girls' Love", "girls-love", source),
        MangaTag("Harem", "harem", source),
        MangaTag("Hentai", "hentai", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Isekai", "isekai", source),
        MangaTag("Josei", "josei", source),
        MangaTag("Lolicon", "lolicon", source),
        MangaTag("Magic", "magic", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mature", "mature", source),
        MangaTag("Mecha", "mecha", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("Philosophical", "philosophical", source),
        MangaTag("Police", "police", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Romance", "romance", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci-fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Shotacon", "shotacon", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shoujo Ai", "shoujo-ai", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Shounen Ai", "shounen-ai", source),
        MangaTag("Slice of Life", "slice-of-life", source),
        MangaTag("Smut", "smut", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Thriller", "thriller", source),
        MangaTag("Tragedy", "tragedy", source),
        MangaTag("Yaoi", "yaoi", source),
        MangaTag("Yuri", "yuri", source),
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = defaultGenres,
    )

    override fun getRequestHeaders(): Headers = headersWithReferer("https://$domain/")

    private fun headersWithReferer(referer: String) = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9,ja;q=0.8")
        .add("Referer", referer)
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // 优先使用站点的 advanced_search Ajax 接口，失败再退回静态页
        fetchListViaAjax(page, order, filter)?.let { list ->
            logDebug("list page=$page order=$order query='${filter.query.orEmpty()}' tag=${filter.tags.firstOrNull()?.key} size=${list.size} url=admin-ajax (advanced_search)")
            return list
        }

        val candidates = listOf(
            buildLibraryUrl(page, order, filter) to "https://$domain/library/",
            buildMangaPageUrl(page, order, filter) to "https://$domain/manga/",
            buildFallbackUrl(page, order, filter) to "https://$domain/manga/"
        )

        for ((idx, candidate) in candidates.withIndex()) {
            val (url, referer) = candidate
            val list = runCatching {
                val doc = webClient.httpGet(url.toHttpUrl(), headersWithReferer(referer)).parseHtml()
                parseMangaList(doc)
            }.getOrElse { emptyList() }
            val altSuffix = if (idx > 0) " (alt$idx)" else ""
            logDebug("list page=$page order=$order query='${filter.query.orEmpty()}' tag=${filter.tags.firstOrNull()?.key} size=${list.size} url=$url$altSuffix")
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private suspend fun fetchListViaAjax(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga>? {
        val (orderParam, orderBy) = mapOrder(order)
        val form = mapOf(
            "action" to "advanced_search",
            "search_term" to filter.query.orEmpty(),
            // 实测 Ajax 分页读取 page 参数，附带 the_page 兼容
            "page" to page.toString(),
            "paged" to page.toString(),
            "the_page" to page.toString(),
            "the_genre" to filter.tags.firstOrNull()?.key.orEmpty(),
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

    private fun parseAjaxHtml(html: String): List<Manga>? {
        if (html.isBlank() || html.contains("No results", ignoreCase = true)) return emptyList()
        if (html.contains("challenge-platform", ignoreCase = true) || html.contains("cf-chl-bypass", ignoreCase = true)) {
            return null
        }
        val doc = Jsoup.parse(html, "https://$domain/")
        return parseMangaList(doc)
    }

    private companion object {
        var warmupDone = false
    }

    private fun buildLibraryUrl(page: Int, order: SortOrder, filter: MangaListFilter): String = buildString {
        append("https://$domain/library/?")
        append("the_page=$page")
        append("&the_genre=${filter.tags.firstOrNull()?.key.orEmpty()}")
        append("&the_author=&the_artist=&the_exclude=&the_type=&the_status=")
        append("&search_term=${filter.query.orEmpty().urlEncoded()}")
        val (orderParam, orderBy) = mapOrder(order)
        append("&project=0&order=$orderParam&orderby=$orderBy")
    }

    private fun buildFallbackUrl(page: Int, order: SortOrder, filter: MangaListFilter): String = buildString {
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

    private fun buildMangaPageUrl(page: Int, order: SortOrder, filter: MangaListFilter): String = buildString {
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

    private fun parseMangaList(doc: Document): List<Manga> {
        // 网站使用 Tailwind CSS，漫画项在 grid 中
        // 每个项目有 a.w-full.h-full 链接到漫画详情页
        val items = doc.select("a.w-full.h-full, div.bsx a, .listupd .bsx a, article a[href*='/manga/']")
            .filter { it.attr("href").contains("/manga/") && !it.attr("href").contains("/chapter-") }
            .distinctBy { it.attr("href") }
        
        return items.mapNotNull { element ->
            try {
                parseMangaItem(element)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseMangaItem(element: Element): Manga {
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
        
        return Manga(
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

    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.url.let { if (it.startsWith("http")) it else "https://$domain$it" }
        val doc = webClient.httpGet(url.toHttpUrl(), getRequestHeaders()).parseHtml()
        
        // 标题：优先 meta，再取非 “Last Updates” 的 h1，避免列表页标题污染
        val title = doc.selectFirst("meta[property=og:title]")?.attrOrNull("content")
            ?.substringBefore(" - ", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?: doc.select("h1")
                .firstOrNull { !it.text().contains("last updates", ignoreCase = true) }
                ?.text()?.trim()
            ?: manga.title
        
        // 别名
        val altTitle = doc.selectFirst("h1 + div, h1 + span, .alternative, .other-name")?.text()?.trim()
        val altTitles = if (!altTitle.isNullOrEmpty()) setOf(altTitle) else manga.altTitles
        
        // 描述
        val description = doc.selectFirst(".synopsis, .description, div[itemprop=description], .entry-content p")?.text()?.trim()
        
        // 封面
        val cover = doc.selectFirst("img.attachment-post-thumbnail, .thumb img, img[itemprop=image]")?.let {
            it.attrOrNull("data-src") ?: it.attrOrNull("data-lazy-src") ?: it.attr("src")
        } ?: manga.coverUrl
        
        // 作者
        val authors = doc.select("a[href*='/author/'], span:contains(Author) + a, .author a").mapNotNull { 
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.toSet()
        
        // 标签
        val tags = doc.select("a[href*='/genre/']").mapNotNull { 
            val name = it.text().trim()
            val key = it.attr("href").substringAfter("/genre/").removeSuffix("/")
            if (name.isNotEmpty() && key.isNotEmpty()) MangaTag(name, key, source) else null
        }.toSet()
        
        // 状态
        val statusText = doc.selectFirst("span:contains(Status) + span, .status, .imptdt:contains(Status)")?.text()?.lowercase()
        val state = when {
            statusText?.contains("ongoing") == true -> MangaState.ONGOING
            statusText?.contains("completed") == true -> MangaState.FINISHED
            else -> manga.state
        }
        
        // 获取 manga_id 用于 AJAX 请求
        val mangaId = doc.selectFirst("input#manga_id, input[name=manga_id]")?.attr("value")
            ?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
            ?: extractMangaIdFromHx(doc)
            ?: extractMangaIdFromScript(doc)
        
        // 章节 - 优先使用 AJAX 端点
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

    private fun extractMangaIdFromHx(doc: Document): String? {
        val hxAttr = doc.selectFirst("#chapter-list[hx-get], [data-hx-get], [hx-get]")
            ?.attrOrNull("hx-get")
            ?: doc.selectFirst("#chapter-list")?.attrOrNull("data-hx-get")
        if (!hxAttr.isNullOrBlank()) {
            Regex("manga_id=([0-9]+)").find(hxAttr)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun extractMangaIdFromScript(doc: Document): String? {
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

    private suspend fun fetchChaptersViaAjax(mangaId: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
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
    ): MangaChapter? {
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
        
        return MangaChapter(
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

    private fun parseChaptersFromHtml(doc: Document): List<MangaChapter> {
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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
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
            MangaPage(
                id = generateUid("${chapter.url}-$index"),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }
}
