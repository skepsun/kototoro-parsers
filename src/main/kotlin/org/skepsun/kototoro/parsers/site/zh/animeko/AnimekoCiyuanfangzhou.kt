package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 次元方舟 (Tier 4)
 * 直连，来自次元方舟，导航：https://www.cyfz.vip/
 */
@ContentSourceParser(
    name = "ANIMEKO_CIYUANFANGZHOU",
    title = "Animeko: 次元方舟",
    locale = "zh",
    type = ContentType.VIDEO,
)
internal class AnimekoCiyuanfangzhou(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_CIYUANFANGZHOU,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("次元方舟")
    }
}
