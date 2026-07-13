package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 樱花动漫 (Tier 4)
 * 直连，来自樱花动漫，导航：https://www.yhdm6go.top/
 */
@ContentSourceParser(
    name = "ANIMEKO_YINGHUA",
    title = "Animeko: 樱花动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoYinghua(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_YINGHUA,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("樱花动漫")
    }
}
