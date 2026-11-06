package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.toJSONObjectOrNull
import java.net.URLDecoder
import java.util.EnumSet

/**
 * 电影88（默认 `dianying88.net`）—— 电影/电视剧/动漫/综艺/B站 聚合站，Next.js App Router 服务端渲染。
 *
 * 站点协议（2026-11-06 实测）：
 * - 列表：`/type/{20|37|43|45|47}?pg={页码}`（电影/电视剧/动漫/综艺/B站，20 部/页）、
 *   `/latest?pg=`、`/ranking?pg=`（全站高分榜，不按分类）。卡片是 `article.mcard`：
 *   标题在 `span.mcard__title`，标签（类型/年份/地区）在 `span.mcard__tag`，
 *   状态在 `span.mcard__meta`（「更新：完结」/「更新至第 N 集」），封面在 `img[srcSet]` 的
 *   `/_next/image?url=%2Fmedia%2F…` 里（取 `url` 参数解码后拼站点域名）。
 * - 搜索：`/search?wd={关键字}`（表单字段是 `wd`，用 `q` 只会拿到空结果页），结果卡片与列表相同、无分页。
 * - 详情：`/vod/{id}`，元数据在 `script[type=application/ld+json]` 的 `TVSeries`/`Movie`
 *   （名称、简介、封面、`genre`、`countryOfOrigin`、`director`、`numberOfEpisodes`）。
 * - 线路：详情页 SSR 渲染若干 `<section aria-labelledby="source-N">`，`h2` 是线路名
 *   （爱奇艺/芒果TV/暴风线路…），集链接是 `/play/{id}?s={线路}&e={集}`，`s` 即线路下标。
 * - **播放地址只在详情页的 RSC flight 数据里**：`"source":{"key":"bfzym3u8","label":"暴风线路",
 *   "episodes":[{"label":"第1集","url":"https://v.fengbao14.com/…/index.m3u8"}]}`。
 *   播放页 `/play/{id}?s=&e=` 自身不含 m3u8（播放器另行取数），所以 `getPages` 回详情页取。
 * - 只有 key 以 `m3u8` 结尾的线路（`bfzym3u8` 等）是免费 HLS 直链；`mgtv`/`qiyi`/`qq`/`youku`
 *   是**正版平台跳转**（分集 url 指向优酷/爱奇艺/芒果页面），客户端不能播。实测电视剧采样 12 部里
 *   8 部带免费线路，其余只给正版跳转——这类片只放一条说明性章节，点播时抛「仅正版跳转」，
 *   不伪造播放地址。
 *
 * 兄弟站 `jihai88.com` 是同一套引擎（卡片同样是 `mcard`、数据同样是 RSC），但实测 12 部采样全是
 * 正版跳转，故未单独注册；换域名时把 `ConfigKey.Domain` 改成对应站点即可，路由前缀差异见 [VOD_URL_REGEX]。
 */
