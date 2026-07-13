package org.skepsun.kototoro.parsers.site.zh.animeko

import org.json.JSONArray
import org.json.JSONObject

/**
 * Top-level wrapper for the animeko exported media source JSON.
 */
internal data class AnimekoExportedSources(
    val mediaSources: List<AnimekoMediaSource>,
)

/**
 * A single media source configuration from the animeko JSON.
 */
internal data class AnimekoMediaSource(
    val factoryId: String,
    val version: Int,
    val name: String,
    val description: String,
    val iconUrl: String,
    val tier: Int,
    val searchConfig: AnimekoSearchConfig,
)

/**
 * Search configuration for a web-selector source.
 */
internal data class AnimekoSearchConfig(
    val searchUrl: String,
    val searchUseOnlyFirstWord: Boolean = false,
    val searchRemoveSpecial: Boolean = false,
    val searchUseSubjectNamesCount: Int = 1,
    val rawBaseUrl: String = "",
    val requestInterval: Int = 0,
    val subjectFormatId: String = "a", // "a" or "indexed"
    val selectorSubjectFormatA: AnimekoSelectorA? = null,
    val selectorSubjectFormatIndexed: AnimekoSelectorIndexed? = null,
    val selectorSubjectFormatJsonPathIndexed: AnimekoSelectorJsonPathIndexed? = null,
    val channelFormatId: String = "index-grouped", // "index-grouped" or "no-channel"
    val selectorChannelFormatFlattened: AnimekoSelectorChannelFlattened? = null,
    val selectorChannelFormatNoChannel: AnimekoSelectorChannelNoChannel? = null,
    val defaultResolution: String = "1080P",
    val defaultSubtitleLanguage: String = "CHS",
    val onlySupportsPlayers: List<String> = emptyList(),
    val filterByEpisodeSort: Boolean = false,
    val filterBySubjectName: Boolean = false,
    val selectMedia: AnimekoSelectMedia = AnimekoSelectMedia(),
    val matchVideo: AnimekoMatchVideo = AnimekoMatchVideo(),
)

/**
 * Subject format "a" selector: selectLists + preferShorterName.
 */
internal data class AnimekoSelectorA(
    val selectLists: String,
    val preferShorterName: Boolean = false,
)

/**
 * Subject format "indexed" selector: separate selectNames and selectLinks.
 */
internal data class AnimekoSelectorIndexed(
    val selectNames: String,
    val selectLinks: String,
    val preferShorterName: Boolean = false,
)

/**
 * JSON path indexed selector for API-based sources.
 */
internal data class AnimekoSelectorJsonPathIndexed(
    val selectLinks: String,
    val selectNames: String,
    val preferShorterName: Boolean = false,
)

/**
 * Channel format "index-grouped": channels have tabs, each with episode lists.
 */
internal data class AnimekoSelectorChannelFlattened(
    val selectChannelNames: String,
    val matchChannelName: String = "",
    val selectEpisodeLists: String,
    val selectEpisodesFromList: String,
    val selectEpisodeLinksFromList: String = "",
    val matchEpisodeSortFromName: String = "第\\s*(?<ep>.+)\\s*[话集]",
)

/**
 * Channel format "no-channel": flat episode list, no channel tabs.
 */
internal data class AnimekoSelectorChannelNoChannel(
    val selectEpisodes: String,
    val selectEpisodeLinks: String = "",
    val matchEpisodeSortFromName: String = "第\\s*(?<ep>.+)\\s*[话集]",
)

/**
 * Media selection settings.
 */
internal data class AnimekoSelectMedia(
    val distinguishSubjectName: Boolean = true,
    val distinguishChannelName: Boolean = true,
)

/**
 * Video URL matching configuration.
 */
internal data class AnimekoMatchVideo(
    val enableNestedUrl: Boolean = false,
    val matchNestedUrl: String = "\$^",
    val matchVideoUrl: String = "",
    val cookies: String = "",
    val addHeadersToVideo: Map<String, String> = emptyMap(),
)

// ============================================================================
// JSON Parsing
// ============================================================================

internal fun JSONObject.toAnimekoMediaSource(): AnimekoMediaSource {
    val args = getJSONObject("arguments")
    return AnimekoMediaSource(
        factoryId = getString("factoryId"),
        version = getInt("version"),
        name = args.getString("name"),
        description = args.optString("description", ""),
        iconUrl = args.optString("iconUrl", ""),
        tier = args.optInt("tier", 4),
        searchConfig = args.getJSONObject("searchConfig").toAnimekoSearchConfig(),
    )
}

