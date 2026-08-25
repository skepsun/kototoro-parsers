package org.skepsun.kototoro.parsers.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * CloudFlareHelper 检测回归测试 — 覆盖 nhentai 场景
 * （403 + server: cloudflare + cf-ray + cf-mitigated: challenge）。
 */
class CloudFlareHelperTest {

    private fun response(code: Int, headers: Map<String, String>): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://nhentai.net/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Forbidden")
            .body("".toResponseBody(null))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    @Test
    fun `detect cloudflare challenge like nhentai`() {
        val resp = response(
            403,
            mapOf(
                "server" to "cloudflare",
                "cf-ray" to "a309c4a3c83b838a-KIX",
                "cf-mitigated" to "challenge",
            ),
        )
        assertEquals(CloudFlareHelper.PROTECTION_CAPTCHA, CloudFlareHelper.checkResponseForProtection(resp))
    }

    @Test
    fun `successful response is not protection`() {
        val resp = response(200, mapOf("server" to "cloudflare", "cf-ray" to "abc-X"))
        assertEquals(CloudFlareHelper.PROTECTION_NOT_DETECTED, CloudFlareHelper.checkResponseForProtection(resp))
    }

    @Test
    fun `403 without cloudflare markers is not protection`() {
        val resp = response(403, emptyMap())
        assertEquals(CloudFlareHelper.PROTECTION_NOT_DETECTED, CloudFlareHelper.checkResponseForProtection(resp))
    }
}
