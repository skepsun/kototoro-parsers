package org.skepsun.kototoro.parsers.site.all

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 搜索分页沿用站点搜索结果页自身的翻页链接：`/page/N/?s=<q>`。
 * 旧的 `?s=<q>&paged=N` 不生效，第 2 页起会重复第 1 页。
 */
class XanimuTest {

    private val parser = Xanimu(ContentLoaderContextMock)

    @Test
    fun `search first page uses site search parameter`() {
        assertEquals(
            "https://xanimu.com/?s=raven",
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = "raven")),
        )
    }

    @Test
    fun `search pagination uses page path instead of paged query`() {
        assertEquals(
            "https://xanimu.com/page/2/?s=Lili+tekken",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter(query = "Lili tekken")),
        )
    }

    @Test
    fun `browse keeps plain listing paths`() {
        assertEquals(
            "https://xanimu.com/",
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://xanimu.com/page/2/",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
    }
}
