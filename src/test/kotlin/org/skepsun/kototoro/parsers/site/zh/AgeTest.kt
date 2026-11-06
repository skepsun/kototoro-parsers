package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * AGE动漫（agedm.io）离线夹具测试：
 * - 播放页只剩一个 `#iframeForVideo`，指向第三方解析服务（`jx.wuzhoupai.com:8443`）；
 * - `sid=2..5`（非凡/暴风/无尽/计算云）走 `…/m3u8/?url=age_…`，响应里 `var Vurl` 就是 m3u8 直链；
 * - `sid=1`（西瓜）走 `…/vip/?url=age_…`，响应里 `var Vurl` 是 96 位十六进制密文，
 *   需要页面自己的 `global.min.js` 现场解密，客户端拿不到直链——必须报错而不是把密文当播放地址。
 */
class AgeTest {

    private val parser = Age(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(html(name), BASE_URI)

    private fun html(name: String): String = javaClass.getResourceAsStream("/fixtures/agedm/$name")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("fixture not found: $name")

    @Test
    fun `advertises the two orders the site really supports`() {
        assertEquals(setOf(SortOrder.UPDATED, SortOrder.POPULARITY), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertTrue(parser.filterCapabilities.isMultipleTagsSupported)
    }

    @Test
    fun `detail fixture yields one chapter per episode of every line`() {
        val chapters = parser.parseChapters(fixture("detail.html"))

        // 夹具里 5 条线路（西瓜 + 4 条 m3u8）各 3 集
        assertEquals(15, chapters.size)
        assertEquals(
            listOf("西瓜", "非凡", "暴风", "无尽", "计算云"),
            chapters.mapNotNull { it.branch }.distinct(),
        )
        assertEquals(
            listOf("第01集", "第02集", "第03集", "第01集", "第02集", "第03集"),
            chapters.take(6).map { it.title },
        )
        // 线路编号就在播放页 URL 里，getPages 靠它区分解析端点
        assertEquals(listOf("/play/20260282/1/1", "/play/20260282/2/1"), listOf(chapters[0].url, chapters[3].url))
    }

    @Test
    fun `chapter ids stay stable and unique inside a line`() {
        val chapters = parser.parseChapters(fixture("detail.html"))
        assertEquals(chapters.size, chapters.map { it.id }.distinct().size)
        // 同一集在不同线路 id 必须不同，否则换线路会串集
        assertTrue(chapters[0].id != chapters[3].id)
    }

    @Test
    fun `m3u8 line exposes the direct stream url`() {
        val url = parser.parseStreamUrl(html("iframe_m3u8.html"))
        assertEquals("https://vip.ffzy-plays.com/20261002/58948_5c1e449d/index.m3u8", url)
    }

    @Test
    fun `vip line ciphertext is never treated as a stream url`() {
        val html = html("iframe_vip.html")
        // 夹具里确实有密文 Vurl，且只带一个广告 mp4（Kpadmp4），没有 m3u8
        assertTrue(html.contains("var Vurl"))
        assertTrue(html.contains("Kpadmp4"))
        assertNull(parser.parseStreamUrl(html))
    }

    @Test
    fun `ad poster mp4 is not mistaken for the stream`() {
        val withAd = """
            <script>
            var Vurl = 'https://example.com/real.m3u8';
            var PlayConfig = {'Kpadmp4': "https://175.178.227.159:7788/Play/adposter.mp4"};
            </script>
        """.trimIndent()
        assertEquals("https://example.com/real.m3u8", parser.parseStreamUrl(withAd))

        // 只有广告没有正片时必须返回 null，交给调用方报「线路不可播」
        val adOnly = """<script>var PlayConfig = {'Kpadmp4': "https://x/Play/adposter.mp4"};</script>"""
        assertNull(parser.parseStreamUrl(adOnly))
    }

    @Test
    fun `vip parser endpoint is flagged as js only`() {
        val vip = "https://jx.wuzhoupai.com:8443/vip/?url=age_768EF8F9"
        val m3u8 = "https://jx.wuzhoupai.com:8443/m3u8/?url=age_1050ZkMu"
        assertTrue(parser.isJsOnlyIframe(vip))
        assertFalse(parser.isJsOnlyIframe(m3u8))
    }

    @Test
    fun `both play fixtures point at the parser endpoint of their line`() {
        val m3u8Iframe = fixture("play.html").selectFirst("#iframeForVideo")?.attr("src").orEmpty()
        val vipIframe = fixture("play_vip.html").selectFirst("#iframeForVideo")?.attr("src").orEmpty()

        assertNotNull(m3u8Iframe)
        assertTrue(m3u8Iframe.contains("/m3u8/?url=age_"))
        assertFalse(parser.isJsOnlyIframe(m3u8Iframe))

        assertTrue(vipIframe.contains("/vip/?url=age_"))
        assertTrue(parser.isJsOnlyIframe(vipIframe))
    }

    private companion object {
        private const val BASE_URI = "https://www.agedm.io/"
    }
}
