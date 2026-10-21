package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 神凑轻小说在线验证（默认跳过）：SHENCOU_INTEGRATION_TEST=1。
 * 覆盖 wowenku.com 的列表 → 详情 → 目录 → 正文，确认不再拿到站点的 404 提示页。
 */
@EnabledIfEnvironmentVariable(named = "SHENCOU_INTEGRATION_TEST", matches = "1")
class ShencouIntegrationTest {

    private val parser = Shencou(ContentLoaderContextMock)

    @Test
    fun exploreDetailsAndChapter() = runBlocking {
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(list.isNotEmpty()) { "神凑列表为空" }
        val target = list.first()
        val details = parser.getDetails(target)
        val description = checkNotNull(details.description) { "神凑简介为空: ${target.url}" }
        check(!description.contains("404错误")) { "简介被站点警告页污染: $description" }
        check(!description.contains("本书公告")) { "公告混入简介: $description" }
        val chapter = checkNotNull(details.chapters?.firstOrNull()) { "神凑章节为空: ${target.url}" }
        val html = parser.getPages(chapter).single().url
        check(html.startsWith("data:text/html")) { "章节正文不是 HTML data url" }
        println("[SHENCOU] ok: ${details.title}, chapters=${details.chapters?.size}, desc=${description.take(40)}")
    }

    @Test
    fun missingArticleReportsNotFoundInsteadOfWarningText() = runBlocking {
        val ghost = target("/books/read_99999999.html")
        assertThrows<NotFoundException> { parser.getDetails(ghost) }
        Unit // JUnit 5 只发现返回 Unit 的测试方法
    }

    private fun target(url: String) = Content(
        id = 1L,
        title = "已删除的作品",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://www.wowenku.com$url",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        description = null,
        chapters = null,
        source = parser.source,
    )
}
