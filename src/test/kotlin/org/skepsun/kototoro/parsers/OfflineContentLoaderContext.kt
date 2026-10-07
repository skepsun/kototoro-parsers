package org.skepsun.kototoro.parsers

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.LinkResolver

internal class OfflineContentLoaderContext(
    private val fixtures: Map<String, String> = emptyMap(),
    private val responseCodes: Map<String, Int> = emptyMap(),
    private val responseTypes: Map<String, String> = emptyMap(),
    private val transformBody: (Request, String) -> String = { _, body -> body },
) : ContentLoaderContext() {
    val requests = mutableListOf<String>()
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        requests += url
        val resource = requireNotNull(fixtures[url]) { "Unexpected offline request: $url" }
        val html = requireNotNull(javaClass.getResourceAsStream("/fixtures/$resource")) {
            "Missing fixture: $resource"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val contentType = responseTypes[url] ?: "text/html; charset=utf-8"
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(responseCodes[url] ?: 200)
            .message("OK")
            .header("Content-Type", contentType)
            .body(transformBody(chain.request(), html).toResponseBody(
                contentType.toMediaType(),
            ))
            .build()
    }.build()

    override fun newParserInstance(source: ContentSource) = (source as ContentParserSource).newParser(this)
    override fun getConfig(source: ContentSource) = SourceConfigMock()
    override fun getDefaultUserAgent() = "Offline parser test"
    override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Browser unavailable in offline tests")
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = error("JS unavailable in offline tests")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("JS unavailable in offline tests")
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
        error("Bitmap unavailable in offline tests")
    override fun createBitmap(width: Int, height: Int): Bitmap = error("Bitmap unavailable in offline tests")
}
