package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `requests hd playback source`() {
        assertEquals(
            "https://91pinse.com/api/videos/452450/playback?hd=1",
            parser.buildPlaybackApiUrl("/api/videos/452450/playback").toString(),
        )
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

    @Test
    fun `prefers hd source when video has multiple quality streams`() {
        val hd = JSONObject(
            """{"url":"https://cdn.example/web/hd.mp4","fallback_url":"https://cdn.example/hls/master.m3u8"}""",
        )
        val base = JSONObject("""{"url":"https://cdn.example/hls/video.m3u8","fallback_url":""}""")

        assertEquals(hd, parser.selectPlaybackJson(hd, base))
    }

    @Test
    fun `falls back to base stream when hd variant is absent`() {
        // 单画质视频：?hd=1 返回 404/空响应，选中结果应回退到基础流。
        val hd = JSONObject("""{}""")
        val base = JSONObject("""{"url":"https://cdn.example/hls/video.m3u8","fallback_url":""}""")

        assertEquals(base, parser.selectPlaybackJson(hd, base))
        assertEquals(base, parser.selectPlaybackJson(null, base))
    }

    @Test
    fun `ignores responses without any playable source`() {
        assertNull(parser.selectPlaybackJson(JSONObject("""{}"""), JSONObject("""{"url":"","fallback_url":""}""")))
    }
}
