package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Redecanais - 巴西影视/成人视频网站
 *
 * 网站: https://redecanais.to/ (原 redecanais.win 已重定向到 redecanais.capital)
 * 最后验证: 2025-08-03
 *
 * ⚠️ 可用性状态: [BROKEN] 需要浏览器 JS 验证才能访问
 * - 主域名 redecanais.to HTTP 200 JS Challenge (JWT-based location.replace)
 * - 旧域名 redecanais.win HTTP 200 重定向到 redecanais.capital
 * - redecanais.capital HTTP 403 Cloudflare managed challenge
 * - 所有域名均无法直接通过 HTTP 解析，需要用户在浏览器中完成验证
 * - 标记为 BROKEN，等待网站去除保护或找到替代方案
 *
 * 页面结构 (推断，无法获取实际 HTML):
 * - 首页: /
 * - 电影: /filmes/
 * - 剧集: /series/
 * - 搜索: /search/
 * - 分类: /category/{name}/
 * - 详情页: /{slug}/
 *
 * 解析器特性:
 * - ✅ JS Challenge 检测 requestBrowserAction 提示用户验证
 * - ✅ 静态标签过滤器 (32个葡萄牙语标签)
 * - ✅ 搜索支持
 * - ✅ 多策略视频 URL 提取 (含 iframe 嵌入)
 * - ❓ 翻页需要实际页面确认
 * - ❓ 列表 HTML 选择器需要实际页面确认
 */

