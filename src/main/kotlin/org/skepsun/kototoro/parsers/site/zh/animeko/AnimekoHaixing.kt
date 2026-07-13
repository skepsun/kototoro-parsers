package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 海星动漫 (Tier 3)
 * 直连，来自海星动漫，导航：https://www.haixingdmx.com/
 */
@ContentSourceParser(
    name = "ANIMEKO_HAIXING",
    title = "Animeko: 海星动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoHaixing(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_HAIXING,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("海星动漫")
    }
}
