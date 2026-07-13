package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: hanime1[1080p] (Tier 4)
 * 魔法，NSFW，1080P，网址：https://hanime1.me/
 */
@ContentSourceParser(
    name = "ANIMEKO_HANIME1_1080P",
    title = "Animeko: hanime1[1080p]",
    locale = "zh",
    type = ContentType.HENTAI_VIDEO,
)
internal class AnimekoHanime1_1080p(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_HANIME1_1080P,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("hanime1[1080p]")
    }
}
