package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

// 看漫网在线验证（默认跳过）：KANMAN_INTEGRATION_TEST=1。
// 覆盖列表翻页去重、两种排序、搜索、题材与进度筛选、详情目录顺序、阅读页首尾图片可访问性。
@EnabledIfEnvironmentVariable(named = "KANMAN_INTEGRATION_TEST", matches = "1")
class KanmanIntegrationTest {

    private val parser = Kanman(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndPages() = runBlocking {
        val first = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
        check(first.isNotEmpty()) { "人气列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().publicUrl.startsWith("https://www.kanman.com/")) {
            "详情地址异常: ${first.first().publicUrl}"
        }

        val second = parser.getListPage(2, SortOrder.POPULARITY, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) {
            "第 2 页与第 1 页重复"
        }

        val updated = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(updated.isNotEmpty()) { "更新时间排序为空" }

        val search = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = "凤"))
        check(search.isNotEmpty()) { "搜索结果为空" }

        val options = parser.getFilterOptions()
        val tag = options.availableTags.first { it.key == "rexue" }
        check(parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(tags = setOf(tag))).isNotEmpty()) {
            "题材筛选（热血）为空"
        }
        check(
            parser.getListPage(
                1,
                SortOrder.POPULARITY,
                ContentListFilter(states = setOf(ContentState.FINISHED)),
            ).isNotEmpty(),
        ) { "进度筛选（完结）为空" }

        // 找一个能读到图的章节：先看前几部作品的目录和阅读页
        var chapters = emptyList<ContentChapter>()
        var pages = emptyList<ContentPage>()
        for (candidate in first.take(3)) {
            val details = parser.getDetails(candidate)
            val list = details.chapters.orEmpty()
            check(list.isNotEmpty()) { "目录为空: ${details.url}" }
            check(list.first().number < list.last().number) { "目录不是正序: ${list.map { it.number }}" }
            check(details.authors.isNotEmpty()) { "作者为空: ${details.url}" }
            chapters = list
            val readable = runCatching { parser.getPages(list.first()) }.getOrNull()
            if (!readable.isNullOrEmpty()) {
                pages = readable
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
