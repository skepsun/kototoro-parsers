package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import java.net.URLDecoder
import java.util.Base64
import java.util.EnumSet

/**
 * 94mt.cc / 天美影院
 * - 首页与分类页公开可访问，无需登录
 * - 播放页内嵌 player_aaaa JSON，包含 m3u8 直链
 * - 使用 MacCMS 风格 URL：/index.php/vod/play/id/{id}/sid/{sid}/nid/{nid}.html
 */
@MangaSourceParser(name = "MT94", title = "94MT", locale = "zh", type = ContentType.VIDEO)
internal class Tm94Parser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.MT94,
    pageSize = 24,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "www.94mt.cc",
        "www.tianmei.one",
        "www.xbyc.cc",
        "www.wyxk.cc",
    )

    override val userAgentKey: ConfigKey.UserAgent = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = CATEGORY_TAGS.map { (title, id) ->
            MangaTag(title, "cat:$id", source)
        }.toSet()
        return MangaListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(MangaTagGroup("分类", tags)),
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    private fun listUrl(page: Int, filter: MangaListFilter): String {
        val catId = filter.tags.firstOrNull { it.key.startsWith("cat:") }?.key?.substringAfter("cat:") ?: "1"
        val p = if (page < 1) 1 else page
        return "http://$domain/index.php/vod/type/id/$catId/page/$p.html"
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = listUrl(page, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = HashSet<String>()

        // 卡片：a.item-link[href*=play/id]
        doc.select("a.item-link[href*=/vod/play/id/]").forEach { a ->
            val href = a.attrOrNull("href") ?: return@forEach
            val id = VIDEO_ID_REGEX.find(href)?.groupValues?.getOrNull(1) ?: return@forEach
            if (!seen.add(id)) return@forEach

            val container = a.parent()
            val img = container?.selectFirst("img")
            val cover = img?.attrOrNull("data-original") ?: img?.attrOrNull("src")
            val title = a.attrOrNull("title")
                ?: img?.attrOrNull("alt")
                ?: a.text().takeIf { it.isNotBlank() }
                ?: "视频 $id"

            items.add(
                Manga(
                    id = generateUid(id),
                    url = "/index.php/vod/detail/id/$id.html",
                    publicUrl = "/index.php/vod/detail/id/$id.html".toAbsoluteUrl(domain),
                    title = title,
                    coverUrl = cover,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.ADULT,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    largeCoverUrl = null,
                    description = null,
                    chapters = null,
                    source = source,
                ),
            )

            if (items.size >= pageSize) return@forEach
        }

        return items
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attrOrNull("content")
            ?: doc.selectFirst(".movie-title")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()
            ?: manga.title

        val cover = doc.selectFirst(".con-pic img, img.img-thumbnail")?.attrOrNull("src")
            ?: manga.coverUrl

        val tags = doc.select(".con-detail ul:has(.info-label:contains(类型)) a, a[href*=/vod/type/id/]").mapNotNull { a ->
            val text = a.text().trim()
            if (text.isNotEmpty()) MangaTag(text, text, source) else null
        }.toSet()

        val actors = doc.select(".con-detail ul:has(.info-label:contains(主演)) a").mapNotNull { a ->
            a.text().trim().takeIf { it.isNotEmpty() }
        }.toSet()

        val chapters = doc.select(".play-list .dslist-group-item a[href*=/vod/play/id/]").mapIndexed { idx, a ->
            val href = a.attrOrNull("href") ?: return@mapIndexed null
            val id = VIDEO_ID_REGEX.find(href)?.groupValues?.getOrNull(1) ?: generateUid(href)
            val titleText = a.text().trim().ifEmpty { "第${idx + 1}集" }
            MangaChapter(
                id = generateUid("$id|$href"),
                url = href,
                title = titleText,
                number = (idx + 1).toFloat(),
                uploadDate = 0L,
                volume = 0,
                branch = null,
                scanlator = null,
                source = source,
            )
        }.filterNotNull()

        val desc = doc.selectFirst(".con-des .summary")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        return manga.copy(
            title = title,
            coverUrl = cover,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            authors = if (actors.isNotEmpty()) actors else manga.authors,
            description = desc ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val playUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(playUrl, getRequestHeaders()).parseHtml()
        val scripts = doc.select("script").mapNotNull { it.data().takeIf { data -> data.contains("player_") } }

        // 优先 player_aaaa，其次 player_data；若脚本未命中则用全页 HTML 正则
        val html = doc.outerHtml()
        val json = scripts.asSequence().mapNotNull { extractPlayerJson(it) }
            .firstOrNull()
            ?: extractPlayerJson(html)
            ?: extractPlayerJsonByUrlOnly(html)
            ?: return emptyList()

        val encrypt = json.optInt("encrypt", 0)
        val urlRaw = json.optString("url").takeIf { it.isNotBlank() }
            ?: json.optString("video").takeIf { it.isNotBlank() }
            ?: run {
                // 尝试从 HTML 正则兜底
                val fallback = fallbackStream(doc) ?: run {
                    context.requestBrowserAction(this, playUrl)
                    return emptyList()
                }
                fallback
            }

        val decodedUrl = decodeUrl(urlRaw, encrypt)
            ?: fallbackStream(doc)
            ?: return emptyList()

        return listOf(
            MangaPage(
                id = generateUid(decodedUrl),
                url = decodedUrl,
                preview = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content"),
                source = source,
            ),
        )
    }

    private fun decodeUrl(raw: String, encrypt: Int): String? {
        val cleaned = raw.replace("\\/", "/")
        return when (encrypt) {
            0 -> URLDecoder.decode(cleaned, "UTF-8")
            1 -> URLDecoder.decode(cleaned, "UTF-8")
            2 -> runCatching {
                val bytes = Base64.getDecoder().decode(cleaned)
                URLDecoder.decode(String(bytes, Charsets.UTF_8), "UTF-8")
            }.getOrNull()
            else -> URLDecoder.decode(cleaned, "UTF-8")
        }
    }

    private fun fallbackStream(doc: org.jsoup.nodes.Document): String? {
        val html = doc.outerHtml()
        val regexes = listOf(
            Regex("""https?://[^"'\s]+\.m3u8""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s]+\.mp4""", RegexOption.IGNORE_CASE),
        )
        regexes.forEach { r ->
            r.find(html)?.value?.let { return it }
        }
        return null
    }

    private fun extractPlayerJson(script: String): JSONObject? {
        // 兼容 player_aaaa = {...}; 或 player_data= {...};
        val m1 = PLAYER_JSON_REGEX.find(script)
        val m2 = PLAYER_JSON_ALT_REGEX.find(script)
        val raw = m1?.groupValues?.getOrNull(1) ?: m2?.groupValues?.getOrNull(1) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun extractPlayerJsonByUrlOnly(body: String): JSONObject? {
        val m = URL_ONLY_REGEX.find(body) ?: return null
        val url = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return JSONObject().put("url", url)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", userAgentKey.defaultValue)
        .add("Referer", "http://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .build()

    private companion object {
        private val VIDEO_ID_REGEX = Regex("/id/(\\d+)/")
        // ICU 正则需转义花括号，使用 DOT_MATCHES_ALL 支持跨行
        private val PLAYER_JSON_REGEX = Regex("""player_aaaa\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
        private val PLAYER_JSON_ALT_REGEX = Regex("""player_data\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
        private val URL_ONLY_REGEX = Regex(""""url"\s*:\s*"(https?:[^"]+)"""", RegexOption.IGNORE_CASE)

        // 部分公开分类 ID（用于筛选）
        private val CATEGORY_TAGS = listOf(
            "麻豆视频" to "1",
            "91制片厂" to "2",
            "天美传媒" to "3",
            "蜜桃传媒" to "4",
            "皇家华人" to "5",
            "星空传媒" to "6",
            "精东影业" to "7",
            "性视界传媒" to "8",
            "草莓原创" to "9",
            "萝莉社" to "10",
            "兔子先生" to "11",
            "杏吧原创" to "12"
        )
    }
}
