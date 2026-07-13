package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 漫次元 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_MANCIYUAN",
    title = "Animeko: 漫次元",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoManciyuan(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_MANCIYUAN,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("漫次元")
    }
}