internal fun JSONObject.toAnimekoSearchConfig(): AnimekoSearchConfig {
    return AnimekoSearchConfig(
        searchUrl = getString("searchUrl"),
        searchUseOnlyFirstWord = optBoolean("searchUseOnlyFirstWord", false),
        searchRemoveSpecial = optBoolean("searchRemoveSpecial", false),
        searchUseSubjectNamesCount = optInt("searchUseSubjectNamesCount", 1),
        rawBaseUrl = optString("rawBaseUrl", ""),
        requestInterval = optInt("requestInterval", 0),
        subjectFormatId = optString("subjectFormatId", "a"),
        selectorSubjectFormatA = optJSONObject("selectorSubjectFormatA")?.toAnimekoSelectorA(),
        selectorSubjectFormatIndexed = optJSONObject("selectorSubjectFormatIndexed")?.toAnimekoSelectorIndexed(),
        selectorSubjectFormatJsonPathIndexed = optJSONObject("selectorSubjectFormatJsonPathIndexed")?.toAnimekoSelectorJsonPathIndexed(),
        channelFormatId = optString("channelFormatId", "index-grouped"),
        selectorChannelFormatFlattened = optJSONObject("selectorChannelFormatFlattened")?.toAnimekoSelectorChannelFlattened(),
        selectorChannelFormatNoChannel = optJSONObject("selectorChannelFormatNoChannel")?.toAnimekoSelectorChannelNoChannel(),
        defaultResolution = optString("defaultResolution", "1080P"),
        defaultSubtitleLanguage = optString("defaultSubtitleLanguage", "CHS"),
        onlySupportsPlayers = optJSONArray("onlySupportsPlayers")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList(),
        filterByEpisodeSort = optBoolean("filterByEpisodeSort", false),
        filterBySubjectName = optBoolean("filterBySubjectName", false),
        selectMedia = optJSONObject("selectMedia")?.toAnimekoSelectMedia() ?: AnimekoSelectMedia(),
        matchVideo = optJSONObject("matchVideo")?.toAnimekoMatchVideo() ?: AnimekoMatchVideo(),
    )
}

internal fun JSONObject.toAnimekoSelectorA(): AnimekoSelectorA {
    return AnimekoSelectorA(
        selectLists = getString("selectLists"),
        preferShorterName = optBoolean("preferShorterName", false),
    )
}

internal fun JSONObject.toAnimekoSelectorIndexed(): AnimekoSelectorIndexed {
    return AnimekoSelectorIndexed(
        selectNames = getString("selectNames"),
        selectLinks = getString("selectLinks"),
        preferShorterName = optBoolean("preferShorterName", false),
    )
}

internal fun JSONObject.toAnimekoSelectorJsonPathIndexed(): AnimekoSelectorJsonPathIndexed {
    return AnimekoSelectorJsonPathIndexed(
        selectLinks = getString("selectLinks"),
        selectNames = getString("selectNames"),
        preferShorterName = optBoolean("preferShorterName", false),
    )
}

internal fun JSONObject.toAnimekoSelectorChannelFlattened(): AnimekoSelectorChannelFlattened {
    return AnimekoSelectorChannelFlattened(
        selectChannelNames = getString("selectChannelNames"),
        matchChannelName = optString("matchChannelName", ""),
        selectEpisodeLists = getString("selectEpisodeLists"),
        selectEpisodesFromList = getString("selectEpisodesFromList"),
        selectEpisodeLinksFromList = optString("selectEpisodeLinksFromList", ""),
        matchEpisodeSortFromName = optString("matchEpisodeSortFromName", "第\\s*(?<ep>.+)\\s*[话集]"),
    )
}

internal fun JSONObject.toAnimekoSelectorChannelNoChannel(): AnimekoSelectorChannelNoChannel {
    return AnimekoSelectorChannelNoChannel(
        selectEpisodes = getString("selectEpisodes"),
        selectEpisodeLinks = optString("selectEpisodeLinks", ""),
        matchEpisodeSortFromName = optString("matchEpisodeSortFromName", "第\\s*(?<ep>.+)\\s*[话集]"),
    )
}

internal fun JSONObject.toAnimekoSelectMedia(): AnimekoSelectMedia {
    return AnimekoSelectMedia(
        distinguishSubjectName = optBoolean("distinguishSubjectName", true),
        distinguishChannelName = optBoolean("distinguishChannelName", true),
    )
}

internal fun JSONObject.toAnimekoMatchVideo(): AnimekoMatchVideo {
    val headers = mutableMapOf<String, String>()
    optJSONObject("addHeadersToVideo")?.let { obj ->
        for (key in obj.keys()) {
            headers[key] = obj.optString(key, "")
        }
    }
    return AnimekoMatchVideo(
        enableNestedUrl = optBoolean("enableNestedUrl", false),
        matchNestedUrl = optString("matchNestedUrl", "\$^"),
        matchVideoUrl = optString("matchVideoUrl", ""),
        cookies = optString("cookies", ""),
        addHeadersToVideo = headers,
    )
}