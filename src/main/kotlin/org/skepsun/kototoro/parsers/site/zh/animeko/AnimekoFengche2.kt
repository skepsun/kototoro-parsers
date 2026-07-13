package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 风车动漫 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_FENGCHE2",
    title = "Animeko: 风车动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoFengche2(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_FENGCHE2,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("风车动漫")
    }
}
