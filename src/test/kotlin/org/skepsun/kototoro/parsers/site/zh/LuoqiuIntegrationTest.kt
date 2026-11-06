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
import java.util.Base64

/**
 * 落秋中文在线验证（默认跳过）：LUOQIU_INTEGRATION_TEST=1。
 * 覆盖列表 → 详情 → 目录（含详情页 99 章之外的分页目录）→ 正文 base64 解码与多页拼接。
 */
@EnabledIfEnvironmentVariable(named = "LUOQIU_INTEGRATION_TEST", matches = "1")
class LuoqiuIntegrationTest {

    private val parser = Luoqiu(ContentLoaderContextMock)

    @Test
    fun exploreDetailsAndChapter() = runBlocking {
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(list.isNotEmpty()) { "落秋列表为空" }
        val target = list.first()
        val details = parser.getDetails(target)
        val description = checkNotNull(details.description) { "落秋简介为空: ${target.url}" }
        check(details.coverUrl != null) { "落秋封面为空: ${target.url}" }
        val chapters = checkNotNull(details.chapters).takeIf { it.isNotEmpty() } ?: error("落秋章节为空: ${target.url}")
        check(chapters.size >= DETAIL_CHAPTER_LIMIT) {
            "详情页应只给前 $DETAIL_CHAPTER_LIMIT 章（多出来说明目录协议变了），实际 ${chapters.size}"
        }

        val chapter = chapters.first()
        val page = parser.getPages(chapter).single()
        check(page.url.startsWith("data:text/html")) { "章节正文不是 HTML data url" }
        val html = String(Base64.getDecoder().decode(page.url.substringAfter("base64,")), Charsets.UTF_8)
        check(html.contains("<p>")) { "章节正文没有段落: ${chapter.url}" }
        check(!html.contains("本章节内容正在手打中")) { "正文是占位页: ${chapter.url}" }
        println("[LUOQIU] ok: ${details.title}, chapters=${chapters.size}, desc=${description.take(40)}")
    }

    @Test
    fun multiPageChapterIsAssembled() = runBlocking {
        // 长章节会被切成 {id}.html、{id}_1.html…（第 2 页起 `.next` 才指向下一章），
        // 拼装后的正文必须明显长于单页：单页约 30 段、正文不到 1200 字。
        val chapters = checkNotNull(parser.getDetails(multiPageSample()).chapters) { "长章节样本书的目录为空" }
        val measured = chapters.take(CHAPTER_SCAN_LIMIT).mapNotNull { chapter ->
            val html = parser.getChapterContent(chapter)?.html ?: return@mapNotNull null
            check(!html.contains("qsbs.bb")) { "base64 段落未被解码: ${chapter.url}" }
            chapter.title.orEmpty() to html.replace(HTML_TAG_REGEX, "").length
        }
        val (title, textLength) = measured.maxByOrNull { it.second } ?: error("没有可解析的章节正文")
        check(textLength > SINGLE_PAGE_TEXT_LIMIT) {
            "扫描 ${CHAPTER_SCAN_LIMIT} 章未见多页正文（最长 $textLength 字，疑似没有拼接 {id}_1.html）"
        }
        println("[LUOQIU] multi-page ok: $title text=$textLength")
    }

    @Test
    fun missingBookReportsNotFound() = runBlocking {
        assertThrows<NotFoundException> { parser.getDetails(bookContent("/book/999999999/")) }
        Unit // JUnit 5 只发现返回 Unit 的测试方法
    }

    /** 多页正文样本：优先用老书（章节全文早已稳定），失效时退回当前列表第一本 */
    private suspend fun multiPageSample(): Content = runCatching {
        parser.getDetails(bookContent(MULTI_PAGE_BOOK))
    }.getOrElse {
        parser.getDetails(parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()).first())
    }

    private fun bookContent(relativeUrl: String) = Content(
        id = 1L,
        title = "落秋样本书",
        altTitles = emptySet(),
        url = relativeUrl,
        publicUrl = "https://www.luoqiuzw.com$relativeUrl",
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

    private companion object {
        const val DETAIL_CHAPTER_LIMIT = 99
        const val CHAPTER_SCAN_LIMIT = 30
        const val SINGLE_PAGE_TEXT_LIMIT = 1500
        const val MULTI_PAGE_BOOK = "/book/5183/"
        val HTML_TAG_REGEX = Regex("<[^>]+>")
    }
}
