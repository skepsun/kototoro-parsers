package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await
import java.io.IOException

/**
 * AGE动漫在线验证（默认跳过）：AGEDM_INTEGRATION_TEST=1。
 *
 * 重点验证 2026-11-06 改写的播放链路：
 * - m3u8 线路要能解析出 `#EXTM3U` 直链（这些图床对 HEAD 一律 502，必须用 GET 校验）；
 * - 西瓜线路必须给出「请切换线路」的 ParseException，而不是把密文当播放地址；
 * - 单条线路的 CDN 域名被墙/超时属于网络环境问题，只记账不算失败，但至少要有线路可播且不算失败。
 */
@EnabledIfEnvironmentVariable(named = "AGEDM_INTEGRATION_TEST", matches = "1")
class AgeIntegrationTest {

    private val parser = Age(ContentLoaderContextMock)

    private sealed interface LineResult {
        data class Playable(val url: String) : LineResult
        data class JsOnly(val message: String) : LineResult
        data class Unreachable(val reason: String) : LineResult
    }

    @Test
    fun listDetailsAndM3u8Playback() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "首页列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "剧集为空: ${details.url}" }
        val branches = chapters.mapNotNull { it.branch }.distinct()
        println("[AGEDM] ${details.title} chapters=${chapters.size} branches=$branches")

        val results = LinkedHashMap<String, LineResult>()
        for (branch in branches) {
            results[branch] = probeLine(chapters.first { it.branch == branch })
        }

        results.forEach { (branch, result) ->
            val text = when (result) {
                is LineResult.Playable -> "ok ${result.url}"
                is LineResult.JsOnly -> "预期不支持（${result.message}）"
                is LineResult.Unreachable -> "CDN 不可达（${result.reason}）"
            }
            println("[AGEDM] $branch → $text")
        }

        val playable = results.values.filterIsInstance<LineResult.Playable>()
        check(playable.isNotEmpty()) { "没有任何线路返回可播放的 m3u8：$results" }
        check(results.values.any { it is LineResult.JsOnly }) { "西瓜线路应当给出「请切换线路」的报错" }
        println("[AGEDM] done: playable=${playable.size}/${branches.size}, sample=${playable.first().url}")
    }

    private suspend fun probeLine(chapter: ContentChapter): LineResult {
        val pages = try {
            parser.getPages(chapter)
        } catch (e: ParseException) {
            val message = e.message.orEmpty()
            check(message.contains("线路")) { "报错没有提示切换线路: $message" }
            return LineResult.JsOnly(message.substringBefore(" at "))
        }

        val videoUrl = parser.getPageUrl(pages.single())
        check(videoUrl.contains(".m3u8")) { "${chapter.branch} 线路不是 m3u8 直链: $videoUrl" }
        // 这些图床对 HEAD 一律 502（实测），必须用 GET 校验
        return try {
            val response = ContentLoaderContextMock.httpClient.newCall(
                Request.Builder().url(videoUrl).get().build(),
            ).await()
            response.use {
                check(it.isSuccessful) { "${chapter.branch} 播放地址不可访问 $videoUrl: HTTP ${it.code}" }
                val type = it.header("Content-Type").orEmpty()
                check(type.contains("mpegurl") || type.contains("video")) {
                    "${chapter.branch} 播放地址类型异常 $videoUrl: $type"
                }
                val head = it.body?.source()?.readUtf8(64).orEmpty()
                check(head.startsWith("#EXTM3U")) { "${chapter.branch} 播放地址不是 HLS 清单: ${head.take(32)}" }
            }
            LineResult.Playable(videoUrl)
        } catch (e: IOException) {
            LineResult.Unreachable("${e::class.simpleName}: ${e.message}")
        }
    }

    @Test
    fun searchReturnsParsedItems() = runBlocking {
        val results = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "圣女"))
        check(results.isNotEmpty()) { "搜索「圣女」没有结果" }
        check(results.first().title.isNotBlank()) { "搜索结果标题为空" }
        println("[AGEDM] search='圣女' first=${results.first().title}")
    }
}
