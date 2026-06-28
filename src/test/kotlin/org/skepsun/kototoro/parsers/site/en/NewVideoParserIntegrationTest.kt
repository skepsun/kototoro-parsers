package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

class NewVideoParserIntegrationTest {

    private val context = ContentLoaderContextMock
    private val results = mutableListOf<TestResult>()

    data class TestResult(
        val name: String,
        var listOk: Boolean = false, var listCount: Int = 0, var listHasCovers: Int = 0,
        var paginationOk: Boolean = false, var page2Count: Int = 0,
        var detailsOk: Boolean = false, var detailHasDesc: Boolean = false, var detailHasCover: Boolean = false,
        var chapterCount: Int = 0, var realChapters: Boolean = false,
        var pagesOk: Boolean = false, var pageCount: Int = 0,
        val errors: MutableList<String> = mutableListOf()
    ) {
        val score get() = (if (listOk) 2 else 0) + (if (listHasCovers > listCount / 2) 1 else 0) +
            (if (paginationOk) 1 else 0) + (if (detailsOk) 2 else 0) +
            (if (detailHasDesc) 1 else 0) + (if (detailHasCover) 1 else 0) +
            (if (realChapters) 3 else if (chapterCount > 0) 1 else 0) +
            (if (pagesOk) 2 else 0)
    }

    @Test fun testAniWorld() = runBlocking { testParser(AniWorld(context), "AniWorld") }
    @Test fun testHentaiCloud() = runBlocking { testParser(HentaiCloud(context), "HentaiCloud") }
    @Test fun testHentaiPlay() = runBlocking { testParser(HentaiPlay(context), "HentaiPlay") }
    @Test fun testHanime() = runBlocking { testParser(Hanime(context), "Hanime") }
    @Test fun testPimpBunny() = runBlocking { testParser(PimpBunny(context), "PimpBunny") }

    private suspend fun testParser(parser: PagedContentParser, name: String) {
        val r = TestResult(name)
        try {
            val filter = ContentListFilter()
            val list = parser.getListPage(1, SortOrder.UPDATED, filter)
            r.listCount = list.size
            r.listOk = list.isNotEmpty()
            if (list.isEmpty()) {
                r.errors += "list empty"
            } else {
                r.listHasCovers = list.count { it.coverUrl != null && it.coverUrl!!.startsWith("http") }
                val badTitles = list.count { it.title.isBlank() || it.title == "Untitled" || it.title == "Watch" }
                if (badTitles > 0) r.errors += "$badTitles items have bad title"

                val list2 = parser.getListPage(2, SortOrder.UPDATED, filter)
                r.page2Count = list2.size
                val urls1 = list.map { it.publicUrl }.toSet()
                val urls2 = list2.map { it.publicUrl }.toSet()
                r.paginationOk = list2.isNotEmpty() && urls1 != urls2
                if (!r.paginationOk) r.errors += "pagination failed"

                val detail = parser.getDetails(list.first())
                r.detailsOk = detail.title.isNotBlank() && detail.title != "Untitled"
                r.detailHasDesc = !detail.description.isNullOrBlank()
                r.detailHasCover = detail.coverUrl?.startsWith("http") == true

                val ch = detail.chapters ?: emptyList()
                r.chapterCount = ch.size
                val onlyWatch = ch.all { (it.title ?: "").contains("Watch") }
                r.realChapters = !onlyWatch && ch.size >= 2
                if (onlyWatch && ch.size <= 1) r.errors += "only placeholder chapter"
                if (ch.isEmpty()) r.errors += "no chapters"

                if (ch.isNotEmpty()) {
                    val pages = parser.getPages(ch.first())
                    r.pageCount = pages.size
                    r.pagesOk = pages.isNotEmpty()
                    if (!r.pagesOk) r.errors += "getPages empty"
                }
            }
        } catch (e: Exception) {
            r.errors += "${e.javaClass.simpleName}: ${e.message?.take(80)}"
        }
        results.add(r)
        val status = if (r.pagesOk) "OK" else if (r.errors.isEmpty()) "WARN" else "ERR"
        println("[$status] $name | list=${r.listCount} cover=${r.listHasCovers} p2=${r.paginationOk} | detail=${r.detailsOk} desc=${r.detailHasDesc} | ch=${r.chapterCount} real=${r.realChapters} | pg=${r.pageCount} | score=${r.score}/14 ${if (r.errors.isNotEmpty()) "| " + r.errors.joinToString("; ") else ""}")
    }
}
