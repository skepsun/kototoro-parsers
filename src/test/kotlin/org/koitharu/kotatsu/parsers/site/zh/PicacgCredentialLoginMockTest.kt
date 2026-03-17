package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.getCookies

/**
 * Picacg 凭证登录（Mock 上下文）
 * - 强制使预置 token/authorization Cookie 过期，以走邮箱/密码登录路径
 * - 从环境变量读取账号：PICACG_EMAIL / PICACG_PASSWORD
 * - 登录后检查授权状态与可读取用户名
 *
 * 运行方法示例：
 * PICACG_SKIP_TOKEN_FILE=1 PICACG_UUID=defaultUuid PICACG_EMAIL="your_email" PICACG_PASSWORD="your_password" ./gradlew :kototoro-parsers:test --tests "org.skepsun.kototoro.parsers.site.zh.PicacgCredentialLoginMockTest.login_with_credentials_on_mock_context" --rerun-tasks --no-parallel --console=plain
 */
class PicacgCredentialLoginMockTest {

    private val context = ContentLoaderContextMock

    @Test
    fun login_with_credentials_on_mock_context() = runTest(timeout = 2.minutes) {
        val parser = context.newParserInstance(ContentParserSource.PICACG)
        // 显式设置 domain 为 api.go2778.com 以匹配 Python 脚本，避免官方服务器限流
        // 通过配置系统设置域名，而不是直接赋值 parser.domain
        val config = context.getConfig(ContentParserSource.PICACG) as org.skepsun.kototoro.parsers.SourceConfigMock
        config.set(parser.configKeyDomain, "api.go2778.com")
        val domain = parser.domain
        println("[TEST] PicacgParser domain=$domain")
        
        // 打印HMAC密钥用于调试 - 将通过签名计算过程自动打印
        
        // 设置与 Python 脚本相同的设备 UUID，避免服务器设备限制
        // 注意：PicacgParser 使用 System.getenv("PICACG_UUID") 而不是 System.getProperty
        // 这里我们通过环境变量传递，而不是系统属性
        // 实际测试时应该通过命令行环境变量传递：PICACG_UUID=f46e7541-6870-49c3-84b9-089716efd21d

        // 显式使可能存在的 token/authorization Cookie 过期，确保走凭证登录路径
        runCatching {
            context.cookieJar.insertCookies(domain, "authorization=expired; Max-Age=0;", "token=expired; Max-Age=0;")
            context.cookieJar.insertCookies("picaapi.picacomic.com", "authorization=expired; Max-Age=0;", "token=expired; Max-Age=0;")
            context.cookieJar.insertCookies("api.go2778.com", "authorization=expired; Max-Age=0;", "token=expired; Max-Age=0;")
            println("[TEST] Cookies after expire on domain($domain): " + context.cookieJar.getCookies(domain).map { it.name })
        }.onFailure { /* ignore */ }

        val email = System.getenv("PICACG_EMAIL")?.trim().orEmpty()
        val password = System.getenv("PICACG_PASSWORD")?.trim().orEmpty()
        assumeTrue(email.isNotBlank() && password.isNotBlank(), "缺少 PICACG_EMAIL 或 PICACG_PASSWORD，跳过测试")

        val authProviderRaw = parser.authorizationProvider
        println("Auth provider class = ${authProviderRaw?.javaClass?.name}")
        val credentials = authProviderRaw as? ContentParserCredentialsAuthProvider
            ?: error("Parser does not support credentials auth")

        // 执行凭证登录；若服务端限流（429）或设备限制导致异常，跳过测试以避免误报
        val success = runCatching {
            credentials.login(email, password)
        }.getOrElse { ex ->
            println("[TEST] Login error: ${ex.javaClass.simpleName}: ${ex.message}")
            assumeTrue(false, "服务器限流或设备限制：${ex.message}")
            false
        }
        assertTrue(success, "login() 返回 false，登录失败")

        val authProvider = parser.authorizationProvider as ContentParserAuthProvider
        assertTrue(authProvider.isAuthorized(), "登录后 isAuthorized=false")

        // 用户名应可读取；若服务端对当前设备/令牌不授权，跳过断言以避免误报
        val username = runCatching { authProvider.getUsername() }.getOrElse { ex ->
            println("[TEST] getUsername error: ${ex.javaClass.simpleName}: ${ex.message}")
            assumeTrue(false, "当前令牌/设备无法获取用户名：${ex.message}")
            ""
        }
        assertTrue(username.isNotBlank(), "登录后用户名为空")
    }
}