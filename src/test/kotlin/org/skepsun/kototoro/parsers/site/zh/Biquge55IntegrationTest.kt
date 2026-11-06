package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 笔趣阁55 在线验证（默认跳过）：BIQUGE55_INTEGRATION_TEST=1。
 * 覆盖分类列表翻页、详情元信息与目录顺序，以及「多分页正文」能否被完整拼成一章。
 */
@EnabledIfEnvironmentVariable(named = "BIQUGE55_INTEGRATION_TEST", matches = "1")
class Biquge55IntegrationTest {

    private val parser = Biquge55(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndContent() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "玄幻分类列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().authors.isNotEmpty()) { "列表作者为空: ${first.first().title}" }

        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        val wuxia = parser.getListPage(
            1,
            SortOrder.UPDATED,
            ContentListFilter(tags = setOf(ContentTag("武侠", "wuxia", parser.source))),
        )
        check(wuxia.isNotEmpty()) { "武侠分类为空" }

        val details = parser.getDetails(first.first())
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "目录为空: ${details.url}" }
        check(!details.description.isNullOrBlank()) { "简介为空: ${details.url}" }
        check(details.authors.isNotEmpty()) { "作者为空: ${details.url}" }
        check(details.title.isNotBlank()) { "书名为空: ${details.url}" }
        check(chapters.first().number < chapters.last().number) { "目录不是正序" }
        check(chapters.zipWithNext().count { (a, b) -> a.number > b.number } < 3) { "目录顺序抖动过多" }

        val chapter = chapters.first()
        val content = parser.getChapterContent(chapter)
        check(content != null) { "正文为空: ${chapter.url}" }
        check(content.html.contains("<h1>")) { "正文缺少标题: ${chapter.url}" }
        check(content.html.length > 500) { "正文过短: ${content.html.length}" }

        val pages = parser.getPages(chapter)
        check(pages.size == 1) { "小说章节应只有一页数据地址" }
        check(parser.getPageUrl(pages.single()).startsWith("data:text/html;charset=utf-8;base64,")) {
            "正文页不是 data URL"
        }
    }
}
