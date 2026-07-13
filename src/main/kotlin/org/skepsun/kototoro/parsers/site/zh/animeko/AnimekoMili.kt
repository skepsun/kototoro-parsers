package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 米粒动漫 (Tier 4)
 * 直连，来自米粒动漫，导航：https://milimili.nl/
 */
@ContentSourceParser(
    name = "ANIMEKO_MILI",
    title = "Animeko: 米粒动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoMili(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_MILI,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("米粒动漫")
    }
}
