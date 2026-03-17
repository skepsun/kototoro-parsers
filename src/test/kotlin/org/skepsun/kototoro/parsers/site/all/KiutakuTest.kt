package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*

class KiutakuTest {

    private val context = ContentLoaderContextMock
    private val parser = Kiutaku(context)

    @Test
    fun testGetListPage() = runBlocking {
        val mangaList = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        println("Fetched ${mangaList.size} manga")
        mangaList.take(5).forEach { manga ->
            println("Title: ${manga.title}, URL: ${manga.url}")
        }
        assert(mangaList.isNotEmpty()) { "Should fetch manga list" }
    }

    @Test
    fun testSearch() = runBlocking {
        val query = "Sally"
        val searchResults = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = query))
        println("Searched for '$query', found ${searchResults.size} results")
        searchResults.take(10).forEach { manga ->
            println("Search result: ${manga.title}, URL: ${manga.url}")
        }
        assert(searchResults.isNotEmpty()) { "Should find results for '$query'" }
    }

    @Test
    fun testGetDetails() = runBlocking {
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        val firstContent = list.first()
        val detailedContent = parser.getDetails(firstContent)
        println("Content Details: ${detailedContent.title}")
        println("Tags: ${detailedContent.tags.joinToString { it.title }}")
        println("Chapters: ${detailedContent.chapters?.size ?: 0}")
        
        assert(detailedContent.title.isNotBlank())
        assert(detailedContent.chapters?.isNotEmpty() ?: false)
    }

    @Test
    fun testGetPages() = runBlocking {
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        val firstContent = list.first()
        val detailedContent = parser.getDetails(firstContent)
        val firstChapter = detailedContent.chapters?.firstOrNull()
        
        assert(firstChapter != null)
        
        val pages = parser.getPages(firstChapter!!)
        println("Fetched ${pages.size} pages")
        
        assert(pages.isNotEmpty()) { "Should fetch pages" }
        
        // Try to fetch one image to verify Referer fix
        val firstPage = pages.first()
        val response = context.httpClient.newCall(
            okhttp3.Request.Builder()
                .url(firstPage.url)
                .tag(ContentSource::class.java, parser.source)
                .build()
        ).execute()
        
        println("Image fetch result: ${response.code} for ${firstPage.url}")
        assert(response.isSuccessful) { "Should be able to fetch image" }
    }

    @Test
    fun testTagFilter() = runBlocking {
        val filterOptions = parser.getFilterOptions()
        val firstTag = filterOptions.availableTags.first()
        val filter = ContentListFilter(tags = setOf(firstTag))
        
        val list = parser.getListPage(1, SortOrder.UPDATED, filter)
        println("Fetched ${list.size} items for tag: ${firstTag.title}")
        
        assert(list.isNotEmpty()) { "Should fetch items for tag ${firstTag.title}" }
    }

    @Test
    fun testPagination() = runBlocking {
        val list1 = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        val list2 = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        
        println("Page 1 first item ID: ${list1.firstOrNull()?.id}")
        println("Page 2 first item ID: ${list2.firstOrNull()?.id}")
        
        assert(list1.isNotEmpty()) { "Page 1 should not be empty" }
        assert(list2.isNotEmpty()) { "Page 2 should not be empty" }
        assert(list1.first().id != list2.first().id) { "Page 1 and Page 2 should have different items" }
    }
}
