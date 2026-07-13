package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 森之屋动漫 (Tier 3)
 * 直连，来自森之屋动漫，导航：https://senfun.in/
 */
@ContentSourceParser(
    name = "ANIMEKO_SENZHIWU",
    title = "Animeko: 森之屋动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoSenzhiwu(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_SENZHIWU,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("森之屋动漫")
    }
}
