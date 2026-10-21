package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.util.Base64

/**
 * 哔哩轻小说：正文必须从 PC 站（linovelib.com）按 `_N.html` 分页拼全，
 * 不能把移动站截断处的「（內容加載失敗！請刷新或更換瀏覽器）」带进正文。
 */
class BilinovelChapterContentTest {

    private fun chapter(parser: Bilinovel) = ContentChapter(
        id = 1L,
        title = "第一话「咒言玩偶」的传说 前篇",
        number = 1f,
        volume = 0,
        url = "/novel/1082/43627.html",
        scanlator = null,
        uploadDate = 0,
        branch = null,
        source = parser.source,
    )

    @Test
    fun `pc station pages are merged and truncation notice removed`() = runBlocking {
        val context = OfflineContentLoaderContext(
            mapOf(
                "https://www.linovelib.com/novel/1082/43627.html" to "bilinovel/linovelib_chapter_1.html",
                "https://www.linovelib.com/novel/1082/43627_2.html" to "bilinovel/linovelib_chapter_2.html",
            ),
        )
        val parser = Bilinovel(context)

        val content = requireNotNull(parser.getChapterContent(chapter(parser)))
        val doc = Jsoup.parse(content.html)

        assertEquals(
            listOf("「呼……呼……呼！」", "第一章第一页正文。", "斷點之後的殘句", "第一章第二页正文。"),
            doc.select("p").eachText().filter { it.isNotBlank() },
        )
        assertFalse(doc.text().contains("內容加載失敗"), "截断提示不应进入正文")
        assertFalse(doc.text().contains("站点广告位"), "广告块应移除")
        assertFalse(content.html.contains("adsbygoogle"), "广告脚本应移除")
        assertEquals(2, context.requests.size, "应在下一章链接处停止翻页: ${context.requests}")

        val image = requireNotNull(doc.selectFirst("img"))
        assertEquals("https://img3.readpai.com/1/1082/115100/282101.jpg", image.attr("src"))
        assertEquals(1, content.images.size)
    }

    @Test
    fun `content requests to pc station never carry a mobile user agent`() = runBlocking {
        val seen = mutableListOf<Pair<String, String?>>()
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://www.linovelib.com/novel/1082/43627.html" to "bilinovel/linovelib_chapter_1.html",
                "https://www.linovelib.com/novel/1082/43627_2.html" to "bilinovel/linovelib_chapter_2.html",
            ),
            transformBody = { request, body ->
                seen += request.url.toString() to request.header("User-Agent")
                body
            },
        )
        val parser = Bilinovel(context)
        val userAgentKey = mutableListOf<ConfigKey<*>>()
            .also { parser.onCreateConfig(it) }
            .filterIsInstance<ConfigKey.UserAgent>()
            .single()
        (parser.config as SourceConfigMock).set(
            userAgentKey,
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36",
        )

        parser.getChapterContent(chapter(parser))

        assertTrue(seen.isNotEmpty())
        seen.forEach { (url, userAgent) ->
            assertTrue(url.startsWith("https://www.linovelib.com/"), "正文请求应指向 PC 站: $url")
            assertFalse(userAgent.orEmpty().contains("Mobile"), "PC 站请求不能带移动端 UA（会被 302 到移动站）: $userAgent")
            assertTrue(userAgent.orEmpty().contains("Windows"), "PC 站请求应使用桌面 UA: $userAgent")
        }
    }

    @Test
    fun `falls back to mobile station when pc content is blocked`() = runBlocking {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://www.linovelib.com/novel/1082/43627.html" to "bilinovel/cloudflare_challenge.html",
                "https://www.bilinovel.com/novel/1082/43627.html" to "bilinovel/mobile_chapter_1.html",
                "https://www.bilinovel.com/novel/1082/43627_2.html" to "bilinovel/mobile_chapter_2.html",
            ),
            responseCodes = mapOf("https://www.linovelib.com/novel/1082/43627.html" to 403),
        )
        val parser = Bilinovel(context)

        val page = parser.getPages(chapter(parser)).single()
        assertTrue(page.url.startsWith("data:text/html;"))
        val html = String(Base64.getDecoder().decode(page.url.substringAfter("base64,")), Charsets.UTF_8)
        val doc = Jsoup.parse(html)

        assertEquals(
            listOf("手机站第一页正文。", "手机站断点残句", "手机站第二页正文。"),
            doc.select("p").eachText().filter { it.isNotBlank() },
        )
        assertFalse(doc.text().contains("內容加載失敗"))
        assertEquals(3, context.requests.size, "PC 站失败后应回退到移动站: ${context.requests}")
    }
}
