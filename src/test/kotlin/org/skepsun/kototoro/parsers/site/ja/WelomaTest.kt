package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * weloma 的离线回归覆盖。
 *
 * 夹具为 2026-11-13 的线上快照；条目/章节这类重复块已裁剪到 3 份，图片地址等字段保持原样。
 * 线上连通性冒烟见 [WelomaIntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"。
 */
class WelomaTest {

    private companion object {
        const val LIST_URL =
            "https://weloma.net/l/0OYCn?sort=last_update&page=1"
        const val DETAILS_URL =
            "https://weloma.net/m/0IlfQ"
        const val READER_URL =
            "https://weloma.net/c/q6zTw"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "weloma/list.html",
            DETAILS_URL to "weloma/details.html",
            READER_URL to "weloma/reader.html",
        ),
        responseTypes = mapOf(
            LIST_URL to "text/html; charset=utf-8",
            DETAILS_URL to "text/html; charset=utf-8",
            READER_URL to "text/html; charset=utf-8",
        ),
    )

    private val parser = Weloma(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "0IlfQ" in it.url }

        assertEquals(20, list.size)
        assertEquals("/m/0IlfQ", item.url)
        assertTrue(item.title.contains("momban Yattero"), "标题解析错误: ${item.title}")
        assertTrue(item.coverUrl.orEmpty().startsWith("http"), "封面应解析成绝对地址")
        assertTrue(list.all { it.source == parser.source }, "条目来源应统一")
    }

    @Test
    fun `details expose chapters`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "0IlfQ" in it.url }

        val details = parser.getDetails(item)
        val chapters = details.chapters.orEmpty()
        assertEquals(32, chapters.size)
        assertTrue(chapters.any { it.url == "/c/q6zTw" }, "缺少目标章节 ${chapters.take(3).map { it.url }}")
    }

    @Test
    fun `pages resolve to absolute urls`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "0IlfQ" in it.url }
        val chapter = parser.getDetails(item).chapters!!.first { "q6zTw" in it.url }

        val pages = parser.getPages(chapter)
        assertEquals(46, pages.size)
        assertTrue(pages.first().url.startsWith("https://ihlv1.xyz/"), "首图地址异常: ${pages.first().url}")
        assertTrue(pages.all { it.url.startsWith("https://") }, "图片应解析成绝对地址")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()))
    }
}
