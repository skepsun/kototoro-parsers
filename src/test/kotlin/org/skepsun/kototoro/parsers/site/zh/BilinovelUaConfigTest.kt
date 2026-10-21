package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.config.ConfigKey

/**
 * 哔哩轻小说：UA 不再内部隐藏，来源设置里必须可见，且默认仍是能通过站点新版 Chrome 判定的移动端 UA。
 */
class BilinovelUaConfigTest {

    private val parser = Bilinovel(ContentLoaderContextMock)

    @Test
    fun `user agent is exposed in source settings`() {
        val keys = mutableListOf<ConfigKey<*>>()
        parser.onCreateConfig(keys)

        val uaKey = keys.filterIsInstance<ConfigKey.UserAgent>().singleOrNull()
        assertNotNull(uaKey, "哔哩轻小说应暴露 UA 设置项: ${keys.map { it.key }}")
        assertTrue(uaKey!!.defaultValue.contains("Chrome/155"))
        assertTrue(uaKey.defaultValue.contains("Mobile"))
    }

    @Test
    fun `list requests use the configured user agent`() {
        val headers = parser.getRequestHeaders()

        assertTrue(headers["User-Agent"]!!.contains("Chrome/155"))
        assertEquals("https://www.bilinovel.com/", headers["Referer"])
    }
}
