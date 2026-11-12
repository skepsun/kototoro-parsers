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
 * 香香腐宅在线验证（默认跳过）：BOYLOVE_INTEGRATION_TEST=1，且需要能直连站点。
 * 覆盖分类列表翻页 → 详情/章节目录 → 阅读页图片可访问性，以及搜索接口。
 */
@EnabledIfEnvironmentVariable(named = "BOYLOVE_INTEGRATION_TEST", matches = "1")
class BoyloveIntegrationTest {

    private val parser = BoyloveParser(ContentLoaderContextMock)
    private val koreanTag = ContentTag("韩漫", "origin:1", parser.source)

    @Test
    fun exploreListDetailsAndChapter() = runBlocking {
        val filter = ContentListFilter(tags = setOf(koreanTag))
        val first = parser.getListPage(1, SortOrder.NEWEST, filter)
        check(first.isNotEmpty()) { "香香腐宅韩漫列表为空" }
        check(first.size == LIST_PAGE_SIZE) { "首页应有 $LIST_PAGE_SIZE 条，实际 ${first.size}" }
        val second = parser.getListPage(2, SortOrder.NEWEST, filter)
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(first.map { it.url }.intersect(second.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }
        val target = first.first()
        check(target.title.isNotEmpty()) { "列表标题为空" }
        check(target.coverUrl != null) { "列表封面为空: ${target.url}" }

        val details = parser.getDetails(target)
        checkNotNull(details.description) { "简介为空: ${target.url}" }
        check(details.authors.isNotEmpty()) { "作者为空: ${target.url}" }
        check(details.tags.isNotEmpty()) { "标签为空: ${target.url}" }
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
            "[BOYLOVE] ok: ${details.title}, authors=${details.authors}, chapters=${chapters.size}, " +
                "pages=${pages.size}, first=${pages.first().url}",
        )
    }

    @Test
    fun searchAndProgressFilterWork() = runBlocking {
        val search = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "韩漫"))
        check(search.isNotEmpty()) { "搜索「韩漫」没有结果" }
        check(search.size == SEARCH_PAGE_SIZE) { "搜索页应有 $SEARCH_PAGE_SIZE 条，实际 ${search.size}" }

        val finished = parser.getListPage(
            1,
            SortOrder.NEWEST,
            ContentListFilter(tags = setOf(koreanTag), states = setOf(ContentState.FINISHED)),
        )
        check(finished.isNotEmpty()) { "已完结韩漫没有结果" }
        check(finished.all { it.state == ContentState.FINISHED }) { "已完结筛选混入连载作品" }
        println("[BOYLOVE] search=${search.size}, finished=${finished.size}, sample=${finished.first().title}")
    }

    private companion object {
        const val LIST_PAGE_SIZE = 24
        const val SEARCH_PAGE_SIZE = 18
    }
}
