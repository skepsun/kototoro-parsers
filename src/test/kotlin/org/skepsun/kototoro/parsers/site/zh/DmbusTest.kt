package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * dmbus 的离线回归覆盖。
 *
 * 夹具为 2026-11-13 的线上快照；条目/章节这类重复块已裁剪到 3 份，图片地址等字段保持原样。
 * 线上连通性冒烟见 [DmbusIntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"。
 */
class DmbusTest {

    private companion object {
        const val LIST_URL =
            "https://dmbus.cc/show-1--time----.html"
        const val DETAILS_URL =
            "https://dmbus.cc/v/6340.html"
        const val READER_URL =
            "https://dmbus.cc/p/6340-1-1.html"
        const val PLAYER_URL =
            "https://hhjx.hhplayer.com/?url=47E3D95BF2A2FC9124C1140E9A476DD02EF454C0DF9147EF69A3D125D25C14F6CC79B22964055DB8A8646B0B808CD5DB"
        const val PARSE_URL =
            "https://hhjx.hhplayer.com/api/parse"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "dmbus/list.html",
            DETAILS_URL to "dmbus/details.html",
            READER_URL to "dmbus/reader.html",
            PLAYER_URL to "dmbus/player.html",
            PARSE_URL to "dmbus/parse.json",
        ),
        responseTypes = mapOf(
            LIST_URL to "text/html; charset=utf-8",
            DETAILS_URL to "text/html; charset=utf-8",
            READER_URL to "text/html; charset=utf-8",
            PLAYER_URL to "text/html; charset=utf-8",
            PARSE_URL to "application/json; charset=utf-8",
        ),
    )

    private val parser = Dmbus(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "6340" in it.url }

        assertEquals(36, list.size)
        assertEquals("/v/6340.html", item.url)
        assertTrue(item.title.contains("宗门修仙传"), "标题解析错误: ${item.title}")
        assertTrue(item.coverUrl.orEmpty().startsWith("http"), "封面应解析成绝对地址")
        assertTrue(list.all { it.source == parser.source }, "条目来源应统一")
    }

    @Test
    fun `details expose chapters`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "6340" in it.url }

        val details = parser.getDetails(item)
        val chapters = details.chapters.orEmpty()
        assertEquals(12, chapters.size)
        assertTrue(chapters.any { it.url == "/p/6340-1-1.html" }, "缺少目标章节 ${chapters.take(3).map { it.url }}")
    }

    @Test
    fun `pages resolve to absolute urls`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "6340" in it.url }
        val chapter = parser.getDetails(item).chapters!!.first { "6340-1-1" in it.url }

        val pages = parser.getPages(chapter)
        assertEquals(1, pages.size)
        assertTrue(pages.first().url.startsWith("http://hhjx.hhplayer.com/playlist/"), "首图地址异常: ${pages.first().url}")
        assertTrue(pages.first().url.contains(".m3u8?"), "播放页应解析成 m3u8 播放列表")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()))
    }
}
