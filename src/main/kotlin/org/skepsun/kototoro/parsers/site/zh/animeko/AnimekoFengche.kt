package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 风车影视 (Tier 3)
 * 直连，来自风车影视，导航：https://www.dongmandaquan.vip/
 */
@ContentSourceParser(
    name = "ANIMEKO_FENGCHE",
    title = "Animeko: 风车影视",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoFengche(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_FENGCHE,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("风车影视")
    }
}
