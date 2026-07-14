package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder

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
    override val selectLists = "body > main > div > div.mt-2-5 > div > div > div > a"
    override val selectNames = ".search-box .thumb-content > .thumb-txt"
    override val selectLinks = ".search-box .thumb-menu > a"
    override val preferShorterName = true
    override val selectChannelNames = "body > main > div > div.row.mt-1-25.mb-5 > div > div > div > ul > li > button"
    override val matchChannelName = "^(?<ch>.+?)(\\\\d+)?\$"
    override val selectEpisodeLists = ".anime-episode"
    override val selectEpisodes = "#线路一 > a"
    override val enableNestedUrl = true
    override val matchNestedUrl = "^.+(m3u8|vip|xigua\\\\.php).+\\\\?"
    override val matchVideoUrl = "(^http(s)?:\\\\/\\\\/(?!.*http(s)?:\\\\/\\\\/)(?!.*google-analytics).+((\\\\.mp4)|(\\\\.mkv)|(m3u8)).*(\\\\?.+)?)|(akamaized)|(bilivideo.com)|(.+player/\\\\?url=(?<v>.+))"
    override val cookies = "quality=1080"
    override val addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
    override val searchRemoveSpecial = true
    override val requestInterval = 3000
    override val filterByEpisodeSort = true
    override val filterBySubjectName = true

    // 饭团动漫没有分类浏览页，仅保留搜索模式

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
        SortOrder.NEWEST to "recent",
    )

    override val selectFilterLists = "a[href^='/anime/']"
}