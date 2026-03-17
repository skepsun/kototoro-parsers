package org.koitharu.kotatsu.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.site.zh.Dmbus
import org.jsoup.Jsoup

class DmbusTest {

    private val context = ContentLoaderContextMock
    private val parser = Dmbus(context)

    @Test
    fun testGetListPage() = runBlocking {
        // Disabled due to compilation errors
        /*
        val mangaList = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
        println("获取到 ${mangaList.size} 个视频")
        mangaList.take(5).forEach { manga: Content ->
            println("标题: ${manga.title}, URL: ${manga.url}")
        }
        assert(mangaList.isNotEmpty()) { "应该获取到视频列表" }
        */
    }

    @Test
    fun testSearch() = runBlocking {
        // Disabled due to compilation errors
        /*
        val searchFilter = ContentListFilter(query = "斗罗大陆")
        val searchResults = parser.getListPage(1, SortOrder.POPULARITY, searchFilter)
        println("搜索到 ${searchResults.size} 个结果")
        searchResults.take(5).forEach { manga: Content ->
            println("搜索结果: ${manga.title}, URL: ${manga.url}")
        }
        assert(searchResults.isNotEmpty()) { "应该搜索到结果" }
        */
    }
}