package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * rawkuma 的离线回归覆盖。
 *
 * 夹具为 2026-11-13 的线上快照；条目/章节这类重复块已裁剪到 3 份，图片地址等字段保持原样。
 * 线上连通性冒烟见 [RawkumaIntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"。
 */
class RawkumaTest {

    private companion object {
        const val LIST_URL =
            "https://rawkuma.net/wp-json/wp/v2/manga?page=1&per_page=24&orderby=date&order=desc&_embed"
        const val DETAILS_URL =
            "https://rawkuma.net/wp-json/wp/v2/manga?slug=aru-mono-kara-no-irai-x&_embed"
        const val CHAPTERS_P1_URL =
            "https://rawkuma.net/wp-admin/admin-ajax.php?manga_id=434898&page=1&action=chapter_list"
        const val CHAPTERS_P2_URL =
            "https://rawkuma.net/wp-admin/admin-ajax.php?manga_id=434898&page=2&action=chapter_list"
        const val READER_URL =
            "https://rawkuma.net/manga/aru-mono-kara-no-irai-x/chapter-1.434907/"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "rawkuma/list.json",
            DETAILS_URL to "rawkuma/details.json",
            CHAPTERS_P1_URL to "rawkuma/chapters_1.html",
            CHAPTERS_P2_URL to "rawkuma/chapters_2.html",
            READER_URL to "rawkuma/reader.html",
        ),
        responseTypes = mapOf(
            LIST_URL to "application/json; charset=utf-8",
            DETAILS_URL to "application/json; charset=utf-8",
            CHAPTERS_P1_URL to "text/html; charset=utf-8",
            CHAPTERS_P2_URL to "text/html; charset=utf-8",
            READER_URL to "text/html; charset=utf-8",
        ),
    )

    private val parser = Rawkuma(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "aru-mono-kara-no-irai-x" in it.url }

        assertEquals(3, list.size)
        assertEquals("/manga/aru-mono-kara-no-irai-x/", item.url)
        assertTrue(item.title.contains("Aru Mono"), "标题解析错误: ${item.title}")
        assertTrue(item.coverUrl.orEmpty().startsWith("http"), "封面应解析成绝对地址")
        assertTrue(list.all { it.source == parser.source }, "条目来源应统一")
    }

    @Test
    fun `details expose chapters`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "aru-mono-kara-no-irai-x" in it.url }

        val details = parser.getDetails(item)
        val chapters = details.chapters.orEmpty()
        assertEquals(1, chapters.size)
        assertTrue(chapters.any { it.url == "/manga/aru-mono-kara-no-irai-x/chapter-1.434907/" }, "缺少目标章节 ${chapters.take(3).map { it.url }}")
    }

    @Test
    fun `pages resolve to absolute urls`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "aru-mono-kara-no-irai-x" in it.url }
        val chapter = parser.getDetails(item).chapters!!.first { "434907" in it.url }

        val pages = parser.getPages(chapter)
        assertEquals(69, pages.size)
        assertTrue(pages.first().url.startsWith("https://cdn.rawkuma.com/a/"), "首图地址异常: ${pages.first().url}")
        assertTrue(pages.all { it.url.endsWith(".webp") }, "图片地址应指向 cdn 的 webp 资源")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()))
    }
}
