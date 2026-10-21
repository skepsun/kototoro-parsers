package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.network.UserAgents

/**
 * 轻小说文库的请求头约定：
 * Cloudflare 只放行浏览器 UA，因此默认必须是新版 Chrome，且在来源设置里可覆盖；
 * 覆盖成非 Chromium UA 时不能继续发送与之矛盾的 Sec-CH-UA 客户端提示。
 */
class Wenku8ConfigTest {

    private val parser = Wenku8(ContentLoaderContextMock)

    @Test
    fun `user agent is exposed in source settings`() {
        val keys = mutableListOf<ConfigKey<*>>()
        parser.onCreateConfig(keys)

        val userAgentKey = keys.filterIsInstance<ConfigKey.UserAgent>().singleOrNull()
        assertNotNull(userAgentKey, "轻小说文库应暴露 UA 设置项: ${keys.map { it.key }}")
        assertTrue(
            userAgentKey!!.defaultValue.contains("Chrome/155"),
            "默认 UA 应为内置浏览器同款新版 Chrome，实际: ${userAgentKey.defaultValue}",
        )
    }

    @Test
    fun `default request headers match a chromium browser`() {
        val headers = parser.getRequestHeaders()

        assertTrue(headers["User-Agent"]!!.contains("Chrome/155"))
        assertEquals("\"Chromium\";v=\"155\", \"Not_A Brand\";v=\"24\"", headers["Sec-CH-UA"])
        assertEquals("?0", headers["Sec-CH-UA-Mobile"])
        assertEquals("\"Windows\"", headers["Sec-CH-UA-Platform"])
        assertEquals("document", headers["Sec-Fetch-Dest"])
    }

    @Test
    fun `custom user agent replaces default and drops mismatched client hints`() {
        val headers = parser.buildHeaders(UserAgents.FIREFOX_MOBILE)

        assertEquals(UserAgents.FIREFOX_MOBILE, headers["User-Agent"])
        assertNull(headers["Sec-CH-UA"], "Firefox UA 不应携带 Chromium 客户端提示")
        assertNull(headers["Sec-CH-UA-Mobile"])
        assertNull(headers["Sec-CH-UA-Platform"])
    }

    @Test
    fun `mobile chrome user agent keeps consistent client hints`() {
        val headers = parser.buildHeaders(UserAgents.CHROME_MOBILE)

        assertEquals("?1", headers["Sec-CH-UA-Mobile"])
        assertEquals("\"Android\"", headers["Sec-CH-UA-Platform"])
        assertTrue(headers["Sec-CH-UA"]!!.contains("\"Chromium\";v=\"131\""))
    }
}
