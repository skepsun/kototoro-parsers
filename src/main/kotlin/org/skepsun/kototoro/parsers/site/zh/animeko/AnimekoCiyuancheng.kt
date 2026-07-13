package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 次元城动画 (Tier 4)
 */
@ContentSourceParser(
    name = "ANIMEKO_CIYUANCHENG",
    title = "Animeko: 次元城动画",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoCiyuancheng(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_CIYUANCHENG,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("次元城动画")
    }
}
