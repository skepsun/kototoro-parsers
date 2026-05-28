package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content

class Mh1234ParserTest {

    private val parser = Mh1234Parser(ContentLoaderContextMock)

    @Test
    fun testChapterUrlAndPageHeaders() = runBlocking {
        val content = Content(
            id = 11247L,
            title = "女子学院的男生",
            altTitles = emptySet(),
            url = "11247",
            publicUrl = "https://m.wmh1234.com/comic/11247.html",
            rating = 0f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )

        val details = parser.getDetails(content)
        val firstChapter = requireNotNull(details.chapters?.firstOrNull()) {
            "章节为空"
        }
        assert(firstChapter.url.contains("/")) {
            "章节 URL 应保留漫画 ID 和章节 ID，实际为: ${firstChapter.url}"
        }

        val pages = parser.getPages(firstChapter)
        assert(pages.isNotEmpty()) { "页面为空" }

        val expectedReferer = "https://m.wmh1234.com/comic/${firstChapter.url}.html"
        val firstPage = pages.first()
        assert(firstPage.headers?.get("Referer") == expectedReferer) {
            "图片 Referer 不正确: ${firstPage.headers}"
        }
        assert(firstPage.url.startsWith("https://")) {
            "图片 URL 应为绝对地址，实际为: ${firstPage.url}"
        }
    }
}
