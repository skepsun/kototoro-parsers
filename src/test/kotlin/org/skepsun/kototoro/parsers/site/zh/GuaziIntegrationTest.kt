package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 瓜子漫画在线验证（默认跳过）：GUAZI_INTEGRATION_TEST=1。
 * 覆盖分类列表翻页、「更新」排序、详情目录顺序与首尾图片可访问性。
 */
@EnabledIfEnvironmentVariable(named = "GUAZI_INTEGRATION_TEST", matches = "1")
class GuaziIntegrationTest {

    private val parser = Guazi(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndPages() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "分类列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().publicUrl.startsWith("https://www.guazimanhua.com/")) {
            "详情地址异常: ${first.first().publicUrl}"
        }

        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        val popularity = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
        check(popularity.isNotEmpty()) { "人气排序为空" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "目录为空: ${details.url}" }
        check(chapters.first().number < chapters.last().number) { "目录不是正序: ${chapters.map { it.number }}" }

        val pages = parser.getPages(chapters.first())
        check(pages.isNotEmpty()) { "阅读页图片为空: ${chapters.first().url}" }
        pages.take(2).forEach { page ->
            val url = parser.getPageUrl(page)
            val response = ContentLoaderContextMock.httpClient.newCall(
                Request.Builder().url(url).head().build(),
            ).await()
            response.use {
                check(it.isSuccessful) { "图片不可访问 $url: HTTP ${it.code}" }
                val type = it.header("Content-Type").orEmpty()
                check(type.startsWith("image/")) { "图片类型异常 $url: $type" }
            }
        }
    }
}
