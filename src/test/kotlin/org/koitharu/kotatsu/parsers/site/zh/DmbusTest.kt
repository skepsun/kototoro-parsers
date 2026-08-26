package org.koitharu.kotatsu.parsers.site.zh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.site.zh.Dmbus
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

class DmbusTest {

    private val parser = Dmbus(ContentLoaderContextMock)

    // dmbus.cc / hhplayer 的 TLS 偶发握手失败，重试几次避免网络抖动导致误报
    private suspend fun <T> withRetry(times: Int = 4, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times) {
            try {
                return block()
            } catch (e: Throwable) {
                last = e
                delay(2000)
            }
        }
        throw (last ?: IllegalStateException("重试失败"))
    }

    @Test
    fun testGetListPage() = runBlocking {
        val mangaList = withRetry {
            parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
        }
        assert(mangaList.isNotEmpty()) { "应该获取到视频列表" }
    }

    @Test
    fun testSearch() = runBlocking {
        val results = withRetry {
            parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = "斗罗大陆"))
        }
        assert(results.isNotEmpty()) { "应该搜索到结果" }
    }

    @Test
    fun testDetailsAndPages() = runBlocking {
        val seed = withRetry { parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()) }.first()
        val detailed = withRetry { parser.getDetails(seed) }
        val chapters = detailed.chapters.orEmpty()
        assert(chapters.isNotEmpty()) { "章节列表为空" }
        val pages = withRetry { parser.getPages(chapters.first()) }
        assert(pages.isNotEmpty()) { "章节阅读页为空" }
        assert(pages.first().url.startsWith("http")) {
            "阅读页地址应为绝对 URL，实际为: ${pages.first().url}"
        }
    }
}
