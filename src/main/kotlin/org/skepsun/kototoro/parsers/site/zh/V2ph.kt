package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.*

/**
 * 微图坊 (v2ph.com)  gallery parser
 */
@MangaSourceParser(name = "V2PH", title = "微图坊", locale = "zh", type = ContentType.IMAGE_SET)
internal class V2ph(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.V2PH,
    pageSize = 20,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("www.v2ph.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

@OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)
    override suspend fun getFilterOptions() = MangaListFilterOptions(
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
            MangaTagGroup(
                "标签",
                setOf(
                    MangaTag("性感美女", "/category/sexy-girls", source),
                    MangaTag("女神", "/category/nvshen", source),
                    MangaTag("短发", "/category/short-hair", source),
                    MangaTag("清纯", "/category/pure", source),
                    MangaTag("内衣美女", "/category/underwear-beauty", source),
                    MangaTag("杂志", "/category/magazine", source),
                    MangaTag("嫩模", "/category/sexy-model", source),
                    MangaTag("美腿", "/category/beautiful-legs", source),
                    MangaTag("日本少女", "/category/japanese-girls", source),
                    MangaTag("极品", "/category/best-quality", source),
                    MangaTag("外拍", "/category/outside", source),
                    MangaTag("比基尼", "/category/bikini-girls", source),
                ),
            ),
            MangaTagGroup(
                "国家",
                setOf(
                    MangaTag("中国大陆", "/country/china", source),
                    MangaTag("日本", "/country/japan", source),
                    MangaTag("韩国", "/country/south-korea", source),
                    MangaTag("台湾", "/country/taiwan", source),
                    MangaTag("泰国", "/country/thailand", source),
                    MangaTag("欧美", "/country/europe", source),
                ),
            ),
            MangaTagGroup(
                "写真机构",
                setOf(
                    MangaTag("秀人网", "/company/XIUREN", source),
                    MangaTag("尤蜜荟", "/company/YOUMI", source),
                    MangaTag("魔范学院", "/company/MFStar", source),
                    MangaTag("美媛馆", "/company/MyGirl", source),
                    MangaTag("丝慕", "/company/SiMu", source),
                    MangaTag("ROSI", "/company/ROSI", source),
                    MangaTag("Beautyleg", "/company/Beautyleg", source),
                    MangaTag("艺图语", "/company/YITUYU", source),
                    MangaTag("物恋传媒", "/company/WLCM", source),
                    MangaTag("国模", "/company/GM", source),
                    MangaTag("三禾摄影", "/company/SHSY", source),
                    MangaTag("袜啵啵", "/company/BoBoSocks", source),
                    MangaTag("Cosdoki", "/company/Cosdoki", source),
                    MangaTag("Girlz-High", "/company/Girlz-High", source),
                    MangaTag("FLASH杂志", "/company/flash", source),
                    MangaTag("RQ-STAR", "/company/RQ-STAR", source),
                    MangaTag("喵糖映画", "/company/Micat", source),
                ),
            ),
        ),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
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

            val tags = mutableSetOf<MangaTag>()
            val metaArea = card.selectFirst(".media-meta")
            metaArea?.select("a[href*='/category/']")?.forEach { 
                tags.add(MangaTag(it.text(), it.attr("href"), source))
            }
            metaArea?.select("a[href*='/company/']")?.forEach { 
                tags.add(MangaTag("机构:${it.text()}", it.attr("href"), source))
            }
            metaArea?.select("a[href*='/actor/']")?.forEach { 
                tags.add(MangaTag("模特:${it.text()}", it.attr("href"), source))
            }

            Manga(
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

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        
        val tags = mutableSetOf<MangaTag>()
        // Scope to the info card in the detail page
        val infoCard = doc.selectFirst(".main-wrap .card .card-body")
        infoCard?.select("a[href*='/category/']")?.forEach { 
            tags.add(MangaTag(it.text(), it.attr("href"), source))
        }
        infoCard?.select("a[href*='/company/']")?.forEach { 
            tags.add(MangaTag("机构:${it.text()}", it.attr("href"), source))
        }
        infoCard?.select("a[href*='/actor/']")?.forEach { 
            tags.add(MangaTag("模特:${it.text()}", it.attr("href"), source))
        }

        val authors = infoCard?.select("a[href*='/actor/']")?.mapToSet { it.text() }.orEmpty()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")

        return manga.copy(
            tags = tags,
            authors = authors,
            description = desc,
            chapters = listOf(
                MangaChapter(
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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val firstPageUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(firstPageUrl, getRequestHeaders()).parseHtml()
        
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
            } else if (t == "末页") {
                val href = link.attr("href")
                val num = Regex("""page=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (num != null) lastPageNum = maxOf(lastPageNum, num)
            }
        }
        
        // 3. Fetch subsequent pages
        if (lastPageNum > 1) {
            // Limited to avoid excessive requests in one go, but usually galleries are < 20 pages
            val limit = minOf(lastPageNum, 50) 
            for (p in 2..limit) {
                val pageUrl = if (firstPageUrl.contains("?")) "$firstPageUrl&page=$p" else "$firstPageUrl?page=$p"
                runCatching {
                    val pageDoc = webClient.httpGet(pageUrl, getRequestHeaders()).parseHtml()
                    allImages.addAll(extractImagesFromDoc(pageDoc))
                }
            }
        }
        
        val headers = mutableMapOf("Referer" to firstPageUrl)
        return allImages.distinct().mapIndexed { index, url ->
            MangaPage(
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
