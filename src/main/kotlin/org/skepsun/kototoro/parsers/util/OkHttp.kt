@file:JvmName("OkHttpUtils")

package org.skepsun.kototoro.parsers.util

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.internal.toLongOrDefault
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

public suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    val callback = ContinuationCallCallback(this, continuation)
    continuation.invokeOnCancellation(callback)
    enqueue(callback)
}

public val Response.mimeType: String?
    get() = header("content-type")?.substringBefore(';')?.trim()?.nullIfEmpty()?.lowercase()

public val HttpUrl.isHttpOrHttps: Boolean
    get() = scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)

public fun Headers.Builder.mergeWith(other: Headers, replaceExisting: Boolean): Headers.Builder {
    for ((name, value) in other) {
        if (replaceExisting || this[name] == null) {
            this[name] = value
        }
    }
    return this
}

public fun Response.copy(): Response = newBuilder()
    .body(peekBody(Long.MAX_VALUE))
    .build()

public fun Response.Builder.setHeader(name: String, value: String?): Response.Builder = if (value == null) {
    removeHeader(name)
} else {
    header(name, value)
}

public inline fun Response.map(mapper: (ResponseBody) -> ResponseBody): Response {
    contract {
        callsInPlace(mapper, InvocationKind.AT_MOST_ONCE)
    }
    return body.use { responseBody ->
        newBuilder()
            .body(mapper(responseBody))
            .build()
    }
}

public fun Response.headersContentLength(
    defaultValue: Long = -1,
): Long = headers["Content-Length"]?.toLongOrDefault(defaultValue) ?: defaultValue

private val chromeVersionRegex = Regex("""Chrome/(\d+)""")

/**
 * Append Sec-CH-UA client hints derived from [userAgent].
 *
 * Cloudflare-protected sites compare these hints with the UA; sending Chromium hints next to
 * a non-Chromium UA is itself a bot signal, so nothing is added unless the UA looks like Chromium.
 */
public fun Headers.Builder.addBrowserClientHints(userAgent: String): Headers.Builder {
    val version = chromeVersionRegex.find(userAgent)?.groupValues?.get(1) ?: return this
    add("Sec-CH-UA", "\"Chromium\";v=\"$version\", \"Not_A Brand\";v=\"24\"")
    add("Sec-CH-UA-Mobile", if (userAgent.contains("Mobile")) "?1" else "?0")
    add(
        "Sec-CH-UA-Platform",
        when {
            userAgent.contains("Android") -> "\"Android\""
            userAgent.contains("iPhone") || userAgent.contains("iPad") -> "\"iOS\""
            userAgent.contains("Mac OS X") -> "\"macOS\""
            userAgent.contains("Linux") -> "\"Linux\""
            else -> "\"Windows\""
        },
    )
    return this
}
