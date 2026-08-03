package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.util.getCookies

class CopyContentAuthTest {

    private val context = ContentLoaderContextMock
    private val username = "skepsun"
    private val password = "js930828"

    @Test
    fun login_and_authorized_with_token_cookie() = runTest(timeout = 2.minutes) {
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val authProviderRaw = parser.authorizationProvider
        println("Auth provider class = ${authProviderRaw?.javaClass?.name}")
        println("Auth provider is credentials = ${authProviderRaw is ContentParserCredentialsAuthProvider}")
        val credentials = authProviderRaw as? ContentParserCredentialsAuthProvider
            ?: error("Parser does not support credentials auth")
        val tokenPreset = System.getenv("COPYMANGA_TOKEN")?.isNotBlank() == true
        val ok = if (!tokenPreset) credentials.login(username, password) else true
        assertTrue(ok, "登录返回失败（可能服务端 500 或参数校验失败；若已通过 COPYMANGA_TOKEN 预置，则忽略该断言）")

        // 授权状态应为真
        val authProvider = parser.authorizationProvider as ContentParserAuthProvider
        val isAuth = authProvider.isAuthorized()
        assertTrue(isAuth, "登录后 isAuthorized=false")

        // Cookie 中应存在 token/authorization 于 API 域
        val cookies = context.cookieJar.getCookies("api.copy3000.com")
        val tokenCookie = cookies.find { it.name.equals("token", true) || it.name.equals("authorization", true) }
        assertTrue(tokenCookie != null && tokenCookie!!.value.isNotBlank(), "API 域未发现有效 token/authorization Cookie")
    }

    @Test
    fun username_fetch_after_login() = runTest(timeout = 2.minutes) {
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val authProviderRaw = parser.authorizationProvider
        println("Auth provider class = ${authProviderRaw?.javaClass?.name}")
        println("Auth provider is credentials = ${authProviderRaw is ContentParserCredentialsAuthProvider}")
        val credentials = authProviderRaw as? ContentParserCredentialsAuthProvider
            ?: error("Parser does not support credentials auth")

        val tokenPreset = System.getenv("COPYMANGA_TOKEN")?.isNotBlank() == true
        val ok = if (!tokenPreset) credentials.login(username, password) else true
        assertTrue(ok, "登录返回失败（可能服务端 500 或参数校验失败；若已通过 COPYMANGA_TOKEN 预置，则忽略该断言）")

        val authProvider = parser.authorizationProvider as ContentParserAuthProvider
        val isAuth = authProvider.isAuthorized()
        assertTrue(isAuth, "登录后 isAuthorized=false")

        // 获取用户名：应返回非空字符串
        val name = authProvider.getUsername()
        assertTrue(name.isNotBlank(), "返回用户名为空字符串")
    }
}
