package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: girigiri愛動漫 (Tier 0)
 */
@ContentSourceParser(
    name = "ANIMEKO_GIRIGIRI",
    title = "Animeko: girigiri愛動漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoGirigiri(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_GIRIGIRI,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("girigiri愛動漫")
    }
}
