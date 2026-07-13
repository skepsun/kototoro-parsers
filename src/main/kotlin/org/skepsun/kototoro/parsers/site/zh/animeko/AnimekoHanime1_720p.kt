package org.skepsun.kototoro.parsers.site.zh.animeko

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Animeko web-selector source: hanime1[720p] (Tier 4)
 * 魔法，NSFW，Hanime1无1080P时可使用，网址：https://hanime1.me/
 */
@ContentSourceParser(
    name = "ANIMEKO_HANIME1_720P",
    title = "Animeko: hanime1[720p]",
    locale = "zh",
    type = ContentType.HENTAI_VIDEO,
)
internal class AnimekoHanime1_720p(
    context: ContentLoaderContext,
) : WebSelectorParser(
    context = context,
    source = ContentParserSource.ANIMEKO_HANIME1_720P,
    pageSize = 24,
) {
    override val mediaSourceConfig: AnimekoMediaSource by lazy {
        AnimekoConfigLoader.byName("hanime1[720p]")
    }
}
