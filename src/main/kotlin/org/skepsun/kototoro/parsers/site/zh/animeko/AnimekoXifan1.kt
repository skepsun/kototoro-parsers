package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 稀饭动漫 (Tier 2)
 */
@ContentSourceParser(
    name = "ANIMEKO_XIFAN1",
    title = "Animeko: 稀饭动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoXifan1(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_XIFAN1,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("稀饭动漫")
    }
}
