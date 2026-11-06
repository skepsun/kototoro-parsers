package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 咚漫中文在线验证（默认跳过）：DONGMANMANHUA_INTEGRATION_TEST=1。
 * 覆盖分类页（全站列表 + 题材筛选）→ 详情/多页目录 → 阅读页图片可访问性。
 */
@EnabledIfEnvironmentVariable(named = "DONGMANMANHUA_INTEGRATION_TEST", matches = "1")
class DongmanmanhuaIntegrationTest {

    private val parser = Dongmanmanhua(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndChapter() = runBlocking {
        val first = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
        check(first.isNotEmpty()) { "咚漫分类列表为空" }
        check(first.size == LIST_PAGE_SIZE) { "首页应有 $LIST_PAGE_SIZE 条，实际 ${first.size}" }
        val second = parser.getListPage(2, SortOrder.POPULARITY, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }
        val target = first.first()
        check(target.title.isNotEmpty()) { "列表标题为空" }
        check(target.coverUrl != null) { "列表封面为空: ${target.url}" }

        val details = parser.getDetails(target)
        checkNotNull(details.description) { "简介为空: ${target.url}" }
        check(details.authors.isNotEmpty()) { "作者为空: ${target.url}" }
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "章节为空: ${target.url}" }
        check(chapters.first().number == 1f) { "章节序号未从 1 开始: ${chapters.first().number}" }
        check(chapters.zipWithNext().all { (a, b) -> a.number < b.number }) { "章节序号不是递增" }

        // 阅读页：首尾图片都要能拿到，且是图片响应
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
            "[DONGMANMANHUA] ok: ${details.title}, authors=${details.authors}, chapters=${chapters.size}, " +
                "pages=${pages.size}, first=${pages.first().url}",
        )
    }

    @Test
    fun searchAndFinishedFilterWork() = runBlocking {
        val search = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = "恋爱"))
        check(search.isNotEmpty()) { "搜索「恋爱」没有结果" }

        val finishedTag = ContentTag("完结", "TERMINATION", parser.source)
        val finished = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(finishedTag)))
        check(finished.isNotEmpty()) { "完结题材没有结果" }
        check(finished.all { content -> content.tags.any { it.key == "TERMINATION" } }) { "完结筛选混入未完结作品" }
        println("[DONGMANMANHUA] search=${search.size}, finished=${finished.size}, sample=${finished.first().title}")
    }

    private companion object {
        const val LIST_PAGE_SIZE = 30
    }
}