@ContentSourceParser("DIANYING88", "电影88", "zh", type = ContentType.VIDEO)
internal class Dianying88(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.DIANYING88, pageSize = LIST_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain(DEFAULT_DOMAIN)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val channels = CHANNELS.map { (slug, title) -> ContentTag(title, slug, source) }.toSet()
        return ContentListFilterOptions(
            availableTags = channels,
            tagGroups = listOf(ContentTagGroup("频道", channels)),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            // 搜索不分页（带 pg 仍返回同一批），只解析首页
            return parseCards(fetchDocument("https://$domain/search?wd=${query.urlEncoded()}"))
        }
        val channel = filter.tags.firstOrNull()?.key
            ?.takeIf { key -> CHANNELS.any { it.first == key } }
            ?: DEFAULT_CHANNEL
        val url = buildString {
            append("https://").append(domain).append('/').append(channel)
            if (page > 1) append("?pg=").append(page)
        }
        return parseCards(fetchDocument(url))
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = fetchDocument(url)
        val ld = parseLdJson(doc)
        val title = doc.selectFirst("h1")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ld.getStringOrNull("name")
            ?: manga.title
        val chapters = parseChapters(doc, url)
        if (chapters.isEmpty()) {
            throw ParseException(shortMessage = "电影88：没有解析到播放线路", url = url)
        }
        val cover = ld.getStringOrNull("image")?.takeIf { it.startsWith("http") } ?: manga.coverUrl
        val genres = jsonStrings(ld, "genre")
        val countries = jsonObjectNames(ld, "countryOfOrigin")
        val directors = jsonObjectNames(ld, "director")
        val tags = (genres + countries).mapTo(HashSet()) { ContentTag(it, it, source) }
        return manga.copy(
            title = title,
            description = ld.getStringOrNull("description")?.trim()?.takeIf { it.isNotEmpty() }
                ?: manga.description,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.ifEmpty { manga.tags },
            authors = directors.mapTo(HashSet()) { it },
            state = parseDetailState(doc, chapters.size),
            chapters = chapters,
        )
    }

    /**
     * 播放地址从详情页 RSC 数据里取：`s` 是线路下标、`e` 是该线路内的集下标。
     * 「仅正版跳转」章节的 url 是外站地址，这里直接给出可操作的错误。
     */
    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val pageUrl = chapter.url.toAbsoluteUrl(domain)
        if (!pageUrl.startsWith("https://$domain/")) {
            throw ParseException(
                shortMessage = "电影88：该片只提供正版平台跳转（$pageUrl），本站没有免费播放线路",
                url = pageUrl,
            )
        }
        val vodId = PLAY_URL_REGEX.find(pageUrl)?.groupValues?.getOrNull(1)
            ?: throw ParseException(shortMessage = "电影88：不认识的播放地址 $pageUrl", url = pageUrl)
        val s = pageUrl.toHttpUrl().queryParameter("s")?.toIntOrNull() ?: 0
        val e = pageUrl.toHttpUrl().queryParameter("e")?.toIntOrNull() ?: 0

        val doc = fetchDocument("https://$domain/vod/$vodId")
        val lines = parseSources(doc.html())
        val indexes = parseLineIndexes(doc)
        val line = lines.withIndex().firstOrNull { (index, item) ->
            (indexes[item.label] ?: index) == s
        }?.value
            ?: throw ParseException(shortMessage = "电影88：找不到第 $s 条线路", url = pageUrl)
        if (!line.isFree) {
            throw ParseException(
                shortMessage = "电影88：线路「${line.label}」是正版平台跳转，请切换到「暴风线路」等免费线路",
                url = pageUrl,
            )
        }
        val streamUrl = line.episodes.getOrNull(e)?.url?.takeIf { it.startsWith("http") }
            ?: throw ParseException(
                shortMessage = "电影88：线路「${line.label}」第 ${e + 1} 集没有解析到播放地址",
                url = pageUrl,
            )
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = streamUrl,
                preview = pageUrl,
                source = source,
            ),
        )
    }

    /** 列表与搜索共用一套卡片：`article.mcard`。 */
    internal fun parseCards(doc: Document): List<Content> {
        return doc.select("article.mcard").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/vod/]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = card.selectFirst(".mcard__title")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: card.selectFirst("h3")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val tags = card.select(".mcard__tag").mapNotNull { tag ->
                tag.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct()
            val meta = card.selectFirst(".mcard__meta")?.textOrNull()?.trim().orEmpty()
            val synopsis = card.selectFirst(".mcard__synopsis")?.textOrNull()?.trim()
                ?.substringAfter("简介：")?.takeIf { it.isNotEmpty() }
            // 标签里的年份（2026）不当标签用，单独放 altTitles 便于同年份检索
            val years = tags.filter { YEAR_REGEX.matches(it) }.toSet()
            Content(
                id = generateUid(href),
                title = title,
                altTitles = years,
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = parseCardCover(card.selectFirst("img"), domain),
                tags = tags.filterNot { YEAR_REGEX.matches(it) }
                    .mapTo(HashSet()) { ContentTag(it, it, source) },
                state = parseCardState(meta),
                authors = emptySet(),
                description = synopsis,
                source = source,
            )
        }
    }

    /** 封面来自 Next.js 图片代理 `/_next/image?url=%2Fmedia%2F…`，解码后拼站点域名可直取。 */
    internal fun parseCardCover(img: Element?, host: String): String? {
        val candidates = buildList {
            img?.attrOrNull("src")?.takeIf { it.isNotBlank() }?.let(::add)
            img?.attrOrNull("srcSet")?.split(',')?.forEach { part ->
                part.trim().substringBefore(' ').takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        for (candidate in candidates) {
            val path = NEXT_IMAGE_URL_PARAM.find(candidate)?.groupValues?.getOrNull(1)
                ?.let { decodeUrlComponent(it) }
                ?: candidate
            if (path.startsWith("/media/") || path.startsWith("http")) {
                return path.toAbsoluteUrl(host)
            }
        }
        return null
    }

    /**
     * 分集来自详情页 RSC 数据（[parseSources]），只保留 key 以 `m3u8` 结尾的免费线路。
     * 线路下标 `s` 优先取 SSR 区块里的 `/play/{id}?s=`（[parseLineIndexes]），取不到就用数据顺序。
     */
    internal fun parseChapters(doc: Document, pageUrl: String): List<ContentChapter> {
        val vodId = VOD_URL_REGEX.find(pageUrl)?.groupValues?.getOrNull(1) ?: return emptyList()
        val lines = parseSources(doc.html())
        val indexes = parseLineIndexes(doc)
        val chapters = ArrayList<ContentChapter>()
        val seen = HashSet<String>()
        var number = 0
        lines.forEachIndexed { index, line ->
            if (!line.isFree) return@forEachIndexed
            val s = indexes[line.label] ?: index
            line.episodes.forEachIndexed { e, episode ->
                val href = "/play/$vodId?s=$s&e=$e"
                if (!seen.add(href)) return@forEachIndexed
                chapters += ContentChapter(
                    id = generateUid(href),
                    title = episode.label.ifBlank { "第${e + 1}集" },
                    number = (++number).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = line.label,
                    uploadDate = 0L,
                    branch = line.label,
                    source = source,
                )
            }
        }
        if (chapters.isEmpty()) {
            val official = lines.filterNot { it.isFree }
            if (official.isNotEmpty()) {
                // 只有正版跳转：放一条说明性章节，点播时由 getPages 抛出「仅正版跳转」
                val platforms = official.joinToString("、") { it.label }
                val external = official.firstNotNullOfOrNull { it.episodes.firstOrNull()?.url }
                chapters += ContentChapter(
                    id = generateUid(pageUrl),
                    title = "仅正版跳转：$platforms",
                    number = 1f,
                    volume = 0,
                    url = external ?: pageUrl,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = platforms,
                    source = source,
                )
            }
        }
        return chapters
    }

    /**
     * 从 Next.js RSC flight 数据里取线路与分集。同一段数据在页面里出现多次
     * （HTML 里的 `\"` 转义版与 flight 版），先反转义再按 key 去重。
     */
    internal fun parseSources(html: String): List<StreamSource> {
        val text = html
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        val result = ArrayList<StreamSource>()
        val seen = HashSet<String>()
        for (match in SOURCE_REGEX.findAll(text)) {
            val key = match.groupValues[1]
            if (!seen.add(key)) continue
            val episodes = EPISODE_REGEX.findAll(match.groupValues[3])
                .map { episode -> StreamEpisode(episode.groupValues[1], episode.groupValues[2]) }
                .toList()
            result += StreamSource(key = key, label = match.groupValues[2], episodes = episodes)
        }
        return result
    }

    /** SSR 区块 `section[aria-labelledby^=source-]`：`h2` 是线路名，第一个集链接的 `s` 是线路下标。 */
    internal fun parseLineIndexes(doc: Document): Map<String, Int> {
        val result = HashMap<String, Int>()
        for (section in doc.select("section[aria-labelledby^=source-]")) {
            val label = section.selectFirst("h2")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val s = section.selectFirst("a[href*=/play/]")?.attrOrNull("href")
                ?.let { LINK_S_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            if (s != null && label !in result) {
                result[label] = s
            }
        }
        return result
    }

    internal fun parseLdJson(doc: Document): JSONObject {
        return doc.select("script[type=application/ld+json]")
            .mapNotNull { script -> script.data().takeIf { it.isNotBlank() }?.toJSONObjectOrNull() }
            .firstOrNull { it.getStringOrNull("@type") in LD_TYPES }
            ?: JSONObject()
    }

    private fun jsonStrings(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun jsonObjectNames(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseCardState(meta: String): ContentState = if (
        meta.contains("完结") || REGEX_ALL_EPISODES.containsMatchIn(meta)
    ) {
        ContentState.FINISHED
    } else {
        ContentState.ONGOING
    }

    private fun parseDetailState(doc: Document, episodeCount: Int): ContentState {
        val text = doc.text()
        return if (episodeCount > 0 && (REGEX_ALL_EPISODES.containsMatchIn(text) || text.contains("已完结"))) {
            ContentState.FINISHED
        } else {
            ContentState.ONGOING
        }
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    /** 一条播放线路：免费 HLS（`bfzym3u8`…）或正版平台跳转（`mgtv`/`qiyi`/`qq`/`youku`）。 */
    internal data class StreamSource(
        val key: String,
        val label: String,
        val episodes: List<StreamEpisode>,
    ) {
        val isFree: Boolean get() = key.endsWith(M3U8_KEY_SUFFIX)
    }

    internal data class StreamEpisode(val label: String, val url: String)

    private companion object {
        const val DEFAULT_DOMAIN = "dianying88.net"
        const val LIST_PAGE_SIZE = 20
        const val DEFAULT_CHANNEL = "latest"
        const val M3U8_KEY_SUFFIX = "m3u8"

        /** 频道 slug → 名称，取自站点导航；`type/{id}` 的数字即站点分类 id。 */
        val CHANNELS = listOf(
            "type/20" to "电影",
            "type/37" to "电视剧",
            "type/43" to "动漫",
            "type/45" to "综艺",
            "type/47" to "B站",
            "latest" to "最近更新",
            "ranking" to "高分推荐",
        )

        val LD_TYPES = setOf("TVSeries", "Movie", "TVEpisode", "CreativeWork")
        val NEXT_IMAGE_URL_PARAM = Regex("""url=([^&]+)""")
        val YEAR_REGEX = Regex("""(19|20)\d{2}""")
        val REGEX_ALL_EPISODES = Regex("""全\s*\d+\s*集""")
        val VOD_URL_REGEX = Regex("""/vod/(\d+)""")
        val PLAY_URL_REGEX = Regex("""/play/(\d+)""")
        val LINK_S_REGEX = Regex("""[?&]s=(\d+)""")
        val SOURCE_REGEX = Regex(
            "\"source\":\\{\"key\":\"([^\"]+)\",\"label\":\"([^\"]*)\",\"episodes\":\\[(.*?)\\]\\}",
            RegexOption.DOT_MATCHES_ALL,
        )
        val EPISODE_REGEX = Regex("\"label\":\"([^\"]*)\",\"url\":\"([^\"]*)\"")

        fun decodeUrlComponent(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }
    }
}
