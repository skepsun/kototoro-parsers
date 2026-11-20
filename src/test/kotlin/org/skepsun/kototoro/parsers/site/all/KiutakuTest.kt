package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * kiutaku 的离线回归覆盖。
 *
 * 夹具为 2026-11-13 的线上快照；条目/章节这类重复块已裁剪到 3 份，图片地址等字段保持原样。
 * 线上连通性冒烟见 [KiutakuIntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"。
 */
class KiutakuTest {

    private companion object {
        const val LIST_URL =
            "https://kiutaku.com/"
        const val DETAILS_URL =
            "https://kiutaku.com/7440"
        const val READER_P2_URL =
            "https://kiutaku.com/7440?page=2"
        const val READER_P3_URL =
            "https://kiutaku.com/7440?page=3"
        const val READER_P4_URL =
            "https://kiutaku.com/7440?page=4"
        const val READER_P5_URL =
            "https://kiutaku.com/7440?page=5"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "kiutaku/list.html",
            DETAILS_URL to "kiutaku/details.html",
            READER_P2_URL to "kiutaku/reader_p2.html",
            READER_P3_URL to "kiutaku/reader_p3.html",
            READER_P4_URL to "kiutaku/reader_p4.html",
            READER_P5_URL to "kiutaku/reader_p5.html",
        ),
        responseTypes = mapOf(
            LIST_URL to "text/html; charset=utf-8",
            DETAILS_URL to "text/html; charset=utf-8",
            READER_P2_URL to "text/html; charset=utf-8",
            READER_P3_URL to "text/html; charset=utf-8",
            READER_P4_URL to "text/html; charset=utf-8",
            READER_P5_URL to "text/html; charset=utf-8",
        ),
    )

    private val parser = Kiutaku(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "/7440" in it.url }

        assertEquals(20, list.size)
        assertEquals("/7440", item.url)
        assertTrue(item.title.contains("Machi"), "标题解析错误: ${item.title}")
        assertTrue(item.coverUrl.orEmpty().startsWith("http"), "封面应解析成绝对地址")
        assertTrue(list.all { it.source == parser.source }, "条目来源应统一")
    }

    @Test
    fun `details expose chapters`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "/7440" in it.url }

        val details = parser.getDetails(item)
        val chapters = details.chapters.orEmpty()
        assertEquals(1, chapters.size)
        assertTrue(chapters.any { it.url == "/7440" }, "缺少目标章节 ${chapters.take(3).map { it.url }}")
    }

    @Test
    fun `pages resolve to absolute urls`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "/7440" in it.url }
        val chapter = parser.getDetails(item).chapters!!.first { "7440" in it.url }

        val pages = parser.getPages(chapter)
        assertEquals(99, pages.size)
        assertTrue(pages.first().url.startsWith("https://mitaku.net/wp-content/uploads/"), "首图地址异常: ${pages.first().url}")
        assertTrue(pages.all { it.url.startsWith("https://") }, "图片应解析成绝对地址")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()))
    }
}
