package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 风铃动漫 (Tier 1)
 * 直连，来自风铃动漫，导航：https://www.aafun.cc/
 */
@ContentSourceParser(
    name = "ANIMEKO_FENGLING",
    title = "Animeko: 风铃动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoFengling(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_FENGLING,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("风铃动漫")
    }
}
