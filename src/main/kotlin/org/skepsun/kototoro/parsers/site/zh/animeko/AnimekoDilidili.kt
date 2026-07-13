package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 嘀哩嘀哩 (Tier 4)
 * 直连，来自嘀哩嘀哩，导航：https://dilidili.io/
 */
@ContentSourceParser(
    name = "ANIMEKO_DILIDILI",
    title = "Animeko: 嘀哩嘀哩",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoDilidili(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_DILIDILI,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("嘀哩嘀哩")
    }
}
