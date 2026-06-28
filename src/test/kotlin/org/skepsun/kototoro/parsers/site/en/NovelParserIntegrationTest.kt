package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

class NovelParserIntegrationTest {

    private val context = ContentLoaderContextMock

    data class TestResult(
        val name: String, var listOk: Boolean = false, var listCount: Int = 0,
        var listHasCovers: Int = 0, var paginationOk: Boolean = false,
        var detailsOk: Boolean = false, var detailHasCover: Boolean = false,
        var detailHasDesc: Boolean = false, var chapterCount: Int = 0,
        var pagesOk: Boolean = false, var pageCount: Int = 0,
        val errors: MutableList<String> = mutableListOf()
    ) {
        val grade get() = when {
            listOk && detailsOk && pagesOk -> "OK"
            listOk && detailsOk -> "WARN"
            listOk -> "LIST"
            else -> "DEAD"
        }
    }

    @Test fun testLightNovelWorld() = runBlocking { test(LightNovelWorld(context), "LightNovelWorld") }
    @Test fun testLNMTL() = runBlocking { test(LNMTL(context), "LNMTL") }
    @Test fun testNovelBuddy() = runBlocking { test(NovelBuddy(context), "NovelBuddy") }
    @Test fun testNovelFire() = runBlocking { test(NovelFire(context), "NovelFire") }
    @Test fun testNovelFull() = runBlocking { test(NovelFull(context), "NovelFull") }
    @Test fun testRoyalRoad() = runBlocking { test(RoyalRoad(context), "RoyalRoad") }
    @Test fun testNovelCool() = runBlocking { test(NovelCool(context), "NovelCool") }
    @Test fun testBakaTsuki() = runBlocking { test(BakaTsuki(context), "BakaTsuki") }
    @Test fun testNovelDex() = runBlocking { test(NovelDex(context), "NovelDex") }
    @Test fun testNovelArchive() = runBlocking { test(NovelArchive(context), "NovelArchive") }
    @Test fun testWuxiaBox() = runBlocking { test(WuxiaBox(context), "WuxiaBox") }
    @Test fun testWuxiaClick() = runBlocking { test(WuxiaClick(context), "WuxiaClick") }
    @Test fun testWuxiaDreams() = runBlocking { test(WuxiaDreams(context), "WuxiaDreams") }
    @Test fun testWuxiaWorldSite() = runBlocking { test(WuxiaWorldSite(context), "WuxiaWorldSite") }
    @Test fun testRanovel() = runBlocking { test(Ranovel(context), "Ranovel") }
    @Test fun testRequiemTLS() = runBlocking { test(RequiemTLS(context), "RequiemTLS") }
    @Test fun testNovelHi() = runBlocking { test(NovelHi(context), "NovelHi") }
    @Test fun testFuckNovelpia() = runBlocking { test(FuckNovelpia(context), "FuckNovelpia") }
    @Test fun testArmaellLibrary() = runBlocking { test(ArmaellLibrary(context), "ArmaellLibrary") }
    @Test fun testLnori() = runBlocking { test(Lnori(context), "Lnori") }
    @Test fun testCyrisia() = runBlocking { test(Cyrisia(context), "Cyrisia") }

    private suspend fun test(parser: PagedContentParser, name: String) {
        val r = TestResult(name)
        try {
            val filter = ContentListFilter()
            val list = parser.getListPage(1, SortOrder.UPDATED, filter)
            r.listCount = list.size
            r.listOk = list.isNotEmpty()
            if (list.isNotEmpty()) {
                r.listHasCovers = list.count { it.coverUrl != null && it.coverUrl!!.startsWith("http") }
                val badTitles = list.count { it.title.isBlank() || it.title == "Untitled" }
                if (badTitles > 0) r.errors += "$badTitles bad titles"

                val list2 = parser.getListPage(2, SortOrder.UPDATED, filter)
                r.paginationOk = list2.isNotEmpty() && list.map { it.publicUrl }.toSet() != list2.map { it.publicUrl }.toSet()

                val detail = parser.getDetails(list.first())
                r.detailsOk = detail.title.isNotBlank() && detail.title != "Untitled"
                r.detailHasCover = detail.coverUrl?.startsWith("http") == true
                r.detailHasDesc = !detail.description.isNullOrBlank()

                val ch = detail.chapters ?: emptyList()
                r.chapterCount = ch.size
                if (ch.isNotEmpty()) {
                    r.pagesOk = parser.getPages(ch.first()).isNotEmpty()
                }
            }
        } catch (e: Exception) {
            r.errors += "${e.javaClass.simpleName}: ${e.message?.take(60)}"
        }
        println("[${r.grade}] $name | list=${r.listCount} cover=${r.listHasCovers} p2=${r.paginationOk} | detail=${r.detailsOk} dsc=${r.detailHasDesc} img=${r.detailHasCover} | ch=${r.chapterCount} pg=${r.pagesOk} ${if (r.errors.isNotEmpty()) "| " + r.errors.joinToString(";") else ""}")
    }
}
