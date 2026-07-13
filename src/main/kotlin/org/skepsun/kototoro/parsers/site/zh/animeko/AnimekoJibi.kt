package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 叽哔动漫 (Tier 2)
 * 直连，来自叽哔动漫，导航：https://www.jibi.cc/
 */
@ContentSourceParser(
    name = "ANIMEKO_JIBI",
    title = "Animeko: 叽哔动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoJibi(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_JIBI,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("叽哔动漫")
    }
}
