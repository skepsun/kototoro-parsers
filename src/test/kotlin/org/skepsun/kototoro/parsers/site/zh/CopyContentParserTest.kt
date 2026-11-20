package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * copymanga 的离线回归覆盖。
 *
 * 夹具为 2026-11-13 的线上快照；条目/章节这类重复块已裁剪到 3 份，图片地址等字段保持原样。
 * 线上连通性冒烟见 [CopyMangaImageLoadIntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"。
 */
class CopyContentParserTest {

    private companion object {
        const val LIST_URL =
            "https://api.copy3000.com/api/v3/comics?limit=30&offset=0&ordering=-datetime_updated&theme=&top=-%E5%85%A8%E9%83%A8&_update=true&free_type=1"
        const val DETAILS_URL =
            "https://api.copy3000.com/api/v3/comic2/snqjsbfcymdsqdwg"
        const val CHAPTERS_URL =
            "https://api.copy3000.com/api/v3/comic/snqjsbfcymdsqdwg/group/default/chapters?limit=100&offset=0&_update=true"
        const val PAGES_URL =
            "https://api.copy3000.com/api/v3/comic/snqjsbfcymdsqdwg/chapter2/72e2a482-e136-11f0-8923-fa163e4baef8"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "copymanga/list.json",
            DETAILS_URL to "copymanga/details.json",
            CHAPTERS_URL to "copymanga/chapters.json",
            PAGES_URL to "copymanga/pages.json",
        ),
        responseTypes = mapOf(
            LIST_URL to "application/json; charset=utf-8",
            DETAILS_URL to "application/json; charset=utf-8",
            CHAPTERS_URL to "application/json; charset=utf-8",
            PAGES_URL to "application/json; charset=utf-8",
        ),
    )

    private val parser = CopyContentParser(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "snqjsbfcymdsqdwg" in it.url }

        assertEquals(3, list.size)
        assertEquals("snqjsbfcymdsqdwg", item.url)
        assertTrue(item.title.contains("暴怒千金"), "标题解析错误: ${item.title}")
        assertTrue(item.coverUrl.orEmpty().startsWith("http"), "封面应解析成绝对地址")
        assertTrue(list.all { it.source == parser.source }, "条目来源应统一")
    }

    @Test
    fun `details expose chapters`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "snqjsbfcymdsqdwg" in it.url }

        val details = parser.getDetails(item)
        val chapters = details.chapters.orEmpty()
        assertEquals(3, chapters.size)
        assertTrue(chapters.any { it.url == "snqjsbfcymdsqdwg/72e2a482-e136-11f0-8923-fa163e4baef8" }, "缺少目标章节 ${chapters.take(3).map { it.url }}")
    }

    @Test
    fun `pages resolve to absolute urls`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "snqjsbfcymdsqdwg" in it.url }
        val chapter = parser.getDetails(item).chapters!!.first { "72e2a482" in it.url }

        val pages = parser.getPages(chapter)
        assertEquals(3, pages.size)
        assertTrue(pages.first().url.startsWith("https://ss.mangafunb.fun/"), "首图地址异常: ${pages.first().url}")
        assertTrue(pages.first().url.endsWith(".webp"), "图片应走 webp 转码")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()))
    }
}
