package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 新优酷 (Tier 4)
 * 直连，来自新优酷，导航：https://www.youknow.tv/
 */
@ContentSourceParser(
    name = "ANIMEKO_XINYOUKU",
    title = "Animeko: 新优酷",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoXinyouku(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_XINYOUKU,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("新优酷")
    }
}
