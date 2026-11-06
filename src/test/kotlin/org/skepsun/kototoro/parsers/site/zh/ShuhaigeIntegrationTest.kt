package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 书海阁在线验证（默认跳过）：SHUHAIGE_INTEGRATION_TEST=1。
 */
@EnabledIfEnvironmentVariable(named = "SHUHAIGE_INTEGRATION_TEST", matches = "1")
class ShuhaigeIntegrationTest {

    private val parser = Shuhaige(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndContent() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "分类列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().authors.isNotEmpty()) { "列表作者为空: ${first.first().title}" }

        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        // 站点按更新时间排序，翻页瞬间会有少量书重复，只要求第 2 页明显不同
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).size < first.size / 2) {
            "第 2 页与第 1 页几乎相同，分页参数失效"
        }

        val other = parser.getListPage(
            1,
            SortOrder.UPDATED,
            ContentListFilter(tags = setOf(ContentTag("武侠", "WuXia", parser.source))),
        )
        check(other.isNotEmpty()) { "武侠分类为空" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.size > 10) { "目录过少: ${chapters.size}" }
        check(!details.description.isNullOrBlank()) { "简介为空: ${details.url}" }
        check(details.authors.isNotEmpty()) { "作者为空: ${details.url}" }
        check(chapters.first().number <= chapters.last().number) { "目录不是正序" }

        val pages = parser.getPages(chapters.first())
        check(pages.size == 1) { "小说章节应只有一页数据地址" }
        val url = parser.getPageUrl(pages.single())
        check(url.startsWith("data:text/html;charset=utf-8;base64,")) { "正文页不是 data URL" }
    }

    @Test
    fun searchUsesPostFormAndSecondPageDiffers() = runBlocking {
        val filter = ContentListFilter(query = "剑来")
        val page1 = parser.getListPage(1, SortOrder.UPDATED, filter)
        check(page1.isNotEmpty()) { "搜索结果为空" }

        val page2 = parser.getListPage(2, SortOrder.UPDATED, filter)
        check(page2.isNotEmpty()) { "搜索第 2 页为空（搜索 id 解析失败？）" }
        check(page2.map { it.url }.intersect(page1.map { it.url }.toSet()).size < page1.size / 2) {
            "搜索第 2 页与第 1 页几乎相同"
        }
    }
}
