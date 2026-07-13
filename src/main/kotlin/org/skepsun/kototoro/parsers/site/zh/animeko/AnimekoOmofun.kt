package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: omofun111 (Tier 0)
 * 直连，来自omofun动漫视频网，导航：https://enlienli.link/
 */
@ContentSourceParser(
    name = "ANIMEKO_OMOFUN",
    title = "Animeko: omofun111",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoOmofun(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_OMOFUN,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("omofun111")
    }
}
