package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import java.util.Base64

internal class Pinse91Test {

    private val parser = Pinse91(ContentLoaderContextMock)

    @Test
    fun `finds current playback api`() {
        val html = """
            <script>
                window.__jjPlaybackSourceRequest = {
                    url: '/api/videos/452450/playback'
                };
            </script>
        """.trimIndent()

        assertEquals("/api/videos/452450/playback", parser.findPlaybackApiPath(html))
    }

    @Test
    fun `preserves signed query parameters from html`() {
        val html = """<video src="https://cdn.example/pl.m3u8?token=a+b&amp;expires=42"></video>"""

        assertEquals(
            listOf("https://cdn.example/pl.m3u8?token=a+b&expires=42"),
            parser.findUrlsByRegex(html),
        )
    }

    @Test
    fun `extracts escaped url only once`() {
        val html = """const source = "https:\/\/cdn.example\/video.mp4?token=abc\u0026expires\u003d42";"""

        assertEquals(
            listOf("https://cdn.example/video.mp4?token=abc&expires=42"),
            parser.findUrlsByRegex(html),
        )
    }

    @Test
    fun `extracts media url from base64 payload`() {
        val url = "https://cdn.example/master.m3u8?token=a+b&expires=42"
        val encoded = Base64.getEncoder().encodeToString(url.toByteArray())

        assertEquals(listOf(url), parser.findUrlsByRegex("data='$encoded'"))
    }
}
