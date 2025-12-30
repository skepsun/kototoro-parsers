package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.MangaLoaderContextMock
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*

class Klz9Test {

    private val context = MangaLoaderContextMock
    private val parser = Klz9(context)

    @Test
    fun testGetListPage() = runBlocking {
        // Test basic list
        val mangaList = parser.getListPage(1, SortOrder.POPULARITY, MangaListFilter())
        println("Fetched ${mangaList.size} manga")
        mangaList.take(5).forEach { manga ->
            println("Title: ${manga.title}, URL: ${manga.url}")
        }
        assert(mangaList.isNotEmpty()) { "Should fetch manga list" }
    }

    @Test
    fun testSearch() = runBlocking {
        // Test search (which uses /api/manga/all)
        val query = "One Piece"
        val searchFilter = MangaListFilter(query = query)
        val searchResults = parser.getListPage(1, SortOrder.POPULARITY, searchFilter)
        println("Searched for '$query', found ${searchResults.size} results")
        searchResults.take(10).forEach { manga ->
            println("Search result: ${manga.title}, URL: ${manga.url}")
        }
        assert(searchResults.isNotEmpty()) { "Should find results for '$query'" }
        assert(searchResults.any { it.title.contains(query, ignoreCase = true) }) { "Should contain the query in title" }
    }

    @Test
    fun testSearchPagination() = runBlocking {
        // Test search pagination
        val query = "a" // Frequent letter to get many results
        val searchFilter = MangaListFilter(query = query)
        
        val page1 = parser.getListPage(1, SortOrder.POPULARITY, searchFilter)
        val page2 = parser.getListPage(2, SortOrder.POPULARITY, searchFilter)
        
        println("Page 1 size: ${page1.size}")
        println("Page 2 size: ${page2.size}")
        
        assert(page1.isNotEmpty()) { "Page 1 should not be empty" }
        if (page1.size == parser.pageSize) {
            assert(page2.isNotEmpty()) { "Page 2 should not be empty if page 1 is full" }
            assert(page1[0].id != page2[0].id) { "Page 1 and Page 2 should have different content" }
        }
    }

    @Test
    fun testGetDetails() = runBlocking {
        // Use One Piece for detail test
        val testUrl = "/manga/one-piece-raw"
        val testManga = Manga(
            id = parser.generateUid(testUrl),
            title = "One Piece",
            altTitles = emptySet(),
            url = testUrl,
            publicUrl = "https://klz9.com$testUrl",
            rating = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )
        
        val detailedManga = parser.getDetails(testManga)
        println("Manga Details: ${detailedManga.title}")
        println("Description: ${detailedManga.description?.take(100)}...")
        println("Chapters: ${detailedManga.chapters?.size ?: 0}")
        
        assert(detailedManga.title.isNotBlank())
        assert(detailedManga.chapters?.isNotEmpty() ?: false)
    }

    @Test
    fun testGetPages() = runBlocking {
        // Get details first to find a chapter
        val testUrl = "/manga/one-piece-raw"
        val testManga = Manga(
            id = parser.generateUid(testUrl),
            title = "One Piece",
            altTitles = emptySet(),
            url = testUrl,
            publicUrl = "https://klz9.com$testUrl",
            rating = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )
        val detailedManga = parser.getDetails(testManga)
        val firstChapter = detailedManga.chapters?.firstOrNull()
        
        assert(firstChapter != null) { "Should find at least one chapter" }
        
        val pages = parser.getPages(firstChapter!!)
        println("Fetched ${pages.size} pages")
        pages.take(3).forEach { page ->
            println("Page URL: ${page.url}")
        }
        
        assert(pages.isNotEmpty()) { "Should fetch pages" }
    }
}
