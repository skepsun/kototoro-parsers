package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 在线验证三个新增站点的列表/详情/播放页解析，并检查提取到的流地址可访问。
 * 启用方式: 设置环境变量 VIDEO_SOURCE_REQUEST_INTEGRATION_TEST=1 后运行；
 * 代理（如有）通过 HTTPS_PROXY/HTTP_PROXY/ALL_PROXY 环境变量生效。
 */
@EnabledIfEnvironmentVariable(named = "VIDEO_SOURCE_REQUEST_INTEGRATION_TEST", matches = "1")
class VideoSourceRequestIntegrationTest {

    private val context = ContentLoaderContextMock

    @Test
    fun fpo() = runBlocking { verify(FpoXxx(context), "FPOXXX") }

    @Test
    fun playVids() = runBlocking { verify(PlayVids(context), "PLAYVIDS") }

    @Test
    fun tnaflix() = runBlocking { verify(Tnaflix(context), "TNAFLIX") }

    @Test
    fun erome() = runBlocking { verify(Erome(context), "EROME") }

    private suspend fun verify(parser: PagedContentParser, name: String) {
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        assertTrue(list.isNotEmpty(), "$name: 列表为空")

        val pageTwo = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        assertTrue(pageTwo.isNotEmpty(), "$name: 第 2 页列表为空")

        val detail = parser.getDetails(list.first())
        assertTrue(detail.title.isNotBlank(), "$name: 标题为空")
        assertTrue(detail.chapters?.isNotEmpty() == true, "$name: 章节为空")

        val chapter = detail.chapters!!.first()
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty(), "$name: 播放页为空")
        val page = pages.first()
        assertTrue(page.url.startsWith("http"), "$name: 视频地址异常: ${page.url}")

        // 流地址可访问性: 带页面 headers 做 Range 请求，期望 2xx/3xx 且为视频类响应。
        val call = httpClient.newCall(
            Request.Builder()
                .url(page.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Range", "bytes=0-2047")
                .apply {
                    page.headers?.forEach { (k, v) -> header(k, v) }
                }
                .build(),
        )
        call.execute().use { response ->
            assertTrue(response.code in 200..399, "$name: 流地址 HTTP ${response.code}: ${page.url}")
            val type = response.header("Content-Type").orEmpty().lowercase()
            assertTrue(
                type.contains("mp4") || type.contains("mpegurl") || type.contains("video") ||
                    type.contains("octet-stream") || response.code in 300..399,
                "$name: 流地址响应类型异常: $type",
            )
        }
        println("$name: stream ${page.url.take(120)}")
    }

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        proxyFromEnv()?.let { builder.proxy(it) }
        builder.build()
    }

    private fun proxyFromEnv(): Proxy? {
        fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        val raw = listOf(
            "HTTPS_PROXY", "https_proxy",
            "HTTP_PROXY", "http_proxy",
            "ALL_PROXY", "all_proxy",
        ).firstNotNullOfOrNull { env(it) } ?: return null
        val uri = runCatching {
            URI(if (raw.contains("://")) raw else "http://$raw")
        }.getOrElse { return null }
        val scheme = uri.scheme?.lowercase() ?: "http"
        val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
        val host = uri.host ?: return null
        val port = uri.port
        return if (port != -1) Proxy(type, InetSocketAddress(host, port)) else null
    }
}
