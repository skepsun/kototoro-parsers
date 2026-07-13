package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: UZVOD (Tier 4)
 * 直连，来自UZVOD优质影院，导航：https://uzvod.com/
 */
@ContentSourceParser(
    name = "ANIMEKO_UZVOD",
    title = "Animeko: UZVOD",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoUzvod(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_UZVOD,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("UZVOD")
    }
}
