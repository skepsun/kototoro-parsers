package org.skepsun.kototoro.parsers

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.parsers.util.await
import java.io.IOException

/**
 * 离线护栏自测：保证 `ContentLoaderContextMock` 默认不会把普通测试放到线上跑。
 *
 * 这两条用例自身不发真实请求——一条断言护栏抛错，一条断言回环地址被放行。
 * 一旦有人设置了 `<SOURCE>_INTEGRATION_TEST=1`，护栏按设计关闭，用例自动跳过。
 */
class OfflineNetworkGuardTest {

    private fun assumeGuardEnabled() {
        assumeTrue(ContentLoaderContextMock.isOfflineGuardEnabled, "已开启线上验证，离线护栏按设计关闭")
    }

    private fun call(url: String): IOException = assertThrows<IOException> {
        runBlocking {
            ContentLoaderContextMock.httpClient
                .newCall(Request.Builder().url(url).build())
                .await()
        }
    }

    @Test
    fun `普通测试里真实出网会被明确拦下`() {
        assumeGuardEnabled()
        val error = call("https://example.com/offline-guard-probe")
        assertTrue(
            error.message.orEmpty().contains("离线测试禁止真实网络请求"),
            "错误信息应指出这是测试联网而非普通网络故障，实际: ${error.message}",
        )
    }

    @Test
    fun `回环地址不受护栏影响`() {
        assumeGuardEnabled()
        // 127.0.0.1:1 必然立即拒绝连接；关键是异常不是护栏抛出的，说明本地 mock server 仍可用
        val error = call("http://127.0.0.1:1/probe")
        assertFalse(
            error.message.orEmpty().contains("离线测试禁止真实网络请求"),
            "回环地址不应被离线护栏拦截",
        )
    }
}
