package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.urlEncoded

/**
 * 饭团动漫
 */
@ContentSourceParser(
    name = "FANTUAN",
    title = "饭团动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class Fantuan(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.FANTUAN,
    pageSize = 24,
) {
    override val searchUrlTemplate = "https://acgfta.com/search.html?wd={keyword}"
    override val selectLists = ".anime-card a[href^=/anime/]"
    override val selectDetailCover = "main img.anime-cover"
    override val selectNames = ".search-box .thumb-content > .thumb-txt"
    override val selectLinks = ".search-box .thumb-menu > a"
    override val preferShorterName = true
    override val selectChannelNames = ".detail-anime-play button[data-bs-toggle=pill]"
    override val matchChannelName = """^(?<ch>.+?)(\d+)?$"""
    override val selectEpisodeLists = ".anime-episode"
    override val selectEpisodes = "#线路一 > a"
    override val enableNestedUrl = true
    override val matchNestedUrl = """^https?://.+(?:m3u8|vip|xigua\.php).+\?"""
    override val matchVideoUrl = """https?://[^\s"<>]+\.(?:mp4|m3u8|flv|mkv)(?:\?[^\s"<>]*)?"""
    override val cookies = "quality=1080"
    override val addHeadersToVideo = mapOf(
        "referer" to "",
        "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3",
    )
    override val searchRemoveSpecial = true
    override val requestInterval = 3000
    override val filterByEpisodeSort = true
    override val filterBySubjectName = true

    // Filters: 饭团动漫使用 /ft/ 路径浏览
    override val categoryFilterUrlTemplate = "https://acgfta.com/ft/{filter}.html"
    override val categoryTags = listOf(
        "recent" to "最近更新",
        "leaderboard" to "日榜",
        "top-movie" to "剧场版",
    )
    override val sortOrderMapping = mapOf(
        SortOrder.UPDATED to "recent",
        SortOrder.POPULARITY to "leaderboard",
    )

    override val selectFilterLists = selectLists

    override fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return "https://$domain/search/page/$page/wd/${query.urlEncoded().replace("+", "%20")}.html"
        }
        val category = filter.tags.firstOrNull()?.key?.substringAfter("type:")
            ?.takeIf { key -> categoryTags.any { it.first == key } }
            ?: sortOrderMapping.getValue(order)
        return "https://$domain/ft/$category/file/$category/page/$page.html"
    }
}
