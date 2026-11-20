package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 85PO（KVS）搜索走路径式 `/search/<关键词>/`，翻页 `/search/<关键词>/<页>/`。
 * 关键词在路径段里，空格必须编码成 %20 而不是 `+`。
 */
class Po85Test {

    private val parser = Po85(ContentLoaderContextMock)

    @Test
    fun `search encodes keyword as path segment`() {
        assertEquals(
            "https://www.85po.com/search/%E3%83%88%E3%82%A4%E3%83%AC/",
            parser.buildUrl(1, SortOrder.UPDATED, ContentListFilter(query = "トイレ")),
        )
    }

    @Test
    fun `search pagination appends page path`() {
        assertEquals(
            "https://www.85po.com/search/%E3%83%88%E3%82%A4%E3%83%AC/2/",
            parser.buildUrl(2, SortOrder.UPDATED, ContentListFilter(query = "トイレ")),
        )
    }

    @Test
    fun `spaces are percent encoded inside the path`() {
        assertEquals(
            "https://www.85po.com/search/wa%20ku/",
            parser.buildUrl(1, SortOrder.UPDATED, ContentListFilter(query = "wa ku")),
        )
    }

    @Test
    fun `browse keeps plain listing paths`() {
        assertEquals(
            "https://www.85po.com/latest-updates/",
            parser.buildUrl(1, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://www.85po.com/most-popular/",
            parser.buildUrl(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
    }
}
