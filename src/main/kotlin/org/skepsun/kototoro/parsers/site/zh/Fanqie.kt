package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 番茄动漫
 */
@ContentSourceParser(
    name = "FANQIE",
    title = "番茄动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class Fanqie(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.FANQIE,
    pageSize = 24,
) {
    override val searchUrlTemplate = "https://www.fqdm.cc/index.php/vod/search.html?wd={keyword}"
    override val selectLists = "div.module-card-item-info > div.module-card-item-title > a"
    override val selectNames = ".search-box .thumb-content > .thumb-txt"
    override val selectLinks = ".search-box .thumb-menu > a"
    override val preferShorterName = true
    override val selectChannelNames = "#y-playList > div > span"
    override val selectEpisodeLists = "#panel1"
    override val selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a"
    override val enableNestedUrl = true
    override val matchNestedUrl = "^.+(m3u8|vip|xigua\\\\.php).+\\\\?"
    override val matchVideoUrl = "(^https?:\\\\/\\\\/(?:play\\\\.xluuss\\\\.com|hn\\\\.bfvvs\\\\.com|play\\\\.subokk\\\\.com).+((\\\\.mp4)|(\\\\.mkv)|(\\\\.m3u8\$)).*(\\\\?.+)?)|(.+top/\\\\?url=(?<v>https?:\\\\/\\\\/(?:play\\\\.xluuss\\\\.com|hn\\\\.bfvvs\\\\.com|play\\\\.subokk\\\\.com).+))"
    override val cookies = "quality=1080"
    override val addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
    override val searchRemoveSpecial = true
    override val requestInterval = 3000
    override val filterByEpisodeSort = true
    override val filterBySubjectName = true

    // Filters: mxproCMS type classification
    override val categoryFilterUrlTemplate = "https://www.fqdm.cc/index.php/vod/show/class/{filter}/id/1.html"
    override val categoryTags = listOf(
        "日韩动漫" to "日韩动漫",
        "国产动漫" to "国产动漫",
        "港台动漫" to "港台动漫",
        "欧美动漫" to "欧美动漫",
        "动漫综合" to "动漫综合",
    )
    override val categoryTagParam = "class"
    override val sortOrderMapping = mapOf(
        SortOrder.POPULARITY to "hits",
        SortOrder.UPDATED to "time",
        SortOrder.RATING to "score",
    )
}
