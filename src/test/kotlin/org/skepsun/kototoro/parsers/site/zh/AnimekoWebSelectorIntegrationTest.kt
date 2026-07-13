package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

class AnimekoWebSelectorIntegrationTest {

    private val context = ContentLoaderContextMock
    private val results = mutableListOf<TestResult>()

    data class TestResult(
        val name: String,
        var listOk: Boolean = false, var listCount: Int = 0, var listHasCovers: Int = 0,
        var listHasTitles: Boolean = false,
        var paginationOk: Boolean = false, var page2Count: Int = 0,
        var detailsOk: Boolean = false, var detailHasDesc: Boolean = false, var detailHasCover: Boolean = false,
        var chapterCount: Int = 0, var realChapters: Boolean = false,
        var pagesOk: Boolean = false, var pageCount: Int = 0,
        var filterOk: Boolean = false, var filterOptions: Int = 0,
        val errors: MutableList<String> = mutableListOf()
    ) {
        val score get() = (if (listOk) 2 else 0) + (if (listHasCovers > listCount / 2) 1 else 0) +
            (if (listHasTitles) 1 else 0) + (if (paginationOk) 1 else 0) +
            (if (detailsOk) 2 else 0) + (if (detailHasDesc) 1 else 0) + (if (detailHasCover) 1 else 0) +
            (if (realChapters) 3 else if (chapterCount > 0) 1 else 0) +
            (if (pagesOk) 2 else 0) + (if (filterOk) 1 else 0)
        val maxScore get() = 15
    }

    @Test fun testFantuan() = runBlocking { testParser(Fantuan(context), "Fantuan") }
    @Test fun testUzvod() = runBlocking { testParser(Uzvod(context), "Uzvod") }
    @Test fun testJibi() = runBlocking { testParser(Jibi(context), "Jibi") }
    @Test fun testFanqie() = runBlocking { testParser(Fanqie(context), "Fanqie") }
    @Test fun testSenzhiwu() = runBlocking { testParser(Senzhiwu(context), "Senzhiwu") }

    private suspend fun testParser(parser: PagedContentParser, name: String) {
        val r = TestResult(name)
        try {
            try { val f = parser.getFilterOptions(); r.filterOptions = f.availableTags.size + f.tagGroups.size; r.filterOk = f.availableContentTypes.isNotEmpty() } catch (_: Exception) { }
            val filter = ContentListFilter()
            val list = parser.getListPage(1, SortOrder.UPDATED, filter)
            r.listCount = list.size; r.listOk = list.isNotEmpty()
            if (list.isEmpty()) { r.errors += "list empty" }
            else {
                r.listHasCovers = list.count { it.coverUrl != null && it.coverUrl!!.startsWith("http") }
                r.listHasTitles = list.all { it.title.isNotBlank() && it.title != "Untitled" }
                val badTitles = list.count { it.title.isBlank() || it.title == "Untitled" }
                if (badTitles > 0) r.errors += "$badTitles bad titles"
                try { val l2 = parser.getListPage(2, SortOrder.UPDATED, filter); r.page2Count = l2.size; r.paginationOk = l2.isNotEmpty() && list.map{it.publicUrl}.toSet() != l2.map{it.publicUrl}.toSet(); if (!r.paginationOk) r.errors += "pagination failed" } catch (_: Exception) { r.errors += "pagination error" }
                var detail = list.first()
                try { detail = parser.getDetails(detail); r.detailsOk = detail.title.isNotBlank() && detail.title != "Untitled"; r.detailHasDesc = !detail.description.isNullOrBlank(); r.detailHasCover = detail.coverUrl?.startsWith("http") == true } catch (_: Exception) { r.errors += "getDetails failed" }
                val ch = detail.chapters ?: emptyList(); r.chapterCount = ch.size
                val onlyWatch = ch.all { (it.title ?: "").contains("Watch") }; r.realChapters = !onlyWatch && ch.size >= 2
                if (onlyWatch && ch.size <= 1) r.errors += "only placeholder chapter"
                if (ch.isEmpty()) r.errors += "no chapters"
                if (ch.isNotEmpty()) { try { val p = parser.getPages(ch.first()); r.pageCount = p.size; r.pagesOk = p.isNotEmpty() && p.first().url.startsWith("http"); if (!r.pagesOk) r.errors += "getPages failed" } catch (_: Exception) { r.errors += "getPages failed" } }
            }
        } catch (e: Exception) { r.errors += "${e.javaClass.simpleName}: ${e.message?.take(80)}" }
        results.add(r)
        println("[${if (r.pagesOk) "OK" else if (r.chapterCount > 0) "WARN" else "ERR"}] $name | list=${r.listCount} cov=${r.listHasCovers} p2=${r.paginationOk} | det=${r.detailsOk} desc=${r.detailHasDesc} cov2=${r.detailHasCover} | ch=${r.chapterCount} real=${r.realChapters} | pg=${r.pageCount} | score=${r.score}/${r.maxScore} ${if (r.errors.isNotEmpty()) "| " + r.errors.joinToString("; ") else ""}")
    }
}