package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.util.json.getIntOrDefault
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.toJSONObjectOrNull
import java.util.Base64
import java.util.EnumSet

/**
 * 动漫456（www.dm456.com）—— MacCMS「conch」模板的动漫站，全站服务端渲染，播放地址直接内联在页面上。
 *
 * 站点协议（2026-11 实测）：
 * - 分类：`/fenlei/{分类码}/` 是首屏，`/fenlei/{分类码}-{页码}/` 是后续页，36 部/页；
 *   分类码大小写不能改：番剧 `SCCCCS`、国创 `FCCCCS`、剧场 `xCCCCS`、少儿 `YCCCCS`、美漫 `bCCCCS`、
 *   真人 `WCCCCS`、布袋 `LCCCCS`、原盘 `qCCCCS`、特典 `ICCCCS`、花絮 `QCCCCS`。
 *   站点没有「全站列表」入口（`/fenlei/` 直接报 System Error），未选分类时按番剧展示。
 * - 搜索：`/vodsearch/-------------/?wd={关键字}&page={页码}`，12 条/页，结构与分类页相同。
 * - 详情：`/donghua/{id}/`，标题 `h2.hl-dc-title`、封面与简介、`状态：`（更新至 N 集 / 全 N 集）、
 *   `类型：` 剧情标签、`声优：` 演员链接；剧集是 `#hl-plays-list li a`，指向 `/player/{id}-{线路}-{集}/`。
 * - 播放页：`<script>var player_aaaa={...}</script>` 里直接给 `url`（HLS m3u8 直链），没有 JS 挑战；
 *   直链无防盗链，`HEAD` 返回 `application/vnd.apple.mpegurl`。
 *   本站点只解析这些公开的免费播放地址，不处理会员、付费或第三方解析线路。
 */
