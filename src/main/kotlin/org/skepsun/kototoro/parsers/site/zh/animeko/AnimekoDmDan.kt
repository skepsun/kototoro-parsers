package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 动漫蛋 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_DM_DAN",
    title = "Animeko: 动漫蛋",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoDmDan(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_DM_DAN,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("动漫蛋")
    }
}
