package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.urlEncoded

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
    override val matchChannelName = """^(?<ch>.+?)(\d+)?$"""
    override val selectEpisodeLists = ".module-blocklist>.scroll-content"
    override val enableNestedUrl = true
    override val matchVideoUrl = """https?://[^\s"<>]+\.(?:mp4|m3u8|flv|mkv)(?:\?[^\s"<>]*)?"""
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

    override val selectFilterLists =
        ".module-list:not(.module-lines-list) .module-item-title[href], a.module-poster-item"

    override fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            val fields = MutableList(14) { "" }
            fields[0] = query.urlEncoded().replace("+", "%20")
            fields[10] = page.toString()
            return "https://$domain/vodsearch/${fields.joinToString("-")}.html"
        }
        val fields = MutableList(12) { "" }
        fields[0] = filter.tags.firstOrNull()?.key?.substringAfter("type:")
            ?.takeIf { key -> categoryTags.any { it.first == key } } ?: "dongman"
        fields[2] = sortOrderMapping.getValue(order)
        fields[8] = page.toString()
        return "https://$domain/vodshow/${fields.joinToString("-")}.html"
    }
}
