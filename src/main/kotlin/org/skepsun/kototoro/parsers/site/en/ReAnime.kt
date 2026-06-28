package org.skepsun.kototoro.parsers.site.en

import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import okhttp3.Headers

@Broken("Cloudflare protected")
@ContentSourceParser("REANIME", "Re:Anime", "en", type = ContentType.VIDEO)
internal class ReAnime(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.REANIME, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("reanime.to")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)
    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
    )
    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter) = emptyList<Content>()
    override suspend fun getDetails(manga: Content) = manga.copy(contentRating = ContentRating.SAFE)
    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        context.requestBrowserAction(this, chapter.url.toAbsoluteUrl(domain))
        return emptyList()
    }
    override fun getRequestHeaders() = Headers.Builder()
        .add("User-Agent", context.getDefaultUserAgent()).build()
}
