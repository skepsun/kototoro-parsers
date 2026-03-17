package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.util.*
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.LinkedHashSet

/**
 * 轻小说百科 - 基于 HTML 解析
 */
@ContentSourceParser("LIGHTNOVEL_WIKI", "轻小说百科", "zh", type = ContentType.NOVEL)
internal class LightNovelWiki(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.LIGHTNOVEL_WIKI, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain(
        "lnovel.org",
        "lnovel.tw"
        )

    override fun getRequestHeaders(): okhttp3.Headers {
        return super.getRequestHeaders().newBuilder()
            .add("Referer", "https://$domain/")
            .add("User-Agent", org.skepsun.kototoro.parsers.network.UserAgents.CHROME_MOBILE)
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genreTags = buildGenreTags()
        val statusTags = buildStatusTags()
        return ContentListFilterOptions(
            availableTags = (genreTags + statusTags).toSet(),
            tagGroups = listOf(
                ContentTagGroup("类别", genreTags),
                ContentTagGroup("状态", statusTags),
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val baseUrl = buildString {
            if (!filter.query.isNullOrBlank()) {
                append("https://$domain/books?page=$page&q%5Bname_cont%5D=${filter.query!!.urlEncoded()}")
            } else {
                val genre = filter.tags.firstOrNull { it.key.startsWith("genre:") }?.key?.substringAfter(":")
                val status = filter.tags.firstOrNull { it.key.startsWith("status:") }?.key?.substringAfter(":")
                append("https://$domain/books?page=$page")
                if (genre != null) append("&q%5Bgenres_id_eq%5D=$genre")
                if (status != null) append("&q%5Bstatus_eq%5D=$status")
            }
        }

        var response = webClient.httpGet(baseUrl)
        var code = response.code
        var doc = response.parseHtml()
        var list = parseContentList(doc)
        println("LightNovelWiki list/search: url=$baseUrl code=$code page=$page query=${filter.query.orEmpty()} results=${list.size}")

        // 当带查询仍返回与无查询相同的数量时，尝试另一查询参数 name_or_other_names_cont
        if (!filter.query.isNullOrBlank() && list.size >= 50) {
            val altUrl = "https://$domain/books?page=$page&q%5Bname_or_other_names_cont%5D=${filter.query!!.urlEncoded()}"
            response = webClient.httpGet(altUrl)
            code = response.code
            doc = response.parseHtml()
            list = parseContentList(doc)
            println("LightNovelWiki search fallback: url=$altUrl code=$code page=$page query=${filter.query.orEmpty()} results=${list.size}")
        }
        return list
    }

    private fun parseContentList(doc: Document): List<Content> {
        val list = mutableListOf<Content>()
        // 页面结构可能变动，放宽选择器：带图片的卡片链接均尝试解析
        val items = doc.select("a.col-6.col-md-4.col-md-3.col-lg-6.col-xl-4, a.card, .card a[href], .book-card a[href], .book-list a[href]")
            .filter { it.selectFirst("img") != null }

        for (item in items) {
            val href = item.attr("href").toRelativeUrl(domain)
            val title = item.selectFirst("h2, h3, h4, .book-title")?.text()?.trim()
                ?: item.attr("title").ifBlank { item.text().trim() }
            if (title.isNullOrBlank()) continue
            val cover = item.selectFirst("img")?.attr("src")?.toAbsoluteUrl(domain)

            list.add(
                Content(
                    id = generateUid(href),
                    title = title,
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    rating = 0f,
                    contentRating = null,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                ),
            )
        }
        return list
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url).parseHtml()
        
        val name = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
        val author = doc.select(".text-body-tertiary").firstOrNull()?.text()?.trim()
        val cover = doc.selectFirst(".w-100.h-100")?.attr("src")?.toAbsoluteUrl(domain)
        val intro = doc.selectFirst(".card-body")?.select("p")?.text()?.trim()
        
        // Tags
        val tagsSet = LinkedHashSet<ContentTag>()
        doc.select("dd a").forEach { tagElement ->
            val tagName = tagElement.text().trim()
            if (tagName.isNotEmpty()) {
                tagsSet.add(ContentTag(tagName, tagName, source))
            }
        }

        // Chapters
        val allChapters = mutableListOf<ContentChapter>()
        val accordionItems = doc.select(".accordion-item").sortedBy { item ->
            val target = item.selectFirst(".accordion-button")?.attr("data-bs-target")
                ?: item.selectFirst(".accordion-collapse")?.id()
                ?: ""
            // Extract the 'n' from 'volumes-n' or '#volumes-n'
            Regex("""\d+""").find(target)?.value?.toIntOrNull() ?: Int.MAX_VALUE
        }
        
        val addedUrls = mutableSetOf<String>()
        if (accordionItems.isNotEmpty()) {
            var currentVolume = 0
            accordionItems.forEach { item ->
                val headerElement = item.selectFirst(".accordion-header, .accordion-button")
                val volumeName = headerElement?.text()?.trim() ?: ""
                val volumeUrl = headerElement?.attr("href")?.takeIf { it.isNotBlank() }?.toRelativeUrl(domain)
                
                currentVolume++
                
                // Get sub-chapters inside the volume
                val chapterLinks = item.select(".accordion-body a[href], .accordion-collapse a[href]")
                
                // 1. Resolve title collision: If the volume header link matches a sub-chapter, use the sub-chapter's better title
                if (volumeUrl != null && volumeUrl !in addedUrls) {
                    val matchingSubChapter = chapterLinks.find { it.attr("href").toRelativeUrl(domain) == volumeUrl }
                    val finalTitle = matchingSubChapter?.text()?.trim() ?: volumeName
                    
                    allChapters.add(ContentChapter(
                        id = generateUid(volumeUrl),
                        title = finalTitle,
                        number = allChapters.size + 1f,
                        volume = currentVolume,
                        url = volumeUrl,
                        scanlator = volumeName, // Store volume name for custom headers
                        uploadDate = 0L,
                        branch = null, // Set to null for single list order
                        source = source
                    ))
                    addedUrls.add(volumeUrl)
                }
                
                // 2. Add all other sub-chapters in order
                chapterLinks.forEach { element ->
                    val cUrl = element.attr("href").toRelativeUrl(domain)
                    if (cUrl !in addedUrls) {
                        val cTitle = element.text().trim()
                        allChapters.add(ContentChapter(
                            id = generateUid(cUrl),
                            title = cTitle,
                            number = allChapters.size + 1f,
                            volume = currentVolume,
                            url = cUrl,
                            scanlator = volumeName, // Store volume name for custom headers
                            uploadDate = 0L,
                            branch = null, // Set to null for single list order
                            source = source
                        ))
                        addedUrls.add(cUrl)
                    }
                }
            }
        } else {
            // Fallback for flat structure
            val chapterElements = doc.select(".accordion-item a[href], .chapter-list a[href], .card-body a[href]")
            chapterElements.forEachIndexed { index, element ->
                val cUrl = element.attr("href").toRelativeUrl(domain)
                if (cUrl !in addedUrls) {
                    val cTitle = element.text().trim()
                    allChapters.add(ContentChapter(
                        id = generateUid(cUrl),
                        title = cTitle,
                        number = allChapters.size + 1f,
                        volume = 0,
                        url = cUrl,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = null,
                        source = source
                    ))
                    addedUrls.add(cUrl)
                }
            }
        }

        return manga.copy(
            title = name,
            coverUrl = cover ?: manga.coverUrl,
            authors = if (author != null) setOf(author) else manga.authors,
            description = intro,
            tags = tagsSet,
            chapters = allChapters
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val content = getChapterContent(chapter) ?: return emptyList()
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = content.html.toDataUrl(),
                preview = null,
                source = source
            )
        )
    }

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url).parseHtml()
        
        // Find the main content element
        val contentElement = doc.selectFirst(
            "#chaptersShowContent, .card-body, .book-content, #content, .article-content, .book-article, #article-body"
        ) ?: doc.body()
        
        // Clean content: remove ads/scripts
        contentElement.select("script, style, iframe, ins, .co, .google-auto-placed, ap_container, .adsbygoogle").remove()
        
        // Collect content HTML: main container + trailing siblings (for illustrations)
        val contentHtml = buildString {
            // Main text content
            val mainContent = contentElement.clone()
            append(mainContent.html())
            
            // Siblings (often illustrations are siblings of the content div)
            var next = contentElement.nextElementSibling()
            while (next != null && (next.tagName() == "a" || next.tagName() == "img" || next.hasClass("d-block"))) {
                if (next.tagName() == "a" && next.selectFirst("img") != null) {
                    append(next.selectFirst("img")!!.outerHtml())
                } else {
                    append(next.outerHtml())
                }
                append("<br>\n")
                next = next.nextElementSibling()
            }
        }
        
        // Process images (in the built HTML) and collect urls
        val images = mutableListOf<NovelChapterContent.NovelImage>()
        val finalDoc = org.jsoup.Jsoup.parseBodyFragment(contentHtml)
        finalDoc.select("img").forEach { img ->
            val src = (img.attr("data-src").ifBlank { img.attr("src") }).trim()
            if (src.isNotBlank()) {
                val abs = src.toAbsoluteUrl(domain)
                img.attr("src", abs)
                img.attr("referrerpolicy", "no-referrer")
                images.add(
                    NovelChapterContent.NovelImage(
                        url = abs,
                        headers = mapOf("Referer" to "https://$domain/")
                    )
                )
            }
        }

        val html = buildChapterHtml(finalDoc.body().html(), chapter.title ?: "")
        return NovelChapterContent(html = html, images = images)
    }

    private fun buildChapterHtml(content: String, title: String): String {
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<style>")
            append("body{font-family:sans-serif;padding:20px;line-height:1.8;font-size:1.1rem;background:#fff;color:#000;}")
            append("img{max-width:100%;height:auto;display:block;margin:10px auto;}")
            append("p{margin-bottom:1.2rem;}")
            append("h1{font-size:1.4rem;border-bottom:1px solid #eee;padding-bottom:10px;margin-bottom:20px;}")
            append("</style></head>")
            append("<body>")
            if (title.isNotBlank()) append("<h1>$title</h1>")
            append(content)
            append("</body></html>")
        }
    }

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(StandardCharsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun buildGenreTags(): Set<ContentTag> = linkedSetOf(
        ContentTag("校园", "genre:3", source),
        ContentTag("爱情", "genre:1", source),
        ContentTag("冒险", "genre:6", source),
        ContentTag("搞笑", "genre:10", source),
        ContentTag("奇幻", "genre:15", source),
        ContentTag("魔法", "genre:2", source),
        ContentTag("异界", "genre:17", source),
        ContentTag("侦探", "genre:8", source),
        ContentTag("穿越", "genre:18", source),
        ContentTag("科幻", "genre:4", source),
        ContentTag("神鬼", "genre:5", source),
        ContentTag("后宫", "genre:12", source),
        ContentTag("格斗", "genre:11", source),
        ContentTag("恐怖", "genre:7", source),
        ContentTag("战争", "genre:16", source),
        ContentTag("百合", "genre:20", source),
        ContentTag("异能", "genre:14", source),
        ContentTag("治愈", "genre:21", source),
        ContentTag("机战", "genre:19", source),
        ContentTag("励志", "genre:23", source),
        ContentTag("都市", "genre:13", source),
        ContentTag("历史", "genre:22", source),
        ContentTag("纯爱", "genre:24", source),
    )

    private fun buildStatusTags(): Set<ContentTag> = linkedSetOf(
        ContentTag("连载中", "status:ongoing", source),
        ContentTag("已完结", "status:completed", source),
    )
}
