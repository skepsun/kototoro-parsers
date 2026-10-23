package org.skepsun.kototoro.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class PimpBunnyTest {

    private val parser = PimpBunny(ContentLoaderContextMock)

    @Test
    fun `parse details fixture`() {
        val result = parser.parseDetails(fixture("detail.html"), content())

        assertEquals("Example Video One", result.title)
        assertEquals("Example description for video one.", result.description)
        assertEquals("https://pimpbunny.com/contents/videos_screenshots/1000/100001/preview.jpg", result.coverUrl)
        assertEquals(setOf("Example Model"), result.authors)
        assertEquals(
            setOf("4k", "blowjob", "4k-porn", "sexy-outfit"),
            result.tags?.map { it.key }?.toSet(),
        )
    }

    @Test
    fun `chapter points to detail page so playback resolves a fresh token`() {
        val chapter = parser.parseDetails(fixture("detail.html"), content()).chapters?.single()

        assertNotNull(chapter)
        // 流地址带 2 小时有效的 v-acctoken，章节只能缓存详情页地址
        assertEquals("/videos/example-video-one/", chapter?.url)
        assertEquals("Video", chapter?.title)
    }

    @Test
    fun `resolve stream picks best quality up to 1080p`() {
        assertEquals(
            "https://pimpbunny.com/get_file/50/dddddddddddddddddddddddddddddddd/100000/100001/" +
                "100001_pb_1080p.mp4/?v-acctoken=EXAMPLE1080",
            parser.resolveStreamUrl(fixture("detail.html")),
        )
    }

    @Test
    fun `resolve stream skips paid redirect slot`() {
        val url = parser.resolveStreamUrl(fixture("detail.html"))

        assertFalse(url.orEmpty().contains("upgrade=true"))
        assertFalse(url.orEmpty().contains("v-acctoken=EXAMPLE1440"))
    }

    @Test
    fun `resolve stream falls back to ld json content url`() {
        assertEquals(
            "https://pimpbunny.com/get_file/51/f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0/100000/100002/100002_720p.mp4/",
            parser.resolveStreamUrl(fixture("detail_ldjson.html")),
        )
    }

    @Test
    fun `resolve stream decodes kvs obfuscated url`() {
        assertEquals(
            "https://pimpbunny.com/get_file/1/0c678e567eb073707ec8c87ebf1c3833/secure/65299/example-1080p.mp4/",
            parser.resolveStreamUrl(fixture("detail_obfuscated.html")),
        )
    }

    @Test
    fun `resolve stream returns null when page has no stream`() {
        assertNull(parser.resolveStreamUrl(fixture("detail_no_stream.html")))
    }

    @Test
    fun `stream pages carry referer header`() {
        val chapter = parser.parseDetails(fixture("detail.html"), content()).chapters?.single()
        assertNotNull(chapter)
        val page = parser.videoPage(requireNotNull(chapter), "https://pimpbunny.com/get_file/1/example.mp4/")

        // get_file 端点缺少 Referer 会直接 403，播放页必须带来源头
        assertEquals("https://pimpbunny.com/", page.headers?.get("Referer"))
        assertNotNull(page.headers?.get("User-Agent"))
        assertEquals("https://pimpbunny.com/get_file/1/example.mp4/", page.url)
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/pimpbunny/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://pimpbunny.com/",
    )

    private fun content() = Content(
        id = 1,
        title = "Example Video One",
        altTitles = emptySet(),
        url = "/videos/example-video-one/",
        publicUrl = "https://pimpbunny.com/videos/example-video-one/",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = parser.source,
    )
}
