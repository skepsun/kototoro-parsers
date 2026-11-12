package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 鸟鸟韩漫在线验证（默认跳过）：NNHANMAN_INTEGRATION_TEST=1。
 * 覆盖分类列表（分页 + 中文题材 + 状态筛选）→ 详情/全量目录（倒序需反转）→ 阅读页首尾图片可访问性 → 站内搜索。
 */
@EnabledIfEnvironmentVariable(named = "NNHANMAN_INTEGRATION_TEST", matches = "1")
class NnhanmanIntegrationTest {

    private val parser = NnhanmanParser(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndChapter() = runBlocking {
        val first = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter.EMPTY)
        check(first.isNotEmpty()) { "分类列表为空" }
        check(first.size == LIST_PAGE_SIZE) { "首页应有 $LIST_PAGE_SIZE 条，实际 ${first.size}" }
        val second = parser.getListPage(2, SortOrder.NEWEST, ContentListFilter.EMPTY)
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        val target = first.first()
        check(target.title.isNotEmpty()) { "列表标题为空" }
        checkNotNull(target.coverUrl) { "列表封面为空: ${target.url}" }

        val details = parser.getDetails(target)
        check(details.title.isNotEmpty()) { "详情标题为空: ${target.url}" }
        check(!details.title.contains('《')) { "详情标题仍带书名号: ${details.title}" }
        check(details.authors.isNotEmpty()) { "作者为空: ${target.url}" }
        checkNotNull(details.description) { "简介为空: ${target.url}" }
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "章节为空: ${target.url}" }
        // 站点目录是倒序输出，解析后必须已反转成阅读顺序
        check(chapters.first().number == 1f) { "章节序号未从 1 开始: ${chapters.first().number}" }
        check(chapters.zipWithNext().all { (a, b) -> a.number < b.number }) { "章节序号不是递增" }

        val pages = parser.getPages(chapters.first())
        check(pages.size >= 3) { "阅读页图片过少: ${pages.size}" }
        listOf(pages.first(), pages.last()).forEach { page ->
            val pageUrl = parser.getPageUrl(page)
            val response = ContentLoaderContextMock.httpClient.newCall(
                Request.Builder().url(pageUrl).head().build(),
            ).await()
            response.use {
                check(it.isSuccessful) { "图片不可访问 $pageUrl: HTTP ${it.code}" }
                val type = it.header("Content-Type").orEmpty()
                check(type.startsWith("image/")) { "图片响应类型异常 $pageUrl: $type" }
            }
        }
        println(
            "[NNHANMAN] ok: ${details.title}, authors=${details.authors}, chapters=${chapters.size}, " +
                "pages=${pages.size}, first=${pages.first().url}",
        )
    }

    @Test
    fun genreStateFilterAndSearchWork() = runBlocking {
        val genre = parser.getListPage(
            1,
            SortOrder.NEWEST,
            ContentListFilter(tags = setOf(ContentTag("正妹", "genre:正妹", parser.source))),
        )
        check(genre.isNotEmpty()) { "题材「正妹」没有结果" }

        val finished = parser.getListPage(
            1,
            SortOrder.POPULARITY,
            ContentListFilter(states = setOf(ContentState.FINISHED)),
        )
        check(finished.isNotEmpty()) { "已完结筛选没有结果" }

        val search = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "秘密"))
        check(search.isNotEmpty()) { "搜索「秘密」没有结果" }
        println(
            "[NNHANMAN] genre=${genre.size}, finished=${finished.size}, search=${search.size}, " +
                "sample=${search.first().title}",
        )
    }

    private companion object {
        const val LIST_PAGE_SIZE = 18
    }
}
