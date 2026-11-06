package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

// 樱花动漫在线验证（默认跳过）：YHDM_INTEGRATION_TEST=1。
// 覆盖分类页翻页去重、搜索、详情分集顺序、播放页 m3u8 直链可访问性。
@EnabledIfEnvironmentVariable(named = "YHDM_INTEGRATION_TEST", matches = "1")
class YhdmIntegrationTest {

    private val parser = Yhdm(ContentLoaderContextMock)

    /** 站群偶尔会连不上（connect timed out），在线验证时重试几次，避免把网络抖动当成解析失败。 */
    private suspend fun <T> retry(times: Int = 3, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                kotlinx.coroutines.delay(1_000L * (attempt + 1))
            }
        }
        throw lastError ?: error("retry failed")
    }

    @Test
    fun exploreListDetailsAndPlayback() = runBlocking {
        val first = retry { parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()) }
        check(first.isNotEmpty()) { "分类列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().publicUrl.startsWith("https://")) { "详情地址异常: ${first.first().publicUrl}" }

        val second = retry { parser.getListPage(2, SortOrder.UPDATED, ContentListFilter()) }
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) {
            "第 2 页与第 1 页重复"
        }

        val latest = retry { parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = emptySet())) }
        check(latest.isNotEmpty()) { "最新页为空" }

        val search = retry { parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "进击")) }
        check(search.isNotEmpty()) { "搜索结果为空" }

        val details = retry { parser.getDetails(first.first()) }
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "分集为空: ${details.url}" }
        check(chapters.first().number < chapters.last().number) { "分集不是正序" }

        val pages = retry { parser.getPages(chapters.first()) }
        check(pages.isNotEmpty()) { "播放页没有返回地址: ${chapters.first().url}" }
        val videoUrl = parser.getPageUrl(pages.first())
        check(videoUrl.contains(".m3u8")) { "播放地址不是 m3u8: $videoUrl" }
        val response = ContentLoaderContextMock.httpClient.newCall(
            Request.Builder().url(videoUrl).head().build(),
        ).await()
        response.use {
            check(it.isSuccessful) { "播放地址不可访问 $videoUrl: HTTP ${it.code}" }
            val type = it.header("Content-Type").orEmpty()
            check(type.contains("mpegurl")) { "播放地址类型异常 $videoUrl: $type" }
        }
    }
}
