package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 咕咕番 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_GUGUFAN",
    title = "Animeko: 咕咕番",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoGugufan(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_GUGUFAN,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("咕咕番")
    }
}
