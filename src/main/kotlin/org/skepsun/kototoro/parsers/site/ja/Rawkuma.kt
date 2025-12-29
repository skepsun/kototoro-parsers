@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    override fun getRequestHeaders() = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9,ja;q=0.8")
        .add("Referer", "https://$domain/")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://$domain/manga/?page=$page")
            
            if (!filter.query.isNullOrEmpty()) {
                append("&s=${filter.query!!.urlEncoded()}")
            }
            
            val orderParam = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.NEWEST -> "newest"
                SortOrder.ALPHABETICAL -> "alphabet"
                else -> "update"
            }
            append("&order=$orderParam")
            
            // 添加 genre 过滤（单选）
            filter.tags.firstOrNull()?.let { tag ->
                append("&the_genre=${tag.key}")
            }
        }

        val doc = webClient.httpGet(url.toHttpUrl(), getRequestHeaders()).parseHtml()
        return parseMangaList(doc)
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
        val parent = element.parent()
        val titleElement = element.selectFirst("h1, h2, h3, .series-title, .tt") 
            ?: parent?.selectFirst("h1, h2, h3, a > h1")
        val title = titleElement?.text()?.trim() 
            ?: img?.attr("alt")?.trim() 
            ?: ""
        
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
        
        // 标题
        val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
        
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
        var page = 1
        val maxPages = 50 // 安全限制
        
        while (page <= maxPages) {
            val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php?manga_id=$mangaId&page=$page&action=chapter_list"
            val response = webClient.httpGet(ajaxUrl.toHttpUrl(), getRequestHeaders())
            val html = response.parseRaw()
            
            if (html.isBlank() || html.contains("No chapters") || html.trim() == "0") break
            
            val doc = org.jsoup.Jsoup.parse(html)
            
            // 选择章节容器 div[data-chapter-number]
            val chapterContainers = doc.select("div[data-chapter-number]")
            
            if (chapterContainers.isEmpty()) break
            
            chapterContainers.forEach { container ->
                try {
                    // 从 data-chapter-number 属性获取章节号
                    val dataChapterNum = container.attr("data-chapter-number").toFloatOrNull()
                    
                    // 从容器内的 a 标签获取章节 URL
                    val linkElement = container.selectFirst("a[href*='/chapter-']") ?: return@forEach
                    val href = linkElement.attr("href").let {
                        if (it.startsWith("http")) it else "https://$domain$it"
                    }
                    val relativeUrl = href.toRelativeUrl(domain)
                    
                    // 章节号 - 优先使用 data-chapter-number，否则从 URL 提取
                    val chapterNumber = dataChapterNum ?: run {
                        val chapterMatch = Regex("/chapter-(\\d+)").find(href)
                        chapterMatch?.groupValues?.get(1)?.toFloatOrNull() ?: (chapters.size + 1f)
                    }
                    
                    // 章节标题 - 从 span 获取
                    val titleText = container.selectFirst(".flex.flex-row span, span")?.text()?.trim()
                    val chapterInt = chapterNumber.toInt()
                    val title = if (!titleText.isNullOrEmpty()) {
                        titleText
                    } else if (chapterNumber == chapterInt.toFloat()) {
                        "Chapter $chapterInt"
                    } else {
                        "Chapter $chapterNumber"
                    }
                    
                    // 日期 - 从 time 标签获取
                    val dateText = container.selectFirst("time")?.text()?.trim()
                    val uploadDate = parseDate(dateText)
                    
                    chapters.add(
                        MangaChapter(
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
                    )
                } catch (e: Exception) {
                    // 忽略解析失败的章节
                }
            }
            
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

    private fun parseChaptersFromHtml(doc: Document): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        
        // 优先使用 div[data-chapter-number] 容器
        val chapterContainers = doc.select("div[data-chapter-number]")
        
        if (chapterContainers.isNotEmpty()) {
            chapterContainers.forEach { container ->
                try {
                    val dataChapterNum = container.attr("data-chapter-number").toFloatOrNull()
                    val linkElement = container.selectFirst("a[href*='/chapter-']") ?: return@forEach
                    val href = linkElement.attrAsAbsoluteUrl("href")
                    val relativeUrl = href.toRelativeUrl(domain)
                    
                    val chapterNumber = dataChapterNum ?: run {
                        val chapterMatch = Regex("/chapter-(\\d+)").find(href)
                        chapterMatch?.groupValues?.get(1)?.toFloatOrNull() ?: (chapters.size + 1f)
                    }
                    
                    val titleText = container.selectFirst(".flex.flex-row span, span")?.text()?.trim()
                    val chapterInt = chapterNumber.toInt()
                    val title = if (!titleText.isNullOrEmpty()) {
                        titleText
                    } else if (chapterNumber == chapterInt.toFloat()) {
                        "Chapter $chapterInt"
                    } else {
                        "Chapter $chapterNumber"
                    }
                    
                    val dateText = container.selectFirst("time")?.text()?.trim()
                    val uploadDate = parseDate(dateText)
                    
                    chapters.add(
                        MangaChapter(
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
                    )
                } catch (e: Exception) {
                    // 忽略
                }
            }
        } else {
            // 回退到旧的选择方式
            val chapterElements = doc.select("a[href*='/chapter-']")
                .filter { it.attr("href").contains("/chapter-") }
                .distinctBy { it.attr("href") }
            
            chapterElements.forEachIndexed { index, element ->
                try {
                    val href = element.attrAsAbsoluteUrl("href")
                    val relativeUrl = href.toRelativeUrl(domain)
                    val chapterMatch = Regex("/chapter-(\\d+)").find(href)
                    val chapterNumber = chapterMatch?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1f)
                    val chapterInt = chapterNumber.toInt()
                    val title = if (chapterNumber == chapterInt.toFloat()) "Chapter $chapterInt" else "Chapter $chapterNumber"
                    
                    chapters.add(
                        MangaChapter(
                            id = generateUid(relativeUrl),
                            title = title,
                            number = chapterNumber,
                            volume = 0,
                            url = relativeUrl,
                            uploadDate = 0L,
                            source = source,
                            scanlator = null,
                            branch = null,
                        )
                    )
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
        
        return chapters.distinctBy { it.url }.sortedBy { it.number }
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
