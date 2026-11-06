package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 电影88 在线验证（默认跳过）：DIANYING88_INTEGRATION_TEST=1。
 *
 * 重点验证 2026-11-06 实测出来的链路：
 * - 列表 20 部/页，`?pg=2` 必须换一批（不是同一页）；
 * - 详情能从 RSC 数据里分出「免费 HLS 线路」与「正版跳转线路」，章节只保留前者；
 * - 免费线路的 m3u8 直链 GET 200 + `#EXTM3U`（这些图床对 HEAD 返回 5xx，必须 GET）；
 * - 只有正版跳转的片，点播时要报「正版平台跳转」而不是给出假地址。
 */
@EnabledIfEnvironmentVariable(named = "DIANYING88_INTEGRATION_TEST", matches = "1")
class Dianying88IntegrationTest {

    private val parser = Dianying88(ContentLoaderContextMock)

    private val tvChannel = ContentListFilter(tags = setOf(ContentTag("电视剧", "type/37", parser.source)))

    @Test
    fun listPagesAdvanceAndSearchWorks() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, tvChannel)
        val second = parser.getListPage(2, SortOrder.UPDATED, tvChannel)
        check(first.size >= 15) { "列表数量异常: ${first.size}" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        val overlap = first.map { it.url }.intersect(second.map { it.url }.toSet())
        check(overlap.size < first.size / 2) { "第 2 页与第 1 页大量重复（$overlap）" }
        println("[DIANYING88] list p1=${first.size} p2=${second.size} overlap=${overlap.size}")

        val results = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "罗小黑"))
        check(results.isNotEmpty()) { "搜索「罗小黑」没有结果" }
        check(results.all { it.title.isNotBlank() && it.url.startsWith("/vod/") }) { "搜索结果字段缺失" }
        println("[DIANYING88] search='罗小黑' hits=${results.size} first=${results.first().title}")
    }

    @Test
    fun freeLinePlaysAndOfficialLineExplains() = runBlocking {
        val candidates = parser.getListPage(1, SortOrder.UPDATED, tvChannel)
        var freeTitle: Content? = null
        var details = parser.getDetails(candidates.first())
        var checked = 0
        for (item in candidates) {
            details = parser.getDetails(item)
            checked++
            val chapters = details.chapters.orEmpty()
            if (chapters.isNotEmpty() && chapters.none { it.title.orEmpty().startsWith("仅正版跳转") }) {
                freeTitle = item
                break
            }
            if (checked >= 6) break
        }
        check(freeTitle != null) { "前 $checked 部电视剧都没有免费线路，站点结构可能变了" }
        val chapters = details.chapters.orEmpty()
        val branches = chapters.mapNotNull { it.branch }.distinct()
        println("[DIANYING88] ${details.title} chapters=${chapters.size} branches=$branches")

        var playable = 0
        for (chapter in chapters.take(2)) {
            val pages = parser.getPages(chapter)
            check(pages.size == 1) { "章节应返回单页: ${chapter.url}" }
            val videoUrl = pages.single().url
            check(videoUrl.startsWith("http")) { "播放地址不是绝对地址: $videoUrl" }
            // 图床对 HEAD 一律 5xx，用 GET 取前 64 字节判断 HLS 清单
            val response = ContentLoaderContextMock.httpClient.newCall(
                Request.Builder().url(videoUrl).get().build(),
            ).await()
            response.use {
                check(it.isSuccessful) { "播放地址不可访问 $videoUrl: HTTP ${it.code}" }
                val head = it.body?.source()?.readUtf8(64).orEmpty()
                check(head.startsWith("#EXTM3U")) { "播放地址不是 HLS 清单: ${head.take(32)} $videoUrl" }
            }
            playable++
            println("[DIANYING88] play ok ${chapter.title} → $videoUrl")
        }
        check(playable > 0) { "没有取到可播放的 m3u8" }

        // 正版跳转线路：构造一条外站章节，必须报可操作的错误
        val official = org.skepsun.kototoro.parsers.model.ContentChapter(
            id = 1L,
            title = "仅正版跳转：芒果TV",
            number = 1f,
            volume = 0,
            url = "https://www.mgtv.com/b/908876/24675446.html",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = parser.source,
        )
        val error = runCatching { parser.getPages(official) }.exceptionOrNull()
        check(error is ParseException) { "正版跳转线路应抛 ParseException，实际: $error" }
        check(error.message.orEmpty().contains("正版平台跳转")) { "报错文案没有说清原因: ${error.message}" }
        println("[DIANYING88] official line → ${error.message}")
    }
}
