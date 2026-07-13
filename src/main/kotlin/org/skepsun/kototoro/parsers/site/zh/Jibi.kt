package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 叽哔动漫 — 直连，来自叽哔动漫，导航：https://www.jibi.cc/
 */
@ContentSourceParser(
    name = "JIBI",
    title = "叽哔动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class Jibi(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.JIBI,
    pageSize = 24,
) {
    override val searchUrlTemplate = "https://www.jibi.cc/index.php/vod/search.html?wd={keyword}"
    override val selectLists = "div.module-card-item-title > a"
    override val preferShorterName = true
    override val selectChannelNames = ".module-tab-item.tab-item span"
    override val matchChannelName = "^(?<ch>.+?)\$"
    override val selectEpisodeLists = ".module-play-list-content"
    override val matchEpisodeSortFromName = "第\\\\s*(?<ep>\\\\d+)"
    override val enableNestedUrl = true
    override val matchVideoUrl = "(^https?:\\\\/\\\\/(?:play\\\\.xluuss\\\\.com|hn\\\\.bfvvs\\\\.com|play\\\\.subokk\\\\.com).+\\\\.(mp4|m3u8|flv|mkv)(\\\\?.+)?)|(url=(?<v>https?:\\\\/\\\\/(?:play\\\\.xluuss\\\\.com|hn\\\\.bfvvs\\\\.com|play\\\\.subokk\\\\.com).+\\\\.(mp4|m3u8|flv|mkv)(\\\\?.+)?))"
    override val cookies = "quality=1080"
    override val addHeadersToVideo = mapOf("referer" to "")

    // Filters: mxproCMS type classification
    override val categoryFilterUrlTemplate = "https://www.jibi.cc/index.php/vod/show/class/{filter}/id/1.html"
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
