package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.util.getCookies

/**
 * 拷贝漫画登录在线验证（默认跳过）：COPYMANGA_INTEGRATION_TEST=1。
 *
 * 凭据只从环境变量读取，不写进仓库：COPYMANGA_USERNAME / COPYMANGA_PASSWORD，
 * 或预置已登录的 COPYMANGA_TOKEN 跳过登录步骤。三者都没有时整条用例中止（Assumptions.abort），
 * 而不是失败——缺凭据是环境问题，不是解析器回归。
 */
@EnabledIfEnvironmentVariable(named = "COPYMANGA_INTEGRATION_TEST", matches = "1")
class CopyContentAuthIntegrationTest {

    private val context = ContentLoaderContextMock
    private val username = System.getenv("COPYMANGA_USERNAME").orEmpty()
    private val password = System.getenv("COPYMANGA_PASSWORD").orEmpty()
    private val tokenPreset = System.getenv("COPYMANGA_TOKEN")?.isNotBlank() == true

    private fun requireCredentials() {
        Assumptions.assumeTrue(
            tokenPreset || (username.isNotBlank() && password.isNotBlank()),
            "需要 COPYMANGA_USERNAME/COPYMANGA_PASSWORD，或预置 COPYMANGA_TOKEN",
        )
    }

    @Test
    fun login_and_authorized_with_token_cookie() = runTest(timeout = 2.minutes) {
        requireCredentials()
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val credentials = parser.authorizationProvider as? ContentParserCredentialsAuthProvider
            ?: error("Parser does not support credentials auth")
        val ok = tokenPreset || credentials.login(username, password)
        assertTrue(ok, "登录返回失败（可能服务端 500 或参数校验失败）")

        // 授权状态应为真
        val authProvider = parser.authorizationProvider as ContentParserAuthProvider
        assertTrue(authProvider.isAuthorized(), "登录后 isAuthorized=false")

        // Cookie 中应存在 token/authorization 于 API 域
        val cookies = context.cookieJar.getCookies("api.copy3000.com")
        val tokenCookie = cookies.find { it.name.equals("token", true) || it.name.equals("authorization", true) }
        assertTrue(tokenCookie != null && tokenCookie.value.isNotBlank(), "API 域未发现有效 token/authorization Cookie")
    }

    @Test
    fun username_fetch_after_login() = runTest(timeout = 2.minutes) {
        requireCredentials()
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val credentials = parser.authorizationProvider as? ContentParserCredentialsAuthProvider
            ?: error("Parser does not support credentials auth")

        val ok = tokenPreset || credentials.login(username, password)
        assertTrue(ok, "登录返回失败（可能服务端 500 或参数校验失败）")

        val authProvider = parser.authorizationProvider as ContentParserAuthProvider
        assertTrue(authProvider.isAuthorized(), "登录后 isAuthorized=false")

        // 获取用户名：应返回非空字符串
        assertTrue(authProvider.getUsername().isNotBlank(), "返回用户名为空字符串")
    }
}
