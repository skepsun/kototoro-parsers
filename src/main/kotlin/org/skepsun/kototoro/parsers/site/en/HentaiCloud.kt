package org.skepsun.kototoro.parsers.site.en

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

/**
 * HentaiCloud - 成人视频网站
 *
 * 网站: https://www.hentaicloud.com/
 * 注意: 站点带 AVS/Cloudflare 类验证页，首次访问可能先出现一个“正在检查浏览器”
 * 的验证页，自动通过后即可正常浏览。解析器在列表/详情/播放页检测到验证页时
 * 触发 requestBrowserAction 让 App 打开浏览器完成验证。
 * - 列表: /videos?page={n}；搜索: /search?search_type=videos&search_query={q}
 * - 标签: /videos/{tag}?page={n}
 * - 播放: <video><source src="/media/videos/hd/{id}.mp4" res=720>（排除 .php 端点）
 */
@ContentSourceParser("HENTAICLOUD", "HentaiCloud", "en", type = ContentType.HENTAI_VIDEO)
internal class HentaiCloud(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HENTAICLOUD, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("www.hentaicloud.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableTags = getDefaultTags(),
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, filter)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        checkProtection(response, doc, url)
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())
        val doc = response.parseHtml()
        checkProtection(response, doc, manga.publicUrl)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)

        val tags = doc.select("a[href*=/genre/], a[href*=/tag/], a[href*=/category/]").mapNotNull {
            val text = it.text().trim()
            val href = it.attr("href")
            if (text.isNotEmpty() && href.isNotEmpty()) {
                ContentTag(text, href.substringAfterLast('/'), source)
            } else null
        }.toSet()

        val chapters = listOf(ContentChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        ))

        return manga.copy(
            title = title,
            description = description?.ifBlank { null },
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(url, getRequestHeaders())
        val doc = response.parseHtml()
        checkProtection(response, doc, url)

        val streams = extractStreams(doc)
        if (streams.isNotEmpty()) {
            return streams.map { src ->
                ContentPage(
                    id = generateUid(src),
                    url = src,
                    preview = null,
                    source = source,
                )
            }
        }

        val iframe = doc.selectFirst("iframe[src]")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src").toAbsoluteUrl(domain)
            val iframeDoc = webClient.httpGet(iframeSrc, getRequestHeaders()).parseHtml()
            val iframeStreams = extractStreams(iframeDoc)
            if (iframeStreams.isNotEmpty()) {
                return iframeStreams.map { src ->
                    ContentPage(
                        id = generateUid(src),
                        url = src,
                        preview = null,
                        source = source,
                    )
                }
            }
        }

        context.requestBrowserAction(this, url)
        return emptyList()
    }

    /**
     * 站点带 AVS/Cloudflare 类验证页：浏览器自动通过后即可正常浏览。
     * 检测到验证/挑战页时触发 requestBrowserAction，让 App 打开浏览器完成验证。
     */
    private fun checkProtection(response: okhttp3.Response, doc: Document, url: String) {
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED || isChallengePage(doc)) {
            context.requestBrowserAction(this, url)
        }
    }

    internal fun isChallengePage(doc: Document): Boolean {
        val title = doc.title().lowercase()
        val html = doc.outerHtml().lowercase()
        return title.contains("just a moment") ||
            title.contains("verify") ||
            title.contains("verification") ||
            title.contains("checking your browser") ||
            title.contains("please wait") ||
            title.contains("enable javascript") ||
            html.contains("checking your browser") ||
            html.contains("cf-browser-verification") ||
            html.contains("cf_chl") ||
            html.contains("__cf_chl_opt") ||
            html.contains("challenge-platform") ||
            html.contains("g-recaptcha") ||
            html.contains("cf-turnstile") ||
            (html.contains("verify") && html.contains("javascript"))
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    internal fun buildListUrl(page: Int, filter: ContentListFilter): String {
        val base = "https://$domain"
        return if (!filter.query.isNullOrBlank()) {
            val q = filter.query.urlEncoded()
            "$base/search?search_type=videos&search_query=$q&page=$page"
        } else if (filter.tags.isNotEmpty()) {
            val tag = filter.tags.first().key.urlEncoded()
            "$base/videos/$tag?page=$page"
        } else {
            "$base/videos?page=$page"
        }
    }

    internal fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()
        val links = doc.select("div.video-item a[href*=/video/]")
        for (link in links) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst("h3, h2, .title, .name")?.text()?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = link.selectFirst("img[src], img[data-src]")?.let {
                (it.attr("data-src").ifBlank { it.attr("src") }).toAbsoluteUrlOrNull(domain)
            }
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.ADULT, source = source, rating = RATING_UNKNOWN,
            ))
            if (items.size >= pageSize) break
        }
        return items
    }

    /**
     * 提取播放地址。优先 <source>（带 res 清晰度属性）且按清晰度降序，
     * 排除 .php 之类的非媒体端点；随后补充 video[src]、og:video 与内嵌 mp4/m3u8。
     */
    internal fun extractStreams(doc: Document): List<String> {
        val streams = linkedMapOf<String, Int>()

        fun put(url: String, quality: Int) {
            if (url.isBlank()) return
            val previous = streams[url]
            if (previous == null || quality > previous) streams[url] = quality
        }

        doc.select("source[src]").forEach { src ->
            val raw = src.attr("src")
            if (raw.isBlank() || raw.contains(".php", ignoreCase = true)) return@forEach
            val res = src.attr("res").toIntOrNull() ?: 0
            put(raw.toAbsoluteUrl(domain), res)
        }
        doc.select("video[src]").forEach { v ->
            val raw = v.attr("src")
            if (raw.isBlank() || raw.contains(".php", ignoreCase = true)) return@forEach
            put(raw.toAbsoluteUrl(domain), 0)
        }
        doc.select("meta[property=og:video]").forEach { meta ->
            val raw = meta.attr("content")
            if (raw.isBlank()) return@forEach
            put(raw.toAbsoluteUrl(domain), 0)
        }

        val html = doc.outerHtml()
        Regex("https?://[^\"'\\s>]+\\.(?:m3u8|mp4)", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            if (!m.value.contains(".php", ignoreCase = true)) put(m.value, 0)
        }
        Regex("/media/videos/(?:hd|iphone)/\\d+\\.mp4", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            put(m.value.toAbsoluteUrl(domain), 0)
        }
        Regex("""/media/videos/hd/\d+\.mp4""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            put(m.value.toAbsoluteUrl(domain), 720)
        }
        Regex("""/media/videos/iphone/\d+\.mp4""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            put(m.value.toAbsoluteUrl(domain), 240)
        }

        return streams.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
            )
            .map { it.key }
    }

    private fun getDefaultTags(): Set<ContentTag> = linkedSetOf(
        ContentTag("Big Boobs", "big-boobs", source),
        ContentTag("Blowjob", "blowjob", source),
        ContentTag("Creampie", "creampie", source),
        ContentTag("Futanari", "futanari", source),
        ContentTag("Harem", "harem", source),
        ContentTag("Loli", "loli", source),
        ContentTag("MILF", "milf", source),
        ContentTag("NTR", "ntr", source),
        ContentTag("Tentacles", "tentacles", source),
        ContentTag("Yuri", "yuri", source),
        ContentTag("3D", "3d", source),
        ContentTag("Anal", "anal", source),
        ContentTag("Ahegao", "ahegao", source),
        ContentTag("BDSM", "bdsm", source),
        ContentTag("Schoolgirl", "schoolgirl", source),
    )
}
