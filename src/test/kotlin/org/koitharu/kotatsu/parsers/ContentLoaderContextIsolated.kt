package org.skepsun.kototoro.parsers

import com.koushikdutta.quack.QuackContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.NoCookiesCookieJar
import org.skepsun.kototoro.parsers.InMemoryCookieJar
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.requireBody
import org.skepsun.kototoro.test_util.BitmapTestImpl
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 一个不预置任何授权 Cookie 的隔离测试上下文，用于凭证登录路径验证。
 */
internal object ContentLoaderContextIsolated : ContentLoaderContext() {

    // 允许通过环境变量切换为内存 Cookie（与 Python 行为一致），默认禁用
    override val cookieJar = run {
        val useMem = System.getenv("PICACG_USE_INMEMORY_COOKIES")?.trim()?.equals("1", ignoreCase = true) == true
        if (useMem) InMemoryCookieJar() else NoCookiesCookieJar()
    }

    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(CommonHeadersInterceptor())
        .addInterceptor(CloudFlareInterceptor())
        .apply {
            resolveProxyFromEnv()?.let { proxy(it) }
        }
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        return QuackContext.create().use { it.evaluate(script)?.toString() }
    }

    override fun getConfig(source: ContentSource): ContentSourceConfig = SourceConfigMock()

    override fun getDefaultUserAgent(): String = UserAgents.FIREFOX_MOBILE

    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
        val srcImage = response.requireBody().byteStream().use(ImageIO::read)
        checkNotNull(srcImage) { "Cannot decode image" }
        val resImage = (redraw(BitmapTestImpl(srcImage)) as BitmapTestImpl)
        return response.newBuilder().body(resImage.compress("png").toResponseBody("image/png".toMediaTypeOrNull())).build()
    }

    override fun createBitmap(width: Int, height: Int): Bitmap {
        return BitmapTestImpl(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB))
    }

    suspend fun doRequest(url: String, source: org.skepsun.kototoro.parsers.model.ContentSource?): okhttp3.Response {
        val request = okhttp3.Request.Builder().get().url(url)
        if (source != null) request.tag(org.skepsun.kototoro.parsers.model.ContentSource::class.java, source)
        return httpClient.newCall(request.build()).await()
    }

    // 显式从环境变量解析代理，并应用到 OkHttp。
    // 支持 HTTPS_PROXY/HTTP_PROXY/ALL_PROXY 及其小写形式，scheme 支持 http/socks5。
    private fun resolveProxyFromEnv(): Proxy? {
        fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        val raw = listOf(
            "HTTPS_PROXY", "https_proxy",
            "HTTP_PROXY", "http_proxy",
            "ALL_PROXY", "all_proxy",
        ).firstNotNullOfOrNull { env(it) } ?: return null

        val uri = runCatching {
            val normalized = if (raw.contains("://")) raw else "http://$raw"
            URI(normalized)
        }.getOrElse { return null }

        val scheme = uri.scheme?.lowercase() ?: "http"
        val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else return null

        println("OkHttp proxy from env: $scheme://$host:$port")
        return Proxy(type, InetSocketAddress(host, port))
    }
}