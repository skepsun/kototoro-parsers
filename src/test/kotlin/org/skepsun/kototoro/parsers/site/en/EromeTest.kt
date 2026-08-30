package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentChapter

class EromeTest {

    private val parser = Erome(ContentLoaderContextMock)

    @Test
    fun `video media page carries erome referer header`() = runBlocking {
        val chapter = ContentChapter(
            id = 1L,
            title = "Video 1",
            number = 1f,
            volume = 0,
            url = "media:https://v52.erome.com/8939/9hgAlCsY/VIDEO_720p.mp4",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = parser.source,
        )

        val page = parser.getPages(chapter).single()
        // Erome 的媒体 CDN 不带 Referer 会直接 403，播放器必须携带该头。
        assertEquals("https://v52.erome.com/8939/9hgAlCsY/VIDEO_720p.mp4", page.url)
        assertEquals("https://www.erome.com/", page.headers?.get("Referer"))
    }

    @Test
    fun `extract media from album fixture`() {
        val media = parser.extractMedia(fixture("album.html"))

        val video = media.single { it.url.endsWith(".mp4", ignoreCase = true) }
        assertEquals("https://v52.erome.com/8939/9hgAlCsY/VIDEO_720p.mp4", video.url)
        assertEquals("https://s52.erome.com/8939/9hgAlCsY/VIDEO.jpg", video.preview)

        val images = media.filter { it.url.endsWith(".jpg", ignoreCase = true) && it.url == it.preview }
        assertEquals(2, images.size)
        assertTrue(images.all { it.url.startsWith("https://s52.erome.com/") })
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/erome/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
    )
}