@ContentSourceParser("DM456", "动漫456", "zh", type = ContentType.VIDEO)
internal class Dm456(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.DM456, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.dm456.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val categories = CATEGORIES.map { (code, title) -> ContentTag(title, code, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = categories,
            tagGroups = listOf(ContentTagGroup("频道", categories)),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return parseList(fetchDocument(searchUrl(query, page)), null)
        }
        val code = filter.tags.firstOrNull()?.key?.takeIf { it in CATEGORIES } ?: DEFAULT_CATEGORY
        val tag = ContentTag(CATEGORIES.getValue(code), code, source)
        return parseList(fetchDocument(categoryUrl(code, page)), tag)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = fetchDocument(manga.url.toAbsoluteUrl(domain))
        if (doc.selectFirst("div.hl-detail-content") == null) {
            throw ParseException(shortMessage = "动漫456：详情页结构缺失", url = manga.url)
        }
        val title = doc.selectFirst("h2.hl-dc-title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: manga.title
        val altTitle = doc.selectFirst(".hl-dc-sub")?.text()?.trim()?.takeIf { it.isNotEmpty() && it != title }
        val coverUrl = doc.selectFirst(".hl-dc-pic [data-original]")?.attrOrNull("data-original")
            ?.toAbsoluteUrl(domain) ?: manga.coverUrl
        val description = parseDescription(doc) ?: manga.description
        val info = parseInfoBlock(doc)
        return manga.copy(
            title = title,
            altTitles = altTitle?.let { setOf(it) } ?: manga.altTitles,
            description = description,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            authors = info.authors.ifEmpty { manga.authors },
            tags = info.tags.ifEmpty { manga.tags },
            state = info.state ?: manga.state,
            chapters = parseChapters(doc),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val pageUrl = chapter.url.toAbsoluteUrl(domain)
        val videoUrl = parseVideoUrl(fetchDocument(pageUrl).html(), pageUrl)
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = videoUrl,
                preview = pageUrl,
                source = source,
            ),
        )
    }

    /** 播放页唯一的取数点：`player_aaaa` 里的 `url`，`encrypt` 为 1/2 时分别做 base64 / URL 解码 */
    internal fun parseVideoUrl(html: String, pageUrl: String): String {
        val json = PLAYER_JSON_REGEX.find(html)?.groupValues?.get(1)?.toJSONObjectOrNull()
            ?: throw ParseException(shortMessage = "动漫456：播放页没有 player_aaaa 配置", url = pageUrl)
        val rawUrl = json.getStringOrNull("url")
            ?: throw ParseException(shortMessage = "动漫456：播放配置里没有 url", url = pageUrl)
        val videoUrl = decodePlayerUrl(rawUrl, json.getIntOrDefault("encrypt", 0))
        if (!videoUrl.startsWith("http")) {
            throw ParseException(shortMessage = "动漫456：播放地址不是直链（来源 ${json.getStringOrNull("from")}）", url = pageUrl)
        }
        return videoUrl
    }

    internal fun parseList(doc: Document, tag: ContentTag?): List<Content> {
        return doc.select("li.hl-list-item").mapNotNull { item ->
            parseCard(item, tag)
        }
    }

    /** 简介整段文字都在 `.hl-content-text > em` 里，不能把 `em` 当装饰标签删掉 */
    internal fun parseDescription(doc: Document): String? {
        return doc.selectFirst(".hl-content-text")?.text()?.replace('\u00A0', ' ')?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** 详情页顶部信息块：`状态：`、`类型：`、`声优：` 都是 `<em>标签名：</em>` 后跟取值 */
    internal fun parseInfoBlock(doc: Document): InfoBlock {
        val tags = LinkedHashSet<ContentTag>()
        var state: ContentState? = null
        val authors = LinkedHashSet<String>()
        for (item in doc.select(".hl-vod-data li")) {
            val label = item.selectFirst("em")?.text()?.trim()?.removeSuffix('：')?.removeSuffix(":") ?: continue
            when (label) {
                "状态" -> state = parseState(item.selectFirst("span")?.text())
                "类型" -> item.select("a[href]").mapNotNullTo(tags) { link ->
                    link.text().trim().takeIf { it.isNotEmpty() }?.let { ContentTag(it, it, source) }
                }
                "声优", "主演", "导演" -> item.select("a[href]").mapNotNullTo(authors) { link ->
                    link.text().trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return InfoBlock(tags = tags, state = state, authors = authors)
    }

    internal fun parseChapters(doc: Document): List<ContentChapter> {
        val lists = doc.select("ul.hl-plays-list").ifEmpty { doc.select("#hl-plays-list") }
        val result = ArrayList<ContentChapter>()
        val seen = HashSet<String>()
        // 一个作品可能有多条线路（多个 tab），只取第一条线路的剧集，避免重复
        val list = lists.firstOrNull() ?: return emptyList()
        for (link in list.select("a[href]")) {
            val url = link.attrOrNull("href")?.trim()?.takeIf { it.startsWith("/player/") } ?: continue
            if (!seen.add(url)) {
                continue
            }
            val title = link.text().trim().takeIf { it.isNotEmpty() } ?: continue
            result += ContentChapter(
                id = generateUid(url),
                title = title,
                number = title.digits().toFloatOrNull() ?: (result.size + 1).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
        return result
    }

    /** 列表卡片：`li.hl-list-item`，封面走懒加载的 `data-original` */
    internal fun parseCard(item: Element, tag: ContentTag?): Content? {
        val link = item.selectFirst("a.hl-item-thumb[href]") ?: item.selectFirst(".hl-item-title a[href]") ?: return null
        val url = link.attrOrNull("href")?.trim()?.takeIf { it.startsWith("/donghua/") } ?: return null
        val title = item.selectFirst(".hl-item-title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attrOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val coverUrl = item.selectFirst("[data-original]")?.attrOrNull("data-original")
            ?.toAbsoluteUrl(domain) ?: item.selectFirst("img[src]")?.attrOrNull("src")?.toAbsoluteUrl(domain)
        val rating = item.selectFirst(".score")?.text()?.trim()?.toFloatOrNull()
            ?.takeIf { it > 0f }?.let { it / 10f } ?: RATING_UNKNOWN
        val remarks = item.selectFirst(".remarks")?.text()?.trim()
        return Content(
            id = generateUid(url),
            title = title,
            altTitles = emptySet(),
            url = url,
            publicUrl = url.toAbsoluteUrl(domain),
            rating = rating,
            contentRating = sourceContentRating,
            coverUrl = coverUrl,
            tags = tag?.let { setOf(it) } ?: emptySet(),
            state = parseState(remarks),
            authors = emptySet(),
            source = source,
        )
    }

    /** `remarks`/`状态` 文案：「更新至 N 集」是在连载，「全 N 集」「已完结」是完结 */
    private fun parseState(text: String?): ContentState? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.contains("完结") || value.contains("全集") || value.startsWith("全") -> ContentState.FINISHED
            value.contains("更新") || value.contains("集") -> ContentState.ONGOING
            else -> null
        }
    }

    private fun decodePlayerUrl(rawUrl: String, encrypt: Int): String = when (encrypt) {
        1 -> String(Base64.getDecoder().decode(rawUrl), Charsets.UTF_8)
        2 -> rawUrl.urlDecode()
        else -> rawUrl
    }

    internal fun categoryUrl(code: String, page: Int): String = if (page <= 1) {
        "https://$domain/fenlei/$code/"
    } else {
        "https://$domain/fenlei/$code-$page/"
    }

    internal fun searchUrl(query: String, page: Int): String = buildString {
        append("https://").append(domain).append("/vodsearch/-------------/?wd=")
        append(query.urlEncoded())
        if (page > 1) {
            append("&page=").append(page)
        }
    }

    private suspend fun fetchDocument(url: String): Document {
        return webClient.httpGet(url).parseHtml()
    }

    internal data class InfoBlock(
        val tags: Set<ContentTag>,
        val state: ContentState?,
        val authors: Set<String>,
    )

    private companion object {
        const val LIST_PAGE_SIZE = 36
        const val DEFAULT_CATEGORY = "SCCCCS"

        val PLAYER_JSON_REGEX = Regex("""player_aaaa\s*=\s*(\{[\s\S]*?\})\s*(?:</script>|;)""")

        val CATEGORIES = mapOf(
            "SCCCCS" to "番剧",
            "FCCCCS" to "国创",
            "xCCCCS" to "剧场",
            "YCCCCS" to "少儿",
            "bCCCCS" to "美漫",
            "WCCCCS" to "真人",
            "LCCCCS" to "布袋",
            "qCCCCS" to "原盘",
            "ICCCCS" to "特典",
            "QCCCCS" to "花絮",
        )
    }
}
