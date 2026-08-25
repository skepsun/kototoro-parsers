package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 在线验证 Comick 的浏览/搜索/详情/章节/阅读页，并检查首张图片可访问。
 * 启用方式: 设置环境变量 COMICK_INTEGRATION_TEST=1 后运行；
 * 代理（如有）通过 HTTPS_PROXY/HTTP_PROXY/ALL_PROXY 环境变量生效。
 */
@EnabledIfEnvironmentVariable(named = "COMICK_INTEGRATION_TEST", matches = "1")
class ComickParserIntegrationTest {

    private val parser = ComickParser(ContentLoaderContextMock)

    @Test
    fun `browse search detail chapters pages and image reachability`() = runBlocking {
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        assertTrue(list.isNotEmpty(), "列表为空")

        val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "ecchi"))
        assertTrue(search.isNotEmpty(), "搜索为空")

        val detail = parser.getDetails(list.first())
        assertTrue(detail.title.isNotBlank(), "详情标题为空")
        val chapters = detail.chapters.orEmpty()
        assertTrue(chapters.isNotEmpty(), "章节为空")

        val pages = parser.getPages(chapters.first())
        assertTrue(pages.isNotEmpty(), "阅读页为空")
        val page = pages.first()
        assertTrue(page.url.startsWith("http"), "图片地址异常: ${page.url}")

        // 图片可访问性: Range 请求，期望 2xx/3xx 且为图片类响应。
        val call = httpClient.newCall(
            Request.Builder()
                .url(page.url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                )
                .header("Referer", "https://comick.art/")
                .header("Range", "bytes=0-2047")
                .build(),
        )
        call.execute().use { response ->
            assertTrue(response.code in 200..399, "图片 HTTP ${response.code}: ${page.url}")
            val type = response.header("Content-Type").orEmpty().lowercase()
            assertTrue(
                type.contains("image") || type.contains("octet-stream") || response.code in 300..399,
                "图片响应类型异常: $type",
            )
        }
        println("Comick: pages=${pages.size} first=${page.url.take(120)}")
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
        // 环境变量可能是无 scheme 的 "127.0.0.1:7890"，先补全 scheme 再解析
        val uri = runCatching {
            URI(if (raw.contains("://")) raw else "http://$raw")
        }.getOrElse { return null }
        val scheme = uri.scheme?.lowercase() ?: "http"
        val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
        val host = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 7890
        return Proxy(type, InetSocketAddress(host, port))
    }
}
