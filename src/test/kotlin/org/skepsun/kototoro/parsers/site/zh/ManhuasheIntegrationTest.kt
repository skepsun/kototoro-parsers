package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

// 漫画社在线验证（默认跳过）：MANHUASHE_INTEGRATION_TEST=1。
// 覆盖分类翻页去重、题材筛选、路径式搜索、详情目录顺序、阅读页首尾图片可访问性。
@EnabledIfEnvironmentVariable(named = "MANHUASHE_INTEGRATION_TEST", matches = "1")
class ManhuasheIntegrationTest {

    private val parser = Manhuashe(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndPages() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "分类列表为空" }
        check(first.size == 20) { "分类每页条数不是 20：${first.size}" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }

        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) {
            "第 2 页与第 1 页重复"
        }

        val tags = parser.getFilterOptions().availableTags
        check(tags.size > 100) { "题材数量异常: ${tags.size}" }
        val tagKey = tags.first { it.title == "热血" }.key
        val filtered = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(tags.first { it.key == tagKey })))
        check(filtered.isNotEmpty()) { "题材筛选为空: $tagKey" }

        val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "一人"))
        check(search.isNotEmpty()) { "搜索结果为空" }
        check(search.any { it.title.contains("一人") }) { "搜索结果与关键词不相关" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "目录为空: ${details.url}" }
        check(chapters.first().number < chapters.last().number) { "目录不是正序" }
        check(details.authors.isNotEmpty() || details.tags.isNotEmpty()) { "详情缺少作者/题材" }

        val pages = parser.getPages(chapters.last())
        check(pages.isNotEmpty()) { "阅读页图片为空: ${chapters.last().url}" }
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
