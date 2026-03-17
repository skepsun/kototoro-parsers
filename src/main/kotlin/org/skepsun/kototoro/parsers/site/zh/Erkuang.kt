package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

@ContentSourceParser("ERKUANG", "二矿动漫", "zh", type = ContentType.VIDEO)
internal class Erkuang(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.ERKUANG, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("www.2rk.cc")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        // 动漫类型标签
        val genres = listOf(
            "冒险", "奇幻", "战斗", "热血", "悬疑", "血腥", "剧情", "科幻", 
            "动漫", "架空", "治愈", "校园", "恋爱", "搞笑", "运动", "推理"
        ).map { ContentTag(it, it, source) }

        return ContentListFilterOptions(
            availableTags = genres.toSet(),
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        return when {
            !filter.query.isNullOrEmpty() -> {
                // 搜索功能
                val url = "https://$domain/search?w=${filter.query.urlEncoded()}"
                parseSearchResults(webClient.httpGet(url).parseHtml())
            }
            else -> {
                // 全部视频列表
                val url = "https://$domain/all?p=$page"
                parseCategoryList(webClient.httpGet(url).parseHtml())
            }
        }
    }

    private fun parseCategoryList(doc: Document): List<Content> {
        return doc.select("article.z").mapNotNull { article ->
            val link = article.selectFirst("section.ac a[href], h2 a[href]") ?: return@mapNotNull null
            val url = link.attr("href")
            val title = article.selectFirst("h2 a span")?.text() 
                ?: article.selectFirst("img")?.attr("alt") 
                ?: return@mapNotNull null
            val cover = article.selectFirst("section.ac img")?.attr("src")?.let {
                if (it.startsWith("/")) "https://$domain$it" else it
            }

            Content(
                id = generateUid(url),
                url = url,
                publicUrl = url.toAbsoluteUrl(domain),
                title = title,
                altTitle = null,
                description = null,
                coverUrl = cover ?: "",
                largeCoverUrl = null,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                author = null,
                state = null,
                source = source,
                isNsfw = false,
            )
        }
    }

    private fun parseSearchResults(doc: Document): List<Content> {
        return parseCategoryList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        // 标题在JavaScript的pageData中
        val scriptContent = doc.html()
        val titlePattern = Regex(""""animeName":\s*"([^"]+)"""")
        val title = titlePattern.find(scriptContent)?.groupValues?.get(1) ?: manga.title
        
        val description = doc.selectFirst("section.ai article p")?.text()?.trim() ?: ""
        val cover = doc.selectFirst("section.ac img")?.attr("src")?.let {
            if (it.startsWith("/")) "https://$domain$it" else it
        } ?: manga.coverUrl

        // 解析剧集列表
        val episodes = doc.select(".af li a").mapNotNull { ep ->
            val epUrl = ep.attr("href")
            val epTitle = ep.text().trim()
            
            // 从标题中提取集数 (例如: "第01话" -> 1)
            val epNum = Regex("\\d+").find(epTitle)?.value?.toFloatOrNull() ?: 0f

            ContentChapter(
                id = generateUid(epUrl),
                title = epTitle,
                number = epNum,
                volume = 0,
                url = epUrl,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source
            )
        }

        // 解析类型标签
        val tags = doc.select("article.ag div:contains(动漫类型) a, article.aa span a[href*='/search?w=']")
            .mapNotNull { tag ->
                val tagName = tag.text().trim()
                if (tagName.isNotBlank()) {
                    ContentTag(tagName, tagName, source)
                } else null
            }.toSet()

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            tags = tags,
            chapters = episodes,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        // 从JavaScript中提取视频URL
        // 格式: h.loadSource("https://www.2rk.cc/video/{id}/{episode}/{hash}.m3u8");
        val scriptContent = doc.html()
        
        // 尝试多个可能的模式
        val patterns = listOf(
            Regex("""h\.loadSource\(["']([^"']+)["']\)"""),
            Regex("""loadSource\(["']([^"']+)["']\)"""),
            Regex("""(https://[^\s"']+\.m3u8)""")
        )
        
        var videoUrl: String? = null
        for (pattern in patterns) {
            videoUrl = pattern.find(scriptContent)?.groupValues?.get(1)
            if (!videoUrl.isNullOrBlank()) break
        }
        
        if (videoUrl.isNullOrBlank()) {
            throw Exception("Video URL not found in page")
        }

        return listOf(
            ContentPage(
                id = generateUid(videoUrl),
                url = videoUrl,
                preview = null,
                source = source
            )
        )
    }
}
