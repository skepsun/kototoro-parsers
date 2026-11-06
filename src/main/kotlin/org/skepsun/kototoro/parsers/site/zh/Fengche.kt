package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.Base64
import java.util.EnumSet

/**
 * 风车动漫（www.fcbbcc.com）——另一种 MacCMS 皮肤（"fengche"），动画为主，播放地址同样是 m3u8 直链。
 *
 * 站点协议（2026-11 实测）：
 * - 该域名的 **HTTPS 不可用**（TLS 握手失败），所有请求必须走 `http://`；`www.fengchedm.com` 也跳到这里。
 * - 分类：`/list/{分类码}-{页码}.html`，40 部/页，页脚 `.pages` 里有总页数；分类码不能猜：
 *   国产动画 `137`、日本动画 `138`、欧美动画 `139`、其他动画 `157`、动画电影 `158`。
 *   `/list/1-1.html`（全站）的主列表是 JS 填充的空壳，站点的「全站列表」入口无效。
 * - 「最新更新」`/new.html` 是纯文字列表（只有标题和日期，没有封面），所以没做成列表源。
 * - 搜索：GET `/common/search.aspx`，关键字不是查询参数而是 **Cookie `keyww`**（页面自己的 JS 也是先写
 *   cookie 再提交），一次返回全部结果，不支持翻页。
 * - 详情：`/content/{id}.html`。标题在 `.tpic .spay a`，封面 `.tpic img[data-original]`，
 *   信息块 `.intro .alex` 里是「别名 / 更新 / 标签 / 类型 / 年份」纯文本（标签用空格分隔）；
 *   站点没有剧情简介，只能拿到 `meta[name=description]`。剧集按「播放列表1/2」分 tab，`div.movurl` 各一套。
 * - 播放：`/playn/{id}-{线路}-{集}.html` 里 `var paly_js = "..."` 是混淆过的 base64：
 *   把 `-` 换回 `a`、`.` 换回 `=` 再做 base64 解码，得到 m3u8 直链（无防盗链）。
 */
