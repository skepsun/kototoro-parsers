package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 蜜桃动漫 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_MITAO",
    title = "Animeko: 蜜桃动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoMitao(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_MITAO,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("蜜桃动漫")
    }
}
