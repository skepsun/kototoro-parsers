package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.MangaLoaderContextMock
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*

class WelomaTest {

    private val context = MangaLoaderContextMock
    private val parser = Weloma(context)

    @Test
    fun testGetListPage() = runBlocking {
        // Test basic list (latest)
        val mangaList = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter())
        println("Fetched ${mangaList.size} manga from latest")
        mangaList.take(5).forEach { manga ->
            println("Title: ${manga.title}, URL: ${manga.url}")
        }
        assert(mangaList.isNotEmpty()) { "Should fetch manga list" }
        assert(mangaList.all { it.url.startsWith("https://weloma.ru/title/ru") }) { "URLs should be absolute and correct" }
    }

    @Test
    fun testSearch() = runBlocking {
        val query = "Blue Lock"
        val searchFilter = MangaListFilter(query = query)
        val searchResults = parser.getListPage(1, SortOrder.POPULARITY, searchFilter)
        println("Searched for '$query', found ${searchResults.size} results")
        searchResults.take(10).forEach { manga ->
            println("Search result: ${manga.title}, URL: ${manga.url}")
        }
        assert(searchResults.isNotEmpty()) { "Should find results for '$query'" }
        assert(searchResults.any { 
            it.title.contains(query, ignoreCase = true) || 
            it.altTitles.any { alt -> alt.contains(query, ignoreCase = true) } ||
            it.title == "ブルーロック"
        }) { "Should contain the query in title or alt titles" }
    }

    @Test
    fun testGetDetails() = runBlocking {
        // Use Blue Lock for detail test (ID 579)
        val testUrl = "https://weloma.ru/title/ru579"
        val testManga = Manga(
            id = 579L,
            title = "ブルーロック",
            altTitles = emptySet(),
            url = testUrl,
            publicUrl = testUrl,
            rating = 0f,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )
        
        val detailedManga = parser.getDetails(testManga)
        println("Manga Details: ${detailedManga.title}")
        println("Authors: ${detailedManga.authors}")
        println("Tags: ${detailedManga.tags.map { it.title }}")
        println("Description: ${detailedManga.description?.take(100)}...")
        println("Chapters: ${detailedManga.chapters?.size ?: 0}")
        
        assert(detailedManga.title.isNotBlank())
        assert(detailedManga.chapters?.isNotEmpty() ?: false)
        assert(detailedManga.tags.isNotEmpty())
        assert(detailedManga.authors.isNotEmpty())

        // Refinement Check: Chapter Ordering (Ascending)
        val chapters = detailedManga.chapters!!
        if (chapters.size > 1) {
            assert(chapters[0].number < chapters.last().number) { "Chapters should be in ascending order" }
        }

        // Refinement Check: Chapter Formatting (Title should be null to use default)
        assert(chapters.first().title == null) { "Chapter title should be null for default formatting" }
    }

    @Test
    fun testGetPages() = runBlocking {
        val testUrl = "https://weloma.ru/title/ru579"
        val testManga = Manga(
            id = 579L,
            title = "ブルーロック",
            altTitles = emptySet(),
            url = testUrl,
            publicUrl = testUrl,
            rating = 0f,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )
        val detailedManga = parser.getDetails(testManga)
        val firstChapter = detailedManga.chapters?.firstOrNull()
        
        assert(firstChapter != null) { "Should find at least one chapter" }
        println("Testing chapter: ${firstChapter!!.number}, URL: ${firstChapter.url}")
        
        val pages = parser.getPages(firstChapter)
        println("Fetched ${pages.size} pages")
        pages.take(3).forEach { page ->
            println("Page URL: ${page.url}")
        }
        
        assert(pages.isNotEmpty()) { "Should fetch pages" }
        assert(pages.all { it.url.startsWith("http") }) { "All page URLs should be absolute" }
    }
}
