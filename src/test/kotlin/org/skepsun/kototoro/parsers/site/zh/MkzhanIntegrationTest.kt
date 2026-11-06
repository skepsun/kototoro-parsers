package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

// 漫客栈在线验证（默认跳过）：MKZHAN_INTEGRATION_TEST=1。
// 覆盖分类列表翻页去重、两种排序、详情目录顺序、阅读页首尾图片可访问性。
@EnabledIfEnvironmentVariable(named = "MKZHAN_INTEGRATION_TEST", matches = "1")
class MkzhanIntegrationTest {

    private val parser = Mkzhan(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndPages() = runBlocking {
        val first = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
        check(first.isNotEmpty()) { "分类列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().publicUrl.startsWith("https://www.mkzhan.com/")) {
            "详情地址异常: ${first.first().publicUrl}"
        }

        val second = parser.getListPage(2, SortOrder.POPULARITY, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) {
            "第 2 页与第 1 页重复"
        }

        val updated = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(updated.isNotEmpty()) { "更新时间排序为空" }
        check(updated.map { it.url } != first.map { it.url }) { "更新时间与热门人气返回了同一批作品" }

        val search = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = "妖神"))
        check(search.isNotEmpty()) { "搜索结果为空" }

        // 找一个目录里有可读章节的作品：VIP 作品只放出少量免费话，顺序往下试
        var chapters = emptyList<ContentChapter>()
        var pages = emptyList<ContentPage>()
        for (candidate in first.take(5)) {
            val details = parser.getDetails(candidate)
            val list = details.chapters.orEmpty()
            check(list.isNotEmpty()) { "目录为空: ${details.url}" }
            check(list.first().number < list.last().number) { "目录不是正序: ${list.map { it.number }}" }
            chapters = list
            val readable = runCatching { list.first().let { parser.getPages(it) to it } }.getOrNull()
            if (readable != null) {
                pages = readable.first
                break
            }
        }
        check(chapters.isNotEmpty()) { "所有候选作品都没有章节" }
        check(pages.isNotEmpty()) { "阅读页图片为空: ${chapters.first().url}" }

        pages.take(2).plus(pages.last()).forEach { page ->
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
