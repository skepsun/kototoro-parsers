package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * 漫画社（www.311s.com）—— PHP 模板站，全站服务端渲染，图片是直链 webp。
 *
 * 站点协议（2026-11 实测）：
 * - 列表：`/category/` 是第 1 页，`/category/page/{页码}` 是后续页，20 部/页；
 *   题材筛选是 `/category/tags/{id}`、`/category/tags/{id}/page/{页码}`，分类页底部把 292 个题材
 *   全列了出来（`a[href^=/category/tags/]`）。
 * - 搜索：`/search/{关键字}`（路径式，不是 query 参数；`?keyword=`/`?q=` 都会得到「共0部漫画」）、
 *   `/search/{关键字}/page/{页码}`，30 部/页。
 * - 详情：`/comic_{id}.html`，书名 `h1`、封面 `.comic-cover-large img`、评分/人气/作者在
 *   `.comic-stats .stat-item span`（文本形如「评分：9.9」「作者：米橙子」）、题材与状态在
 *   `.comic-tags span.tag`（第一个 span 是空格分隔的题材，第二个是「连载」或「完结」）、
 *   简介 `.comic-description p`、目录 `.chapter-list .chapter-item a`（正序，几百话一次给全）。
 * - 阅读页：`/chapter_{漫画id}_{章节id}.html`，图片是 `div.comic-content img.comic-image`，
 *   直接就是 `https://manhua.5um.net/...webp` 直链，无防盗链、无 JS 注入。
 */
@ContentSourceParser("MANHUASHE", "漫画社", "zh", type = ContentType.MANGA)
internal class Manhuashe(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.MANHUASHE, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("www.311s.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = runCatching {
            parseTags(fetchDocument("https://$domain/category/"))
        }.getOrDefault(emptyList())
        return ContentListFilterOptions(
            availableTags = tags.toSet(),
            tagGroups = if (tags.isEmpty()) emptyList() else listOf(ContentTagGroup("题材", tags.toSet())),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        val url = if (query.isNotEmpty()) {
            val base = "https://$domain/search/${query.urlEncoded()}"
            if (page > 1) "$base/page/$page" else base
        } else {
            val tagId = filter.tags.firstOrNull()?.key?.takeIf { it.all(Char::isDigit) }
            val base = if (tagId != null) "https://$domain/category/tags/$tagId" else "https://$domain/category"
            if (page > 1) "$base/page/$page" else "$base/"
        }
        val doc = fetchDocument(url)
        return parseListPage(doc).also {
            if (it.isEmpty() && doc.selectFirst(".comic-section, .content-body") == null) {
                throw ParseException(shortMessage = "漫画社：列表页结构缺失", url = url)
            }
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val title = doc.selectFirst(".comic-main-info h1")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ParseException(shortMessage = "漫画社：详情页没有书名", url = url)
        val cover = doc.selectFirst(".comic-cover-large img")?.attrAsRelativeUrlOrNull("src")?.toAbsoluteUrl(domain)
            ?: manga.coverUrl
        val stats = doc.select(".comic-stats .stat-item span").mapNotNull { it.textOrNull() }
        val authors = stats.firstOrNull { it.contains("作者") }
            ?.substringAfter("作者")
            ?.trimStart('：', ':', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { setOf(it) }
            ?: manga.authors
        val rating = stats.firstOrNull { it.contains("评分") }
            ?.substringAfter("评分")
            ?.trimStart('：', ':', ' ')
            ?.trim()
            ?.toFloatOrNull()
            ?.div(10f)
            ?.takeIf { it in 0f..1f }
            ?: RATING_UNKNOWN
        val tagSpans = doc.select(".comic-tags span.tag").mapNotNull { it.textOrNull()?.trim() }
        val genreNames = tagSpans.firstOrNull { it.isNotEmpty() && it.split(' ').size > 1 }
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val tags = genreNames.mapTo(HashSet()) { ContentTag(it, it, source) }.ifEmpty { manga.tags }
        val state = parseState(tagSpans.joinToString(" "))
        val description = doc.select(".comic-description p").mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
            ?: manga.description
        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            largeCoverUrl = cover,
            authors = authors,
            tags = tags,
            rating = rating,
            state = state,
            chapters = parseChapters(doc),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val images = parsePageImages(doc)
        if (images.isEmpty()) {
            throw ParseException(shortMessage = "漫画社：阅读页没有解析到图片", url = url)
        }
        return images.map { imageUrl ->
            ContentPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = url,
                source = source,
            )
        }
    }

    internal fun parseListPage(doc: Document): List<Content> {
        return doc.select("div.comic-item").mapNotNull { item ->
            val link = item.selectFirst("a.comic-cover[href^=/comic_], h3 a[href^=/comic_]")
                ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = item.selectFirst("h3 a")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.selectFirst("a.comic-cover img")?.attrOrNull("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val cover = item.selectFirst("a.comic-cover img")?.attrAsRelativeUrlOrNull("src")?.toAbsoluteUrl(domain)
            val badge = item.selectFirst("span.update-badge")?.textOrNull()?.trim()
            val author = item.selectFirst("p.comic-author")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            Content(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = emptySet(),
                state = parseState(badge.orEmpty()),
                authors = author?.let { setOf(it) } ?: emptySet(),
                source = source,
            )
        }
    }

    internal fun parseTags(doc: Document): List<ContentTag> {
        return doc.select("a[href^=/category/tags/]").mapNotNull { a ->
            val id = TAG_REGEX.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val name = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ContentTag(name, id, source)
        }.distinctBy { it.key }
    }

    internal fun parseChapters(doc: Document): List<ContentChapter> {
        return doc.select(".chapter-list .chapter-item a[href^=/chapter_]")
            .mapIndexedNotNull { index, a ->
                val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
                val title = a.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                ContentChapter(
                    id = generateUid(href),
                    title = title,
                    number = (index + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }
    }

    internal fun parsePageImages(doc: Document): List<String> {
        return doc.select("div.comic-content img.comic-image")
            .mapNotNull { img -> img.attrAsRelativeUrlOrNull("src") ?: img.attrAsRelativeUrlOrNull("data-src") }
            .map { it.toAbsoluteUrl(domain) }
            .filter { it.isNotEmpty() }
    }

    private fun parseState(text: String): ContentState? = when {
        text.contains("已完结") || text.contains("完结") -> ContentState.FINISHED
        text.contains("连载") -> ContentState.ONGOING
        else -> null
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private companion object {
        val TAG_REGEX = Regex("""/category/tags/(\d+)""")
    }
}
