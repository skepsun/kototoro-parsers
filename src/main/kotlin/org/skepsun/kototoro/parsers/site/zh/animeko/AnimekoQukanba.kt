package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 去看吧 (Tier 3)
 * 直连，来自去看吧，导航：https://11kt.net/
 */
@ContentSourceParser(
    name = "ANIMEKO_QUKANBA",
    title = "Animeko: 去看吧",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoQukanba(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_QUKANBA,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("去看吧")
    }
}
