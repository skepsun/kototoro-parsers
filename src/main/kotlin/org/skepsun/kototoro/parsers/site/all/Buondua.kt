package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import java.util.*

@ContentSourceParser("BUONDUA", "Buondua", type = ContentType.HENTAI_MANGA)
internal class Buondua(context: ContentLoaderContext) : PagedContentParser(context, ContentParserSource.BUONDUA, 20) {

    override val configKeyDomain = ConfigKey.Domain("buondua.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
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

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val categories = listOf(
            ContentTag("Japan 🇯🇵", "jp-11853", source),
            ContentTag("Cosplay 🎭", "cosplay-10688", source),
            ContentTag("XiuRen ✨", "xiuren-7417", source),
            ContentTag("Korean 🇰🇷", "korean-realgraphic-11066", source),
            ContentTag("OtherXXX 🔞", "otherxxx-13913", source),
            ContentTag("Pure Media 📸", "pure-media-10876", source),
            ContentTag("JVID 🎥", "jvid-11832", source),
            ContentTag("Bimilstory 📔", "bimilstory-11116", source),
            ContentTag("MFStar ⭐", "mfstar-7419", source),
            ContentTag("YouMei 🌸", "youmei-10700", source),
        )

        return ContentListFilterOptions(
            availableTags = categories.toSet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
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

            Content(
                id = generateUid(href),
                title = img?.attr("alt")?.trim() ?: div.selectFirst("h2")?.text()?.trim() ?: "Unknown",
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = img?.absUrl("src").orEmpty(),
                tags = div.select(".item-tags .tag").mapToSet { tagA ->
                    ContentTag(
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

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val article = doc.selectFirst("div.article.content") ?: doc.parseFailed("Cannot find article content")

        val tags = article.select(".article-tags .tag").mapToSet { a ->
            ContentTag(
                key = a.attr("href").substringAfterLast("/tag/"),
                title = a.text().trim(),
                source = source
            )
        }

        return manga.copy(
            tags = tags,
            chapters = listOf(
                ContentChapter(
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

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val firstPageUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(firstPageUrl).parseHtml()
        
        val pages = mutableListOf<ContentPage>()
        
        // Find all detail pages for this gallery
        val paginationLinks = doc.select(".pagination .pagination-link").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isNotEmpty()) href.toAbsoluteUrl(domain) else null
        }.distinct()

        val allPageUrls = if (paginationLinks.isEmpty()) {
            listOf(firstPageUrl)
        } else {
            (listOf(firstPageUrl) + paginationLinks).distinct()
        }

        for (pageUrl in allPageUrls) {
            val pageDoc = if (pageUrl == firstPageUrl) doc else webClient.httpGet(pageUrl).parseHtml()
            val images = pageDoc.select(".article-fulltext img")
            images.forEach { img ->
                val src = img.absUrl("src")
                if (src.isNotEmpty()) {
                    pages.add(
                        ContentPage(
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
