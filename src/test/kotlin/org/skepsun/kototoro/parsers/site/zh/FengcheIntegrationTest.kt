package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 风车动漫在线验证（默认跳过）：FENGCHE_INTEGRATION_TEST=1。
 * 该站点只能走 http，顺带验证播放地址 HEAD 可访问。
 */
@EnabledIfEnvironmentVariable(named = "FENGCHE_INTEGRATION_TEST", matches = "1")
class FengcheIntegrationTest {

    private val parser = Fengche(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndPlayback() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "默认频道列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().publicUrl.startsWith("http://")) { "站点只能走 http: ${first.first().publicUrl}" }
        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "剧集为空: ${details.url}" }
        check(chapters.first().number >= 1f) { "剧集序号异常: ${chapters.first().number}" }

        val pages = parser.getPages(chapters.first())
        val videoUrl = parser.getPageUrl(pages.single())
        check(videoUrl.contains("m3u8") || videoUrl.contains("mp4")) { "播放地址不是直链: $videoUrl" }
        val response = ContentLoaderContextMock.httpClient.newCall(
            Request.Builder().url(videoUrl).head().build(),
        ).await()
        response.use {
            check(it.isSuccessful) { "播放地址不可访问 $videoUrl: HTTP ${it.code}" }
            val type = it.header("Content-Type").orEmpty()
            check(type.contains("mpegurl") || type.contains("video")) { "播放地址类型异常 $videoUrl: $type" }
        }
        println("[FENGCHE] ok: ${details.title}, chapters=${chapters.size}, m3u8=$videoUrl")
    }

    @Test
    fun searchThroughCookieWorks() = runBlocking {
        val results = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "吞噬"))
        check(results.isNotEmpty()) { "搜索「吞噬」没有结果" }
        check(results.any { it.title.contains("吞噬") }) { "搜索结果与关键字无关: ${results.take(3).map { it.title }}" }
        check(parser.getListPage(2, SortOrder.UPDATED, ContentListFilter(query = "吞噬")).isEmpty()) {
            "搜索结果页不分页，第 2 页应为空"
        }
        println("[FENGCHE] search=${results.size}, first=${results.first().title}")
    }
}
