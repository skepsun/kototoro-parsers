package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.util.*

class RawkumaTest {

    private val parser = Rawkuma(ContentLoaderContextMock)

    @Test
    fun testGetPagesNewChapterPage() = runBlocking {
        // 2026-08 之后的章节页：图片在 <section data-image-data> 内，域名 kuma.kyut.dev
        val chapter = ContentChapter(
            id = parser.generateUid("/manga/fate-grand-order-turas-realta/chapter-1.342974/"),
            title = "Chapter 1",
            number = 1f,
            volume = 0,
            url = "/manga/fate-grand-order-turas-realta/chapter-1.342974/",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = parser.source,
        )

        val pages = parser.getPages(chapter)
        println("Rawkuma fetched ${pages.size} pages")
        pages.take(3).forEach { println("Page URL: ${it.url}") }

        assert(pages.isNotEmpty()) { "Should fetch pages from the new chapter page structure" }
        assert(pages.all { it.url.startsWith("http") }) { "All page urls should be absolute" }
    }

    @Test
    fun testGetPagesFromDetails() = runBlocking {
        // 端到端：先解析详情拿章节，再取图片
        val testUrl = "/manga/fate-grand-order-turas-realta/"
        val testContent = Content(
            id = parser.generateUid(testUrl),
            title = "Fate/Grand Order - Turas Realta",
            altTitles = emptySet(),
            url = testUrl,
            publicUrl = "https://rawkuma.net$testUrl",
            rating = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )

        val detailedContent = parser.getDetails(testContent)
        val firstChapter = detailedContent.chapters?.firstOrNull()
        assert(firstChapter != null) { "Should find at least one chapter" }

        val pages = parser.getPages(firstChapter!!)
        println("Rawkuma E2E fetched ${pages.size} pages")
        assert(pages.isNotEmpty()) { "Should fetch pages" }
    }
}
