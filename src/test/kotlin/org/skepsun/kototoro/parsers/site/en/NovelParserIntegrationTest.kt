package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

class NovelParserIntegrationTest {

    private val context = ContentLoaderContextMock

    @Test fun testBakaTsuki() = runBlocking { test(BakaTsuki(context), "BakaTsuki") }
    @Test fun testNovelCool() = runBlocking { test(NovelCool(context), "NovelCool") }
    @Test fun testNovelFull() = runBlocking { test(NovelFull(context), "NovelFull") }
    @Test fun testNovelHi() = runBlocking { test(NovelHi(context), "NovelHi") }
    @Test fun testRoyalRoad() = runBlocking { test(RoyalRoad(context), "RoyalRoad") }

    private suspend fun test(parser: PagedContentParser, name: String) {
        try {
            val filter = ContentListFilter()
            val list = parser.getListPage(1, SortOrder.UPDATED, filter)
            val listOk = list.isNotEmpty()
            val covers = if (listOk) list.count { it.coverUrl != null && it.coverUrl!!.startsWith("http") } else 0
            val detailOk = if (listOk) {
                val d = parser.getDetails(list.first())
                d.title.isNotBlank() && d.title != "Untitled"
            } else false
            val detailCover = if (listOk) {
                val d = parser.getDetails(list.first())
                d.coverUrl?.startsWith("http") == true
            } else false
            val pagesOk = if (listOk) {
                val d = parser.getDetails(list.first())
                val ch = d.chapters ?: emptyList()
                ch.isNotEmpty() && parser.getPages(ch.first()).isNotEmpty()
            } else false
            val grade = when { listOk && detailOk && pagesOk -> "OK"; listOk && detailOk -> "WARN"; listOk -> "LIST"; else -> "DEAD" }
            println("[$grade] $name | list=${list.size} covers=$covers | detail=$detailOk detailCover=$detailCover | pages=$pagesOk")
        } catch (e: Exception) {
            println("[DEAD] $name | ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }
}
