package org.skepsun.kototoro.parsers.site.en

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 搜索分页沿用站点搜索结果页自身的翻页链接：`/page/N/?s=<q>`。
 * 旧的 `?s=<q>&page=N` 会被 WordPress 忽略，第 2 页起拿回同一批条目。
 */
class CosxplayTest {

    private val parser = Cosxplay(ContentLoaderContextMock)

    @Test
    fun `search first page uses site search parameter`() {
        assertEquals(
            "https://cosxplay.com/?s=asuka",
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = "asuka")),
        )
    }

    @Test
    fun `search pagination uses page path instead of page query`() {
        assertEquals(
            "https://cosxplay.com/page/2/?s=asuka",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter(query = "asuka")),
        )
        assertEquals(
            "https://cosxplay.com/page/3/?s=Lili+tekken",
            parser.buildListUrl(3, SortOrder.UPDATED, ContentListFilter(query = "Lili tekken")),
        )
    }

    @Test
    fun `browse keeps plain listing paths`() {
        assertEquals(
            "https://cosxplay.com/?filter=latest",
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://cosxplay.com/page/2/?filter=latest",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
    }

    /**
     * 站点排序条只有 Popular / Newest / Most viewed / Longest 四项，
     * 每一项都对应真实的 `?filter=` 参数（`/?filter=longest` 在存档里也有 246 条快照）。
     * SortOrder 没有 “Longest”，因此不暴露它，更不能拿 ALPHABETICAL 顶替。
     */
    @Test
    fun `sort orders map to real site filters`() {
        assertEquals(
            "https://cosxplay.com/?filter=popular",
            parser.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://cosxplay.com/?filter=most-viewed",
            parser.buildListUrl(1, SortOrder.RATING, ContentListFilter.EMPTY),
        )
        assertEquals(
            setOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.RATING),
            parser.availableSortOrders,
        )
    }

    @Test
    fun `unsupported sort order falls back to site default without a fake filter`() {
        assertEquals(
            "https://cosxplay.com/",
            parser.buildListUrl(1, SortOrder.ALPHABETICAL, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://cosxplay.com/",
            parser.buildListUrl(1, SortOrder.ADDED, ContentListFilter.EMPTY),
        )
    }
}
