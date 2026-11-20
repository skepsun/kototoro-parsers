package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * klz9 的离线回归覆盖。
 *
 * 夹具来自 2026-11-13 的真实 API 响应：列表响应被裁剪到前 3 条，详情与阅读页保持原样。
 * 线上连通性冒烟见 [Klz9IntegrationTest]；本类只锁解析规则，不发任何网络请求，
 * 请求了未登记的 URL 会直接抛 "Unexpected offline request"，因此夹具一旦失效就会立刻暴露。
 */
class Klz9Test {

    private companion object {
        const val JSON = "application/json; charset=utf-8"
        const val LIST_URL = "https://klz9.com/api/manga/list?page=1&limit=36&sort=last_update&order=desc"
        const val DETAILS_URL =
            "https://klz9.com/api/manga/slug/momban-yattero-to-iware-15-nen-tsuttatetteru-ma-ni" +
                "-ore-no-maryoku-ga-9999-saikyo-ni-sodattemashita"
        const val CHAPTER_URL = "https://klz9.com/api/chapter/278468"
    }

    private val context = OfflineContentLoaderContext(
        fixtures = mapOf(
            LIST_URL to "klz9/list.json",
            DETAILS_URL to "klz9/details.json",
            CHAPTER_URL to "klz9/chapter.json",
        ),
        responseTypes = mapOf(
            LIST_URL to JSON,
            DETAILS_URL to JSON,
            CHAPTER_URL to JSON,
        ),
    )

    private val parser = Klz9(context)

    @Test
    fun `list page maps items`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())

        assertEquals(3, list.size)
        val first = list.first()
        assertTrue(first.title.contains("MOMBAN YATTERO"), "首条标题异常: ${first.title}")
        assertTrue(first.url.isNotBlank(), "内容 url 不能为空")
        assertTrue(first.coverUrl!!.startsWith("https://"), "封面应为绝对地址: ${first.coverUrl}")
        assertTrue(list.all { it.source == parser.source }, "全部条目应归属同一个来源")
    }

    @Test
    fun `details expose chapters in reading order`() = runBlocking {
        val item = parser.getList(0, SortOrder.UPDATED, ContentListFilter()).first()

        val details = parser.getDetails(item)

        assertEquals(32, details.chapters.orEmpty().size)
        val firstChapter = details.chapters!!.first()
        assertTrue(firstChapter.url.isNotBlank(), "章节 url 不能为空")
        assertTrue(details.chapters!!.all { it.url.isNotBlank() })
    }

    @Test
    fun `pages resolve to absolute image urls`() = runBlocking {
        val item = parser.getList(0, SortOrder.UPDATED, ContentListFilter()).first()
        val chapter = parser.getDetails(item).chapters!!.first()

        val pages = parser.getPages(chapter)

        assertEquals(46, pages.size)
        assertTrue(pages.all { it.url.startsWith("https://") }, "阅读页应为绝对地址")
        assertEquals(pages.first().url, parser.getPageUrl(pages.first()), "该站 getPageUrl 不应改写地址")
    }
}
