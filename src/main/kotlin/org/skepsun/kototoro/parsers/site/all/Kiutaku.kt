package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import okhttp3.Response
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.util.*

@MangaSourceParser("KIUTAKU", "Kiutaku", type = ContentType.HENTAI_MANGA)
internal class Kiutaku(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.KIUTAKU, 24) {

    override val configKeyDomain = ConfigKey.Domain("kiutaku.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false
        )

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val categories = listOf(
            MangaTag("Cosplay 🎭", "272", source),
            MangaTag("Genshin Impact ☄️", "516", source),
            MangaTag("Honkai: Star Rail ✨", "1305", source),
            MangaTag("Azur Lane ⚓", "165", source),
            MangaTag("NIKKE 🔫", "899", source),
            MangaTag("Wuthering Waves 🌊", "1723", source),
            MangaTag("Chainsaw Man 🪚", "648", source),
            MangaTag("2B ⚔️", "18", source),
            MangaTag("Nier Automata 🤖", "19", source),
            MangaTag("VTuber 🎙️", "1162", source),
        )

        return MangaListFilterOptions(
            availableTags = categories.toSet(),
        )
    }

    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host.endsWith("mitaku.net")) {
            val newRequest = request.newBuilder()
                .header("Referer", "https://mitaku.net/")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val tag = filter.tags.firstOrNull()
        val baseUrl = buildString {
            append("https://")
            append(domain)
            if (tag != null) {
                append("/tag/").append(tag.key)
            } else if (order == SortOrder.POPULARITY) {
                append("/hot")
            } else {
                append("/")
            }
        }

        val url = buildString {
            append(baseUrl)
            val params = mutableListOf<String>()
            if (!filter.query.isNullOrBlank()) {
                params.add("search=${filter.query.urlEncoded()}")
            }
            if (page > 1) {
                params.add("start=${(page - 1) * pageSize}")
            }
            if (params.isNotEmpty()) {
                append(if (baseUrl.contains("?")) "&" else "?")
                append(params.joinToString("&"))
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div.items-row").map { div ->
            val a = div.selectFirst("a.item-link") ?: div.parseFailed("Cannot find link")
            val href = a.attrAsRelativeUrl("href")
            val img = a.selectFirst("img")

            Manga(
                id = generateUid(href),
                title = img?.attr("alt")?.trim() ?: div.selectFirst("h2")?.text()?.trim() ?: "Unknown",
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = img?.absUrl("src").orEmpty(),
                tags = div.select(".item-tags .tag").mapToSet { tagA ->
                    MangaTag(
                        key = tagA.attr("href").substringAfterLast("/tag/"),
                        title = tagA.text().trim(),
                        source = source
                    )
                },
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val article = doc.selectFirst("div.article.content") ?: doc.parseFailed("Cannot find article content")

        val tags = article.select(".article-tags .tag").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast("/tag/"),
                title = a.text().removePrefix("#").trim(),
                source = source
            )
        }

        return manga.copy(
            tags = tags,
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                )
            )
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val firstPageUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(firstPageUrl).parseHtml()
        
        val pages = mutableListOf<MangaPage>()
        
        // Find all detail pages for this gallery
        val paginationLinks = doc.select(".pagination .pagination-link").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isNotEmpty()) href.toAbsoluteUrl(domain) else null
        }.distinct()

        val allPageUrls = if (paginationLinks.isEmpty()) {
            listOf(firstPageUrl)
        } else {
            // Include first page and all other pages found in pagination
            (listOf(firstPageUrl) + paginationLinks).distinct()
        }

        for (pageUrl in allPageUrls) {
            val pageDoc = if (pageUrl == firstPageUrl) doc else webClient.httpGet(pageUrl).parseHtml()
            val images = pageDoc.select(".article-fulltext img")
            images.forEach { img ->
                val src = img.absUrl("src")
                if (src.isNotEmpty()) {
                    pages.add(
                        MangaPage(
                            id = generateUid(src),
                            url = src,
                            preview = null,
                            source = source
                        )
                    )
                }
            }
        }
        
        return pages
    }
}
