package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import java.util.Base64

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
        // 站点是单一翻译、无分组：branch 必须为 null，避免每章被应用当成独立分支
        assert(firstChapter.branch == null) {
            "单翻译站点 branch 应为 null，实际为: ${firstChapter.branch}"
        }
        // chapter.url 是 /go/ 的 base64 token，解码后应保留漫画 ID 和章节 ID
        assert(firstChapter.url.isNotBlank()) {
            "章节 token 不应为空"
        }
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(firstChapter.url.trimEnd('=')), Charsets.UTF_8)
        }.getOrNull()
        assert(decoded?.contains("11247") == true) {
            "章节 token 解码后应包含漫画 ID，实际为: $decoded"
        }

        val pages = parser.getPages(firstChapter)
        assert(pages.isNotEmpty()) { "页面为空" }

        val firstPage = pages.first()
        val referer = firstPage.headers?.get("Referer")
        assert(referer?.startsWith("https://") == true) {
            "图片 Referer 应为阅读器绝对地址，实际为: $referer"
        }
        assert(firstPage.url.startsWith("https://")) {
            "图片 URL 应为绝对地址，实际为: ${firstPage.url}"
        }
    }
}