@ContentSourceParser("FENGCHE", "风车动漫", "zh", type = ContentType.VIDEO)
internal class Fengche(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.FENGCHE, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("www.fcbbcc.com")

    /** 手机 UA 会被重定向到「DM530 手机版」，卡片结构完全不同，必须固定桌面 UA */
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

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
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            // 搜索结果页没有分页控件，一次给出全部命中
            if (page > 1) {
                return emptyList()
            }
            // 关键字是 Cookie 而不是查询参数；手写 Cookie 头会被客户端的 CookieJar 覆盖，必须写进 jar
            context.cookieJar.insertCookies(domain, "keyww=${query.urlEncoded()}; Path=/")
            return parseCards(fetchDocument(searchUrl()), null)
        }
        val code = filter.tags.firstOrNull()?.key?.takeIf { it in CATEGORIES } ?: DEFAULT_CATEGORY
        val tag = ContentTag(CATEGORIES.getValue(code), code, source)
        return parseCards(fetchDocument(categoryUrl(code, page)), tag)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = fetchDocument(manga.url.toSiteUrl())
        val cover = doc.selectFirst(".tpic img[data-original], .tpic img[src]")?.let {
            it.attrOrNull("data-original") ?: it.attrOrNull("src")
        }?.takeIf { it.isNotBlank() }?.toSiteUrl()
        if (cover == null && doc.selectFirst(".intro .alex") == null) {
            throw ParseException(shortMessage = "风车动漫：详情页结构缺失", url = manga.url)
        }
        val title = doc.selectFirst(".tpic .spay a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst(".tpic img[alt]")?.attrOrNull("alt") ?: manga.title
        val info = parseInfoBlock(doc)
        return manga.copy(
            title = title,
            altTitles = info.altTitles.ifEmpty { manga.altTitles },
            description = parseDescription(doc, title) ?: manga.description,
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            tags = info.tags.ifEmpty { manga.tags },
            state = info.state ?: manga.state,
            chapters = parseChapters(doc),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val pageUrl = chapter.url.toSiteUrl()
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

    /** 列表卡片：`.imgs ul li`（分类页与搜索结果页共用），封面是懒加载的 `data-original` */
    internal fun parseCards(doc: Document, tag: ContentTag?): List<Content> {
        return doc.select(".imgs ul li").mapNotNull { item -> parseCard(item, tag) }
    }

    internal fun parseCard(item: Element, tag: ContentTag?): Content? {
        val link = item.selectFirst("a[href^=/content/]") ?: return null
        val url = link.attrOrNull("href")?.trim()?.takeIf { it.startsWith("/content/") } ?: return null
        val title = item.selectFirst("p a[title]")?.attrOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.selectFirst("img[alt]")?.attrOrNull("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val img = item.selectFirst("img")
        val coverUrl = (img?.attrOrNull("data-original") ?: img?.attrOrNull("src"))
            ?.takeIf { it.isNotBlank() }?.toSiteUrl()
        // 卡片里的第二个 <p> 是连载状态（更新至第 N 集 / 已完结）
        val remarks = item.select("p").getOrNull(1)?.text()?.trim()
        return Content(
            id = generateUid(url),
            title = title,
            altTitles = emptySet(),
            url = url,
            publicUrl = url.toSiteUrl(),
            rating = RATING_UNKNOWN,
            contentRating = sourceContentRating,
            coverUrl = coverUrl,
            tags = tag?.let { setOf(it) } ?: emptySet(),
            state = parseState(remarks),
            authors = emptySet(),
            source = source,
        )
    }

    /** 详情页信息块：`.intro .alex` 下每行「标签名：值」，标签值用空格分隔 */
    internal fun parseInfoBlock(doc: Document): InfoBlock {
        val altTitles = LinkedHashSet<String>()
        val tags = LinkedHashSet<ContentTag>()
        var state: ContentState? = null
        for (node in doc.select(".intro .alex p, .intro .alex span")) {
            val text = node.text().trim()
            val separator = text.indexOfFirst { it == '：' || it == ':' }
            if (separator <= 0) {
                continue
            }
            val label = text.substring(0, separator).trim()
            val value = text.substring(separator + 1).trim()
            when (label) {
                "别名" -> value.splitByWhitespace().mapNotNullTo(altTitles) { it.takeIf(String::isNotEmpty) }
                "标签" -> value.splitByWhitespace().mapNotNullTo(tags) {
                    it.takeIf(String::isNotEmpty)?.let { name -> ContentTag(name, name, source) }
                }
                "类型" -> value.takeIf { it.isNotEmpty() }?.let { tags += ContentTag(it, it, source) }
                "更新", "状态" -> state = parseState(value)
            }
        }
        return InfoBlock(altTitles = altTitles, tags = tags, state = state)
    }

    /** 站点详情页没有简介，只有 `meta[name=description]`，且内容通常就是「标题,标题在线观看」 */
    internal fun parseDescription(doc: Document, title: String): String? {
        val description = doc.selectFirst("meta[name=description]")?.attrOrNull("content")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val residue = description.replace(title, "").replace("在线观看", "").replace(",", "")
            .replace("，", "").trim()
        return description.takeIf { residue.isNotEmpty() }
    }

    internal fun parseChapters(doc: Document): List<ContentChapter> {
        val container = doc.selectFirst("div.movurl") ?: return emptyList()
        val result = ArrayList<ContentChapter>()
        for (link in container.select("a[href]")) {
            val url = link.attrOrNull("href")?.trim()?.takeIf { it.startsWith("/playn/") } ?: continue
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

    /** `var paly_js = "..."`：混淆 base64，`-` 代表 `a`、`.` 代表 `=` */
    internal fun parseVideoUrl(html: String, pageUrl: String): String {
        val payload = PLAYER_REGEX.find(html)?.groupValues?.get(1)
            ?: throw ParseException(shortMessage = "风车动漫：播放页没有 paly_js", url = pageUrl)
        val encoded = payload.replace('-', 'a').replace('.', '=')
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        val videoUrl = try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw ParseException(shortMessage = "风车动漫：播放地址解码失败", url = pageUrl, cause = e)
        }
        if (!videoUrl.startsWith("http")) {
            throw ParseException(shortMessage = "风车动漫：播放地址不是直链", url = pageUrl)
        }
        return videoUrl
    }

    /** 「更新至第 N 集」是在连载，「已完结」是完结 */
    internal fun parseState(text: String?): ContentState? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.contains("完结") -> ContentState.FINISHED
            value.contains("更新") || value.contains("集") -> ContentState.ONGOING
            else -> null
        }
    }

    internal fun categoryUrl(code: String, page: Int): String = "$baseUrl/list/$code-$page.html"

    internal fun searchUrl(): String = "$baseUrl/common/search.aspx"

    private suspend fun fetchDocument(url: String): Document {
        return webClient.httpGet(url).parseHtml()
    }

    private val baseUrl: String
        get() = "http://$domain"

    /** 该站点只能走 http，公共的 `toAbsoluteUrl` 会硬拼 `https://`，这里自己补协议 */
    private fun String.toSiteUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "http:$this"
        startsWith('/') -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    internal data class InfoBlock(
        val altTitles: Set<String>,
        val tags: Set<ContentTag>,
        val state: ContentState?,
    )

    private companion object {
        const val LIST_PAGE_SIZE = 40
        const val DEFAULT_CATEGORY = "138"

        val PLAYER_REGEX = Regex("""var\s+paly_js\s*=\s*"([^"]+)"""")

        val CATEGORIES = mapOf(
            "137" to "国产动画",
            "138" to "日本动画",
            "139" to "欧美动画",
            "157" to "其他动画",
            "158" to "动画电影",
        )
    }
}
