package org.skepsun.kototoro.parsers.site.galleryadults.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * wnacg 的离线回归覆盖。
 *
 * 夹具为 2026-11-13 的线上快照；条目/章节这类重复块已裁剪到 3 份，图片地址等字段保持原样。
 * 线上连通性冒烟见 [WnacgReadIntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"。
 */
class WnacgParserOfflineTest {

    private companion object {
        const val LIST_URL =
            "https://www.wnacg.com/albums.html"
        const val DETAILS_URL =
            "https://www.wnacg.com/photos-index-page-1-aid-404199.html"
        const val READER_URL =
            "https://www.wnacg.com/photos-gallery-aid-404199.html"
        const val DOMAIN_URL =
            "https://wn01.link/"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "wnacg/list.html",
            DETAILS_URL to "wnacg/details.html",
            READER_URL to "wnacg/reader.html",
            DOMAIN_URL to "wnacg/domain_page.html",
        ),
        responseTypes = mapOf(
            LIST_URL to "text/html; charset=utf-8",
            DETAILS_URL to "text/html; charset=utf-8",
            READER_URL to "text/html; charset=utf-8",
            DOMAIN_URL to "text/html; charset=utf-8",
        ),
    )

    private val parser = WnacgParser(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "404199" in it.url }

        assertEquals(20, list.size)
        assertEquals("/photos-index-page-1-aid-404199.html", item.url)
        assertTrue(item.title.contains("ふらいでぃっしゅ"), "标题解析错误: ${item.title}")
        assertTrue(item.coverUrl.orEmpty().startsWith("http"), "封面应解析成绝对地址")
        assertTrue(list.all { it.source == parser.source }, "条目来源应统一")
    }

    @Test
    fun `details expose chapters`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "404199" in it.url }

        val details = parser.getDetails(item)
        val chapters = details.chapters.orEmpty()
        assertEquals(1, chapters.size)
        assertTrue(chapters.any { it.url == "/photos-slist-aid-404199.html" }, "缺少目标章节 ${chapters.take(3).map { it.url }}")
    }

    @Test
    fun `pages resolve to absolute urls`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        val item = list.first { "404199" in it.url }
        val chapter = parser.getDetails(item).chapters!!.first { "404199" in it.url }

        val pages = parser.getPages(chapter)
        assertEquals(61, pages.size)
        assertTrue(pages.first().url.startsWith("https://img5.qy0.ru/"), "首图地址异常: ${pages.first().url}")
        assertTrue(pages.first().url.contains("verify="), "图片地址应带防盗链 verify 参数")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()))
    }
}
