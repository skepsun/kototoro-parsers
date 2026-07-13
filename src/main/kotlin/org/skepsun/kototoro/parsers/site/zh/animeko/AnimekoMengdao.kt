package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 萌道动漫 (Tier 4)
 * 直连，来自萌道动漫，导航：https://www.gpjda.com/
 */
@ContentSourceParser(
    name = "ANIMEKO_MENGDAO",
    title = "Animeko: 萌道动漫",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoMengdao(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_MENGDAO,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("萌道动漫")
    }
}
