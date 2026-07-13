package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * 森之屋动漫 — 直连，来自森之屋动漫，导航：https://senfun.in/
 */
@ContentSourceParser(
    name = "SENZHIWU",
    title = "森之屋动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class Senzhiwu(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.SENZHIWU,
    pageSize = 24,
) {
    override val searchUrlTemplate = "https://senfun.in/search.html?wd={keyword}"
    override val selectLists = "div.module-card-item-title > a"
    override val preferShorterName = true
    override val selectChannelNames = "div.module-tab-item.tab-item span"
    override val selectEpisodeLists = "#panel1"
    override val selectEpisodesFromList = "a.module-play-list-link"
    override val enableNestedUrl = true
    override val matchVideoUrl = "(^http(s)?:\\\\/\\\\/(?!.*http).+\\\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\\\/\\\\/.+\\\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\\\/\\\\/(?!.*http).+(sign\\\\.bytetos|sign\\\\.byteimg|mcloud\\\\.139|cloudflarestorage|tos-cn)\\\\.com(?!.*\\\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\\\.com(?!.*\\\\.ts))|(\\\\/video\\\\/tos\\\\/alisg\\\\/)|(\\\\/video\\\\/.*mime_type=video)"
    override val cookies = "quality=1080"
    override val addHeadersToVideo = mapOf("referer" to "")
}
