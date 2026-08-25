package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.CloudFlareProtectedException
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 在线验证 nhentai：被 Cloudflare 挑战时解析器应报出挑战（触发浏览器验证），
 * 而不是静默返回空列表；未被挑战时应返回真实列表。
 * 启用方式: 设置环境变量 NHENTAI_INTEGRATION_TEST=1；
 * 代理通过 HTTPS_PROXY/HTTP_PROXY/ALL_PROXY 环境变量生效。
 */
@EnabledIfEnvironmentVariable(named = "NHENTAI_INTEGRATION_TEST", matches = "1")
class NhentaiParserIntegrationTest {

    private val parser = NhentaiParser(ContentLoaderContextMock)

    @Test
    fun `list either returns content or reports cloudflare challenge`() = runBlocking {
        try {
            val list = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter())
            assertTrue(list.isNotEmpty(), "未触发挑战但列表为空")
            println("NHENTAI: list=${list.size} first=${list.firstOrNull()?.title?.take(50)}")
        } catch (e: CloudFlareProtectedException) {
            // 被 Cloudflare challenge 拦截：解析器会触发 requestBrowserAction 让用户验证
            println("NHENTAI: cloudflare challenge detected, browser action required: ${e.url}")
        }
    }
}
