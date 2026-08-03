package org.skepsun.kototoro.parsers

import com.koushikdutta.quack.QuackContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl
import okhttp3.Protocol
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.newParser
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.LinkResolver
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.requireBody
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.test_util.BitmapTestImpl
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal object ContentLoaderContextMock : ContentLoaderContext() {

    override val cookieJar = InMemoryCookieJar()

    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(CommonHeadersInterceptor())
        .addInterceptor(CloudFlareInterceptor())
        .apply {
            resolveProxyFromEnv()?.let { proxy(it) }
        }
        // 为避免部分服务端在 HTTP/2 下行为异常，这里强制使用 HTTP/1.1
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        // 使用系统代理（依赖操作系统的全局代理设置）
        runCatching { System.setProperty("java.net.useSystemProxies", "true") }
        loadTestCookies()
        // 允许通过环境变量 COPYMANGA_TOKEN 预置授权 Cookie
        runCatching {
            val token = System.getenv("COPYMANGA_TOKEN")?.trim()
            if (!token.isNullOrEmpty()) {
                // 写入到 API 与站点两个域，兼容解析器在两域读取 token/authorization
                cookieJar.insertCookies(
                    "api.copy3000.com",
                    "authorization=$token;",
                    "token=$token;",
                )
                cookieJar.insertCookies(
                    "www.copy3000.com",
                    "authorization=$token;",
                    "token=$token;",
                )
            }
        }.onFailure { /* ignore */ }

        // 允许通过环境变量 PICACG_TOKEN 预置授权 Cookie（官方 API 域）
        runCatching {
            val token = System.getenv("PICACG_TOKEN")?.trim()
            if (!token.isNullOrEmpty()) {
                cookieJar.insertCookies(
                    "picaapi.picacomic.com",
                    "authorization=$token",
                    "token=$token",
                )
                // 同时写入新域，兼容解析器默认域切换到 api.go2778.com
                cookieJar.insertCookies(
                    "api.go2778.com",
                    "authorization=$token",
                    "token=$token",
                )
            }
        }.onFailure { /* ignore */ }

        // 若环境变量未设置，尝试从项目根目录的 picacg_token.txt 读取（可通过 PICACG_SKIP_TOKEN_FILE=1 跳过）
        runCatching {
            val skipTokenFile = System.getenv("PICACG_SKIP_TOKEN_FILE")?.trim() == "1"
            val envToken = System.getenv("PICACG_TOKEN")?.trim()
            if (envToken.isNullOrEmpty() && !skipTokenFile) {
                val tokenFile = java.io.File("../picacg_token.txt")
                println("PICACG token file candidate: ${tokenFile.absolutePath}, exists=${tokenFile.exists()}")
                if (tokenFile.exists()) {
                    val fileToken = tokenFile.readText().trim()
                    if (fileToken.isNotEmpty()) {
                        cookieJar.insertCookies(
                            "picaapi.picacomic.com",
                            "authorization=$fileToken",
                            "token=$fileToken",
                        )
                        cookieJar.insertCookies(
                            "api.go2778.com",
                            "authorization=$fileToken",
                            "token=$fileToken",
                        )
                        println("PICACG preset token from file: length=${fileToken.length}")
                        println("PICACG cookies for picaapi.picacomic.com: ${cookieJar.getCookies("picaapi.picacomic.com").map { it.name }}")
                        println("PICACG cookies for api.go2778.com: ${cookieJar.getCookies("api.go2778.com").map { it.name }}")
                    }
                }
            }
        }.onFailure { /* ignore */ }
    }

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        return QuackContext.create().use {
            it.evaluate(script)?.toString()
        }
    }

    override fun getConfig(source: ContentSource): ContentSourceConfig {
        return SourceConfigMock()
    }

    override fun getDefaultUserAgent(): String = UserAgents.FIREFOX_MOBILE

    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
        val srcImage = response.requireBody().byteStream().use(ImageIO::read)
        checkNotNull(srcImage) { "Cannot decode image" }
        val resImage = (redraw(BitmapTestImpl(srcImage)) as BitmapTestImpl)
        return response.newBuilder()
            .body(resImage.compress("png").toResponseBody("image/png".toMediaTypeOrNull()))
            .build()
    }

    override fun createBitmap(width: Int, height: Int): Bitmap {
        return BitmapTestImpl(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB))
    }

    suspend fun doRequest(url: String, source: ContentSource?): Response {
        val request = Request.Builder()
            .get()
            .url(url)
        if (source != null) {
            request.tag(ContentSource::class.java, source)
        }
        return httpClient.newCall(request.build()).await()
    }

    private fun loadTestCookies() {
        // https://addons.mozilla.org/ru/firefox/addon/cookies-txt/
        javaClass.getResourceAsStream("/cookies.txt")?.use {
            cookieJar.loadFromStream(it)
        } ?: println("No cookies loaded!")
    }

    private fun OkHttpClient.Builder.permissiveSSL() = also { builder ->
        runCatching {
            val trustAllCerts = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, arrayOf(trustAllCerts), SecureRandom())
            val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts)
            builder.hostnameVerifier { _, _ -> true }
        }.onFailure {
            it.printStackTrace()
        }
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

    override fun newParserInstance(source: ContentSource): ContentParser {
        return (source as ContentParserSource).newParser(this)
    }

    override fun newLinkResolver(link: HttpUrl): LinkResolver {
        throw UnsupportedOperationException("LinkResolver not available in test")
    }
}
