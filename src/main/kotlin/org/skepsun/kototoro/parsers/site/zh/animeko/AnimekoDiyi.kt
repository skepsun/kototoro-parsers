package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: 第一动漫 (Tier 4)
 * 直连，NSFW，第一动漫，导航：https://1anime.org/
 */
@ContentSourceParser(
    name = "ANIMEKO_DIYI",
    title = "Animeko: 第一动漫",
    locale = "zh",
    type = ContentType.HENTAI_VIDEO,
)
internal class AnimekoDiyi(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_DIYI,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("第一动漫")
    }
}
