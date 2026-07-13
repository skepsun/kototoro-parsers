package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 2k动漫 (Tier 4)
 * 直连，来自2k动漫，导航：https://www.2kdm.org/
 */
@ContentSourceParser(
    name = "ANIMEKO_K2DM",
    title = "Animeko: 2k动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoK2dm(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_K2DM,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("2k动漫")
    }
}
