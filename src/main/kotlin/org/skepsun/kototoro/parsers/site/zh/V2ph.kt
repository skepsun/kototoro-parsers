package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.*

/**
 * 微图坊 (v2ph.com)  gallery parser
 *
 * Login is required to view full albums (non-logged-in users are limited to ~10 images).
 * Uses browser-based login via WebView since the site has Cloudflare protection.
 */
@ContentSourceParser(name = "V2PH", title = "微图坊", locale = "zh", type = ContentType.HENTAI_MANGA)
internal class V2ph(
    context: ContentLoaderContext,
) : PagedContentParser(
    context = context,
    source = ContentParserSource.V2PH,
    pageSize = 20,
), ContentParserAuthProvider {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("www.v2ph.com")

    private val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override fun getRequestHeaders(): okhttp3.Headers {
        return super.getRequestHeaders().newBuilder()
            .set("User-Agent", defaultUserAgent)
            .set("Referer", "https://$domain/")
            .set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
    }

    override val authUrl: String
        get() = "https://$domain/login"

    override suspend fun isAuthorized(): Boolean {
        return try {
            val response = webClient.httpGet("https://$domain", getRequestHeaders())
            val doc = response.parseHtml()
            // Logged in users see a dropdown with their name or a logout link
            // Search for logout link or user profile link
            val isLoggedOut = doc.selectFirst("a[href*='/login'], a[href*='/register']") != null
            val isLoggedIn = doc.selectFirst("a[href*='/logout'], a[href*='/user/'], .nav-link .fa-user") != null
            isLoggedIn || !isLoggedOut
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getUsername(): String {
        try {
            val doc = webClient.httpGet("https://$domain", getRequestHeaders()).parseHtml()
            // V2ph typically shows the username in a dropdown toggle in the top right
            val username = doc.selectFirst(".navbar .dropdown-toggle")?.text()?.trim()
                ?: doc.selectFirst("a[href*='/user/profile']")?.text()?.trim()
                ?: doc.selectFirst(".user-info .name")?.text()?.trim()
            
            if (!username.isNullOrBlank() && username != "登录") {
                return username
            }
        } catch (e: Exception) {
            // Ignore for now
        }
        throw AuthRequiredException(source)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

@OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)
    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableLocales = setOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.JAPANESE,
            Locale.ENGLISH,
            Locale.KOREAN,
            Locale.FRENCH,
            Locale.GERMAN,
            Locale("es"),
            Locale("ru"),
            Locale("ar"),
        ),
        tagGroups = listOf(
            ContentTagGroup(
                "标签",
                setOf(
                    ContentTag("性感美女", "/category/sexy-girls", source),
                    ContentTag("女神", "/category/nvshen", source),
                    ContentTag("短发", "/category/short-hair", source),
                    ContentTag("清纯", "/category/pure", source),
                    ContentTag("内衣美女", "/category/underwear-beauty", source),
                    ContentTag("杂志", "/category/magazine", source),
                    ContentTag("嫩模", "/category/sexy-model", source),
                    ContentTag("美腿", "/category/beautiful-legs", source),
                    ContentTag("日本少女", "/category/japanese-girls", source),
                    ContentTag("极品", "/category/best-quality", source),
                    ContentTag("外拍", "/category/outside", source),
                    ContentTag("比基尼", "/category/bikini-girls", source),
                ),
            ),
            ContentTagGroup(
                "国家",
                setOf(
                    ContentTag("中国大陆", "/country/china", source),
                    ContentTag("日本", "/country/japan", source),
                    ContentTag("韩国", "/country/south-korea", source),
                    ContentTag("台湾", "/country/taiwan", source),
                    ContentTag("泰国", "/country/thailand", source),
                    ContentTag("欧美", "/country/europe", source),
                ),
            ),
            ContentTagGroup(
                "写真机构",
                setOf(
                    ContentTag("秀人网", "/company/XIUREN", source),
                    ContentTag("尤蜜荟", "/company/YOUMI", source),
                    ContentTag("魔范学院", "/company/MFStar", source),
                    ContentTag("美媛馆", "/company/MyGirl", source),
                    ContentTag("丝慕", "/company/SiMu", source),
                    ContentTag("ROSI", "/company/ROSI", source),
                    ContentTag("Beautyleg", "/company/Beautyleg", source),
                    ContentTag("艺图语", "/company/YITUYU", source),
                    ContentTag("物恋传媒", "/company/WLCM", source),
                    ContentTag("国模", "/company/GM", source),
                    ContentTag("三禾摄影", "/company/SHSY", source),
                    ContentTag("袜啵啵", "/company/BoBoSocks", source),
                    ContentTag("Cosdoki", "/company/Cosdoki", source),
                    ContentTag("Girlz-High", "/company/Girlz-High", source),
                    ContentTag("FLASH杂志", "/company/flash", source),
                    ContentTag("RQ-STAR", "/company/RQ-STAR", source),
                    ContentTag("喵糖映画", "/company/Micat", source),
                ),
            ),
        ),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: ContentListFilter,
    ): List<Content> {
        val hl = getHl(filter.locale)
        val url = when {
            !filter.query.isNullOrBlank() -> {
                "https://$domain/search/?q=${filter.query.urlEncoded()}&page=$page"
            }
            filter.tags.isNotEmpty() -> {
                val tag = filter.tags.first()
                "https://$domain${tag.key}?page=$page"
            }
            else -> {
                "https://$domain/?page=$page"
            }
        }.let { 
            if (hl != null) {
                if (it.contains("?")) "$it&hl=$hl" else "$it?hl=$hl"
            } else it
        }

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        // Skip .actor-cover as they don't have album links
        return doc.select(".albums-list .card:not(.actor-cover)").mapNotNull { card ->
            val titleEl = card.selectFirst("h6 a")
            val href = titleEl?.attrAsRelativeUrl("href") 
                ?: card.selectFirst("a.media-cover")?.attrAsRelativeUrl("href")
                ?: return@mapNotNull null // Skip if no link (like section headers or ads if any)
            
            val cover = card.selectFirst("img")?.let { 
                it.attrOrNull("data-src", "src")
            }?.toAbsoluteUrl(domain)

            val tags = mutableSetOf<ContentTag>()
            val metaArea = card.selectFirst(".media-meta")
            metaArea?.select("a[href*='/category/']")?.forEach { 
                tags.add(ContentTag(it.text(), it.attr("href"), source))
            }
            metaArea?.select("a[href*='/company/']")?.forEach { 
                tags.add(ContentTag("机构:${it.text()}", it.attr("href"), source))
            }
            metaArea?.select("a[href*='/actor/']")?.forEach { 
                tags.add(ContentTag("模特:${it.text()}", it.attr("href"), source))
            }

            Content(
                id = generateUid(href),
                title = titleEl?.text() ?: card.selectFirst("img")?.attr("alt") ?: "Unknown",
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = tags,
                state = null,
                authors = metaArea?.select("a[href*='/actor/']")?.mapToSet { it.text() }.orEmpty(),
                largeCoverUrl = cover,
                source = source,
            )
        }
    }

    private fun getHl(locale: Locale?): String? = when (locale?.language) {
        "zh" -> if (locale.country == "TW" || locale.country == "HK" || locale.script == "Hant") "zh-Hant" else "zh-Hans"
        "ja" -> "ja"
        "en" -> "en"
        "ko" -> "ko"
        "es" -> "es"
        "fr" -> "fr"
        "ru" -> "ru"
        "de" -> "de"
        "ar" -> "ar"
        else -> null
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        
        val tags = mutableSetOf<ContentTag>()
        // Scope to the info card in the detail page
        val infoCard = doc.selectFirst(".main-wrap .card .card-body")
        infoCard?.select("a[href*='/category/']")?.forEach { 
            tags.add(ContentTag(it.text(), it.attr("href"), source))
        }
        infoCard?.select("a[href*='/company/']")?.forEach { 
            tags.add(ContentTag("机构:${it.text()}", it.attr("href"), source))
        }
        infoCard?.select("a[href*='/actor/']")?.forEach { 
            tags.add(ContentTag("模特:${it.text()}", it.attr("href"), source))
        }

        val authors = infoCard?.select("a[href*='/actor/']")?.mapToSet { it.text() }.orEmpty()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")

        return manga.copy(
            tags = tags,
            authors = authors,
            description = desc,
            chapters = listOf(
                ContentChapter(
                    id = manga.id,
                    title = "Album",
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            )
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val firstPageUrl = chapter.url.toAbsoluteUrl(domain)
        
        val doc = try {
            val response = webClient.httpGet(firstPageUrl, getRequestHeaders())
            response.parseHtml()
        } catch (e: Exception) {
            // If we get an error, it might be Cloudflare. Trigger a browser check.
            context.requestBrowserAction(this, firstPageUrl)
            val response = webClient.httpGet(firstPageUrl, getRequestHeaders())
            response.parseHtml()
        }
        
        val allImages = mutableListOf<String>()
        
        // 1. Get images from the first page
        allImages.addAll(extractImagesFromDoc(doc))
        
        // 2. Identify total number of pages in the album
        // Look for the last page link in the pagination nav
        val paginationLinks = doc.select("nav ul.pagination li.page-item a.page-link")
        var lastPageNum = 1
        for (link in paginationLinks) {
            val t = link.text().trim()
            if (t.toIntOrNull() != null) {
                lastPageNum = maxOf(lastPageNum, t.toInt())
            } else if (t == "末页" || t == "»") {
                val href = link.attr("href")
                val num = Regex("""page=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (num != null) lastPageNum = maxOf(lastPageNum, num)
            }
        }
        
        // 3. Fetch subsequent pages
        if (lastPageNum > 1) {
            // Limited to avoid excessive requests, but usually galleries are < 100 pages
            val limit = minOf(lastPageNum, 100) 
            for (p in 2..limit) {
                val pageUrl = if (firstPageUrl.contains("?")) "$firstPageUrl&page=$p" else "$firstPageUrl?page=$p"
                try {
                    val pageDoc = webClient.httpGet(pageUrl, getRequestHeaders()).parseHtml()
                    val images = extractImagesFromDoc(pageDoc)
                    if (images.isEmpty()) break // No more images, maybe blocked?
                    allImages.addAll(images)
                } catch (e: Exception) {
                    // Stop on first error to prevent hanging or many popups
                    break
                }
            }
        }
        
        val headers = mutableMapOf("Referer" to firstPageUrl)
        return allImages.distinct().mapIndexed { index, url ->
            ContentPage(
                id = generateUid(url + index),
                url = url,
                preview = null,
                headers = headers,
                source = source
            )
        }
    }

    private fun extractImagesFromDoc(doc: org.jsoup.nodes.Document): List<String> {
        return doc.select(".photos-list img").mapNotNull { img ->
            img.attrOrNull("data-src", "src")
        }.filter { it.isNotBlank() && !it.startsWith("data:") }
        .map { it.toAbsoluteUrl(domain) }
    }
}
