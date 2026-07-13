package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * UZVOD — 直连，来自UZVOD优质影院，导航：https://uzvod.com/
 */
@ContentSourceParser(
    name = "UZVOD",
    title = "UZVOD",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class Uzvod(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.UZVOD,
    pageSize = 24,
) {
    override val searchUrlTemplate = "https://uzvod.com/vodsearch/-------------.html?wd={keyword}"
    override val selectLists = ".video-info-header>h3>a"
    override val preferShorterName = true
    override val selectChannelNames = ".tab-item"
    override val matchChannelName = "^(?<ch>.+?)(\\\\d+)?\$"
    override val selectEpisodeLists = ".module-blocklist>.scroll-content"
    override val enableNestedUrl = true
    override val matchVideoUrl = "(^http(s)?:\\\\/\\\\/(?!.*http).+\\\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\\\/\\\\/.+\\\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\\\/\\\\/(?!.*http).+(sign\\\\.bytetos|sign\\\\.byteimg|mcloud\\\\.139|cloudflarestorage|tos-cn)\\\\.com(?!.*\\\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\\\.com(?!.*\\\\.ts))|(\\\\/video\\\\/tos\\\\/alisg\\\\/)|(\\\\/video\\\\/.*mime_type=video)"
    override val cookies = "quality=1080"
    override val addHeadersToVideo = mapOf("referer" to "")

    // Filters: UZVOD uses /vodtype/{slug}.html
    override val categoryFilterUrlTemplate = "https://uzvod.com/vodtype/{filter}.html"
    override val categoryTags = listOf(
        "rihandongman" to "日韩动漫",
        "guochandongman" to "国产动漫",
        "oumeidongman" to "欧美动漫",
        "haiwaidongman" to "海外动漫",
    )
    override val sortOrderMapping = mapOf(
        SortOrder.UPDATED to "time",
        SortOrder.POPULARITY to "hits",
    )
}