// 2025-08-03 验证: [BROKEN] 所有域名均需浏览器 JS/CF 验证
// redecanais.to HTTP 200 JS Challenge (JWT-based location.replace) — 无法直接解析
// redecanais.win → redecanais.capital HTTP 403 CF managed challenge
@ContentSourceParser("REDECANAIS", "Redecanais", type = ContentType.HENTAI_VIDEO)
internal class Redecanais(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.REDECANAIS, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("redecanais.to")

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    /**
     * 静态分类标签 - 基于巴西影视网站常见分类
     */
    private val BUILTIN_TAGS: Set<ContentTag> = linkedSetOf(
        // 内容类型
        ContentTag("Filmes", "filmes", source),
        ContentTag("Series", "series", source),
        ContentTag("Canais", "canais", source),
        // 语言/配音
        ContentTag("Dublado", "dublado", source),
        ContentTag("Legendado", "legendado", source),
        ContentTag("Nacional", "nacional", source),
        // 分类
        ContentTag("Acao", "acao", source),
        ContentTag("Aventura", "aventura", source),
        ContentTag("Comedia", "comedia", source),
        ContentTag("Drama", "drama", source),
        ContentTag("Ficcao Cientifica", "ficcao-cientifica", source),
        ContentTag("Terror", "terror", source),
        ContentTag("Suspense", "suspense", source),
        ContentTag("Romance", "romance", source),
        ContentTag("Documentario", "documentario", source),
        ContentTag("Anime", "anime", source),
        ContentTag("Animacao", "animacao", source),
        ContentTag("Infantil", "infantil", source),
        ContentTag("Adulto", "adulto", source),
        ContentTag("Erotico", "erotico", source),
        // 来源
        ContentTag("Netflix", "netflix", source),
        ContentTag("Amazon Prime", "amazon-prime", source),
        ContentTag("Disney+", "disney-plus", source),
        ContentTag("HBO Max", "hbo-max", source),
        ContentTag("Star+", "star-plus", source),
        ContentTag("Paramount+", "paramount-plus", source),
        // 年份
        ContentTag("2025", "2025", source),
        ContentTag("2024", "2024", source),
        ContentTag("2023", "2023", source),
        ContentTag("2022", "2022", source),
        ContentTag("2021", "2021", source),
        ContentTag("2020", "2020", source),
        ContentTag("Antigos", "antigos", source),
    )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableContentTypes = EnumSet.of(ContentType.HENTAI_VIDEO),
            availableTags = BUILTIN_TAGS,
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val response = webClient.httpGet(url, getRequestHeaders())

        // 检查 JS Challenge 保护
        if (isChallengeResponse(response)) {
            context.requestBrowserAction(this, url)
        }

        val doc = response.parseHtml()

        // 检查是否仍在 JS challenge 页面
        if (doc.title()?.contains("Loading", ignoreCase = true) == true) {
            context.requestBrowserAction(this, url)
        }

        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val response = webClient.httpGet(manga.publicUrl, getRequestHeaders())

        if (isChallengeResponse(response)) {
            context.requestBrowserAction(this, manga.publicUrl)
        }

        val doc = response.parseHtml()

        if (doc.title()?.contains("Loading", ignoreCase = true) == true) {
            context.requestBrowserAction(this, manga.publicUrl)
        }

        // 提取标题
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1.entry-title, h1.post-title, h1.title, h1")?.text()?.trim()
            ?: manga.title

        // 提取描述
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("div.entry-content p, div.post-content p, div.synopsis, div.descricao")
                ?.text()?.trim()

        // 提取封面
        val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(
                "div.entry-thumbnail img, div.post-thumbnail img, article img.wp-post-image, img.poster"
            )?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
            ?: manga.coverUrl

        // 提取标签/分类
        val tags = doc.select(
            "a[rel=tag], a[href*=/tag/], a[href*=/category/], a[href*=/categoria/], a[href*=/genero/]"
        ).mapNotNullToSet { elem ->
            val tagName = elem.text().trim()
            if (tagName.isNotEmpty()) {
                ContentTag(
                    key = elem.attr("href").substringAfterLast('/').substringBefore('?'),
                    title = tagName,
                    source = source,
                )
            } else null
        }

        // 提取元数据（时长、年份等）
        val metadataParts = mutableListOf<String>()
        doc.select(
            "span.duration, span.duracao, span.year, span.ano, span.quality, span.qualidade"
        ).forEach { span ->
            val text = span.text().trim()
            if (text.isNotEmpty()) metadataParts.add(text)
        }
        val metadataDescription = if (metadataParts.isNotEmpty()) {
            metadataParts.joinToString(" | ")
        } else null

        val finalDescription = listOfNotNull(description, metadataDescription)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

        // 创建单个章节
        val chapter = ContentChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = title,
            description = finalDescription,
            coverUrl = coverUrl,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        if (chapter.url.contains(".m3u8") || chapter.url.contains(".mp4")) {
            return listOf(
                ContentPage(
                    id = generateUid(chapter.url),
                    url = chapter.url,
                    preview = null,
                    source = source,
                ),
            )
        }

        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(fullUrl, getRequestHeaders())

        if (isChallengeResponse(response)) {
            context.requestBrowserAction(this, fullUrl)
        }

        val doc = response.parseHtml()
        val videoUrl = extractVideoUrl(doc) ?: return emptyList()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        return listOf(
            ContentPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = poster,
                source = source,
            ),
        )
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val base = StringBuilder("https://").append(domain)

        if (!filter.query.isNullOrBlank()) {
            // 搜索
            base.append("/search/?q=").append(filter.query.urlEncoded())
            if (page > 1) base.append("&page=").append(page)
        } else if (filter.tags.isNotEmpty()) {
            // 分类过滤
            val firstTag = filter.tags.first()
            base.append("/").append(firstTag.key).append("/")
            if (page > 1) base.append("page/").append(page).append("/")
        } else {
            // 主页
            base.append("/")
            if (page > 1) base.append("page/").append(page).append("/")
        }

        return base.toString()
    }

    private fun isChallengeResponse(response: okhttp3.Response): Boolean {
        // 检查 HTTP 403
        if (response.code == 403) return true

        // 检查 Cloudflare
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) return true

        // 检查 JS redirect challenge
        val body = runCatching {
            response.peekBody(1024 * 10).string()
        }.getOrNull() ?: return false

        return body.contains("location.replace") && body.contains("Loading")
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>(pageSize)
        val seen = LinkedHashSet<String>()

        // 策略 1: 文章/视频卡片
        val articles = doc.select(
            "article.post, article.type-post, article.hentai, " +
                "div.post, div.movie-item, div.video-item, div.item"
        )

        for (article in articles) {
            val link = article.selectFirst("a[href]")
            if (link == null) continue

            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            // 过滤非详情链接
            if (href.contains("/category/") || href.contains("/tag/") ||
                href.contains("/categoria/") || href.contains("/page/") ||
                href.contains("/search/") || href == "/" || href == domain
            ) continue

            val absoluteUrl = href.toAbsoluteUrl(domain)
            if (!seen.add(absoluteUrl)) continue

            val img = article.selectFirst("img")
            val coverUrl = img?.let {
                it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
            }

            val title = article.selectFirst(
                "h2.entry-title, h2.post-title, h2.title, h2, h3"
            )?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: link.attr("title")?.trim()
                ?: link.text().trim().ifEmpty { "Untitled" }

            val duration = article.selectFirst("span.duration, span.duracao, span.time")
                ?.text()?.trim()
            val quality = article.selectFirst("span.quality, span.qualidade")
                ?.text()?.trim()

            val descParts = listOfNotNull(
                if (duration.isNullOrBlank()) null else "Duracao: $duration",
                if (quality.isNullOrBlank()) null else "Qualidade: $quality",
            ).joinToString(" | ")

            items.add(
                Content(
                    id = generateUid(absoluteUrl),
                    url = absoluteUrl.removePrefix("https://$domain")
                        .removePrefix("http://$domain"),
                    publicUrl = absoluteUrl,
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = coverUrl ?: "",
                    largeCoverUrl = coverUrl,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = descParts.takeIf { it.isNotBlank() },
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                )
            )

            if (items.size >= pageSize) break
        }

        // 策略 2: 通用视频卡片
        if (items.isEmpty()) {
            val cards = doc.select("a[href]").filter { a ->
                val href = a.attr("href")
                href.isNotBlank() && !href.contains("/category/") &&
                    !href.contains("/tag/") && !href.contains("/categoria/") &&
                    !href.contains("/page/") && !href.contains("/search/") &&
                    !href.contains("/cdn-cgi/") && href != "/" &&
                    !href.startsWith("#") && !href.startsWith("javascript:") &&
                    !href.contains("consentmanager")
            }

            for (card in cards) {
                val href = card.attr("href")
                val absoluteUrl = href.toAbsoluteUrl(domain)
                if (!seen.add(absoluteUrl)) continue

                val img = card.selectFirst("img")
                if (img == null) continue

                val coverUrl = img.attr("data-src")
                    .ifBlank { img.attr("data-original").ifBlank { img.attr("src") } }
                val title = img.attr("alt").trim()
                    .ifBlank { card.attr("title").trim().ifBlank { card.text().trim().ifEmpty { "Untitled" } } }

                items.add(
                    Content(
                        id = generateUid(absoluteUrl),
                        url = absoluteUrl.removePrefix("https://$domain")
                            .removePrefix("http://$domain"),
                        publicUrl = absoluteUrl,
                        title = title,
                        altTitles = emptySet(),
                        coverUrl = coverUrl,
                        largeCoverUrl = coverUrl,
                        authors = emptySet(),
                        tags = emptySet(),
                        state = null,
                        description = null,
                        contentRating = ContentRating.ADULT,
                        source = source,
                        rating = RATING_UNKNOWN,
                    )
                )

                if (items.size >= pageSize) break
            }
        }

        return items
    }

    private fun extractVideoUrl(doc: Document): String? {
        val html = doc.outerHtml()

        // 策略 1: og:video meta 标签
        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")
        if (!ogVideo.isNullOrBlank()) return ogVideo

        // 策略 2: video 标签 source
        val videoSource = doc.selectFirst("video source[src]")?.attr("src")
        if (!videoSource.isNullOrBlank() && !videoSource.startsWith("blob:")) return videoSource

        val videoSrc = doc.selectFirst("video[src]")?.attr("src")
        if (!videoSrc.isNullOrBlank() && !videoSrc.startsWith("blob:")) return videoSrc

        // 策略 3: iframe 嵌入（常见于巴西视频站）
        val iframe = doc.selectFirst("iframe[src]")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank() && !iframeSrc.startsWith("about:blank")) {
                if (iframeSrc.contains(".m3u8") || iframeSrc.contains(".mp4")) {
                    return iframeSrc
                }
                return iframeSrc.toAbsoluteUrl(domain)
            }
        }

        // 策略 4: JavaScript 中的视频 URL
        val jsVideoPatterns = listOf(
            Regex(
                """(?:videoUrl|video_url|videoSrc|video_src|file|source|src|player_url|embed_url)\s*[:=]\s*['\"]([^'\"]+\.(?:m3u8|mp4)[^'\"]*)['\"]""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""['\"](https?://[^'\"]+\.(?:m3u8|mp4)[^'\"]*)['\"]"""),
        )

        for (pattern in jsVideoPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        // 策略 5: 通用 m3u8/mp4 正则
        val m3u8Pattern = Regex(
            """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""",
            RegexOption.IGNORE_CASE,
        )
        val m3u8Match = m3u8Pattern.find(html)
        if (m3u8Match != null) return m3u8Match.value

        val mp4Pattern = Regex(
            """https?://[^\s"'<>]+\.mp4[^\s"'<>]*""",
            RegexOption.IGNORE_CASE,
        )
        val mp4Match = mp4Pattern.find(html)
        return mp4Match?.value
    }
}
