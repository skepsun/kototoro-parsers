package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.ContentLoaderContextIsolated
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.util.getCookies

/**
 * Picacg 凭证登录集成测试：不依赖预置 token，直接使用邮箱/密码登录并获取用户名。
 *
 * 运行示例：
 * PICACG_EMAIL="your_email" PICACG_PASSWORD="your_password" ./gradlew :kototoro-parsers:test --tests "org.skepsun.kototoro.parsers.site.zh.PicacgCredentialLoginTest"
 */
class PicacgCredentialLoginTest {

    private val context = ContentLoaderContextIsolated

    @Test
    fun login_with_credentials_and_fetch_username() = runTest(timeout = 2.minutes) {
        // 支持用户名环境变量：优先 PICACG_ACCOUNT，其次回退到 PICACG_EMAIL
        val accountOrEmail = (System.getenv("PICACG_ACCOUNT") ?: System.getenv("PICACG_EMAIL"))?.trim().orEmpty()
        val password = System.getenv("PICACG_PASSWORD")?.trim().orEmpty()
        assumeTrue(accountOrEmail.isNotBlank() && password.isNotBlank(), "Missing PICACG_ACCOUNT/PICACG_EMAIL or PICACG_PASSWORD, skipping")

        val parser = context.newParserInstance(ContentParserSource.PICACG)
        val domain = parser.domain
        println("[TEST] PICACG domain=$domain")
        println("[TEST] Cookies before login on $domain: " + context.cookieJar.getCookies(domain).map { it.name })

        val authProviderRaw = parser.authorizationProvider
        val credentials = authProviderRaw as? ContentParserCredentialsAuthProvider
            ?: error("Parser does not support credentials auth")

        val ok = credentials.login(accountOrEmail, password)
        assertTrue(ok, "登录方法返回 false")

        val authProvider = parser.authorizationProvider as ContentParserAuthProvider
        assertTrue(authProvider.isAuthorized(), "登录后 isAuthorized=false")

        val username = authProvider.getUsername()
        assertTrue(username.isNotBlank(), "登录后无法获取用户名")

        println("[TEST] Username after login: $username")
    }
}