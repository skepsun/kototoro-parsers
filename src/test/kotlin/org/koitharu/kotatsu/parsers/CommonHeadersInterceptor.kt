package org.skepsun.kototoro.parsers

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentSource

private const val HEADER_REFERER = "Referer"

internal class CommonHeadersInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(ContentSource::class.java)
        val parser = if (source is ContentParserSource) {
            ContentLoaderContextMock.newParserInstance(source)
        } else {
            null
        }
        val headersBuilder = request.headers.newBuilder()
        val suppressReferer = runCatching {
            val v = System.getenv("PICACG_SUPPRESS_LANG_REFERER")?.trim()?.lowercase()
            (v == "1" || v == "true" || v == "yes")
        }.getOrDefault(false)
        val isPicacg = request.url.host.contains("picacomic", ignoreCase = true) ||
            request.header("accept")?.contains("application/vnd.picacomic.com", ignoreCase = true) == true
        if (headersBuilder[HEADER_REFERER] == null && parser != null) {
            if (!(suppressReferer && isPicacg)) {
                headersBuilder[HEADER_REFERER] = "https://${parser.domain}/"
            }
        }
        val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
        return if (parser is Interceptor) {
            parser.intercept(ProxyChain(chain, newRequest))
        } else {
            chain.proceed(newRequest)
        }
    }

	private class ProxyChain(
		private val delegate: Interceptor.Chain,
		private val request: Request,
	) : Interceptor.Chain by delegate {

		override fun request(): Request = request
	}
}
