package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 番茄动漫 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_FANQIE",
    title = "Animeko: 番茄动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoFanqie(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_FANQIE,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("番茄动漫")
    }
}
