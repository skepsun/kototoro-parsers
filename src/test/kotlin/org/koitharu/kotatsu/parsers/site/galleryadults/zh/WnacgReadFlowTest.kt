package org.skepsun.kototoro.parsers.site.galleryadults.zh

import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.CommonHeadersInterceptor
import org.skepsun.kototoro.parsers.InMemoryCookieJar
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.network.OkHttpWebClient
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class WnacgReadFlowTest {

    // 复用现有测试上下文，网络严谨性在图片 HEAD/GET 阶段通过自建 OkHttpClient 体现

    private fun isUrlAbsolute(u: String?): Boolean = u?.startsWith("http://") == true || u?.startsWith("https://") == true

    private fun isLikelyPlaceholder(u: String): Boolean {
        val l = u.lowercase()
        val name = l.substringAfterLast('/')
        val isGif = name.endsWith(".gif")
        val hasLoadingWord = name.contains("loading") || name.contains("spinner") || name.contains("lazy") || name.contains("placeholder") || name.contains("spacer") || name.contains("blank") || name.contains("ajax-loader")
        val inStatic = l.contains("/static/") || l.contains("/assets/") || l.contains("/images/")
        return (isGif && (hasLoadingWord || inStatic)) || hasLoadingWord
    }

    @Test
    fun read_flow_head_then_get_images_no_handshake_error() = runTest(timeout = 2.minutes) {
        val context = ContentLoaderContextMock
        val parser = context.newParserInstance(ContentParserSource.WNACG)
        val domain = parser.domain
        val aid = "327407"
        val seed = org.skepsun.kototoro.parsers.model.Content(
            id = aid.hashCode().toLong(),
            title = "aid-$aid",
            altTitles = emptySet(),
            url = "/photos-index-aid-$aid.html",
            publicUrl = "https://$domain/photos-index-aid-$aid.html",
            rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = null,
            chapters = null,
            source = ContentParserSource.WNACG,
        )
        val detailed = parser.getDetails(seed)
        val chapter = detailed.chapters?.firstOrNull() ?: error("未解析到章节：aid=$aid")

        val pages = parser.getPages(chapter)

        assertTrue(pages.isNotEmpty(), "页面列表为空")
        assertTrue(pages.all { isUrlAbsolute(it.url) }, "存在非绝对地址")
        assertTrue(pages.none { isLikelyPlaceholder(it.url) }, "存在明显占位图/加载图")
        assertDistinctByUrl(pages)

        // 仅取前 3 张以控制测试时长
        val testTargets = pages.take(3)
        val extraHeaders = Headers.Builder()
            .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        // 顺序执行 HEAD + GET：HEAD 成功后再 GET 校验类型与长度
        for (page in testTargets) {
            val strictClient = OkHttpClient.Builder()
                .cookieJar(context.cookieJar)
                .addInterceptor(CommonHeadersInterceptor())
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 7890)))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
            val web = OkHttpWebClient(strictClient, ContentParserSource.WNACG)

            val headResp = web.httpHead(page.url)
            headResp.close()

            val getResp = web.httpGet(page.url, extraHeaders)
            val ct = getResp.header("Content-Type")?.lowercase()
            // chunked 传输时 contentLength 为 -1，退回按实际读取字节数判定长度
            val len = getResp.body?.let { body ->
                body.contentLength().takeIf { it > 0 } ?: body.use { it.bytes().size.toLong() }
            } ?: -1L
            getResp.close()

            assertTrue(ct?.startsWith("image/") == true, "Content-Type 不是图片: $ct")
            assertTrue(len > 1024, "图片长度过小: $len")
        }
    }

    private fun assertDistinctByUrl(pages: List<ContentPage>) {
        val seen = HashSet<String>(pages.size)
        val dup = pages.map { it.url }.any { !seen.add(it) }
        assertTrue(!dup, "图片 URL 存在重复")
    }
}