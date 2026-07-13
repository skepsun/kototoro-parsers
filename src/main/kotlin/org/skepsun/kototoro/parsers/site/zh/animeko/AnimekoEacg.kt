package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: E-ACG (Tier 2)
 */
@ContentSourceParser(
    name = "ANIMEKO_EACG",
    title = "Animeko: E-ACG",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoEacg(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_EACG,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("E-ACG")
    }
}
