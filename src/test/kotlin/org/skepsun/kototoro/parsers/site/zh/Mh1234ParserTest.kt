package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.Content
import java.util.Base64

class Mh1234ParserTest {

    private val parser = Mh1234Parser(OfflineContentLoaderContext(mapOf(
        "https://m.wmh1234.com/comic/11247.html" to "mh1234/detail.html",
        "https://m.wmh1234.com/go/MTEyNDctMS1jaGVjaw" to "mh1234/redirect.html",
        "https://reader.example/comic/11247/1" to "mh1234/reader.html",
    )))

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
        assertEquals("测试漫画", details.title)
        assertEquals("测试简介", details.description)
        assertEquals(setOf("测试作者"), details.authors)
        assertEquals(setOf("热血", "少年"), details.tags.map { it.title }.toSet())
        assertEquals("https://images.example/cover.webp", details.coverUrl)
        assertEquals(listOf("第1话", "第2话"), details.chapters?.map { it.title })
        assertEquals(listOf(1f, 2f), details.chapters?.map { it.number })
        val firstChapter = requireNotNull(details.chapters?.firstOrNull()) {
            "章节为空"
        }
        // 站点是单一翻译、无分组：branch 必须为 null，避免每章被应用当成独立分支
        assertNull(firstChapter.branch)
        // chapter.url 是 /go/ 的 base64 token，解码后应保留漫画 ID 和章节 ID
        assertEquals("MTEyNDctMS1jaGVjaw", firstChapter.url)
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(firstChapter.url.trimEnd('=')), Charsets.UTF_8)
        }.getOrNull()
        assertEquals("11247-1-check", decoded)

        val pages = parser.getPages(firstChapter)
        assertEquals(listOf("https://images.example/1.webp", "https://images.example/2.webp"), pages.map { it.url })

        val firstPage = pages.first()
        val referer = firstPage.headers?.get("Referer")
        assertEquals("https://reader.example/comic/11247/1", referer)
        assertEquals(firstPage.url, parser.getPageUrl(firstPage))
    }
}
