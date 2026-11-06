package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 全本小说网 quanben.io 在线验证（默认跳过）：QUANBEN_INTEGRATION_TEST=1。
 */
@EnabledIfEnvironmentVariable(named = "QUANBEN_INTEGRATION_TEST", matches = "1")
class QuanbenIntegrationTest {

    private val parser = Quanben(ContentLoaderContextMock)

    @Test
    fun exploreListSearchDetailsAndContent() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "玄幻分类列表为空" }
        check(first.first().authors.isNotEmpty()) { "列表作者为空: ${first.first().title}" }

        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        val fantasy = parser.getListPage(
            1,
            SortOrder.UPDATED,
            ContentListFilter(tags = setOf(ContentTag("耽美", "danmei", parser.source))),
        )
        check(fantasy.isNotEmpty()) { "耽美分类为空" }

        val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "玄幻"))
        check(search.isNotEmpty()) { "搜索结果为空" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.size > 10) { "目录过少: ${chapters.size}" }
        check(!details.description.isNullOrBlank()) { "简介为空: ${details.url}" }
        check(details.authors.isNotEmpty()) { "作者为空: ${details.url}" }
        check(chapters.first().number < chapters.last().number) { "目录不是正序" }

        val pages = parser.getPages(chapters.first())
        check(pages.size == 1) { "小说章节应只有一页数据地址" }
        check(parser.getPageUrl(pages.single()).startsWith("data:text/html;charset=utf-8;base64,")) {
            "正文页不是 data URL"
        }
    }
}
