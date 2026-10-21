package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.jsoup.Jsoup
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * 哔哩轻小说在线验证（默认跳过）：BILINOVEL_INTEGRATION_TEST=1。
 * 重点确认正文来自 PC 站完整分页，而不是移动站截断页（带「內容加載失敗」提示）。
 */
@EnabledIfEnvironmentVariable(named = "BILINOVEL_INTEGRATION_TEST", matches = "1")
class BilinovelIntegrationTest {

    private val parser = Bilinovel(ContentLoaderContextMock)

    private fun book(url: String) = Content(
        id = 1L,
        title = "第101篇百物语",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://www.bilinovel.com$url",
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

    @Test
    fun chapterContentIsCompleteInsteadOfTruncated() = runBlocking {
        val details = parser.getDetails(book("/novel/1082.html"))
        check(details.title.isNotBlank()) { "标题为空" }
        val chapters = checkNotNull(details.chapters) { "章节目录为空" }
        check(chapters.size >= 10) { "章节数异常: ${chapters.size}" }
        check(chapters.any { it.scanlator?.isNotBlank() == true }) { "缺少卷名（scanlator）" }

        val target = chapters.first { it.url.endsWith("/43627.html") }
        val content = checkNotNull(parser.getChapterContent(target)) { "正文为空: ${target.url}" }
        val doc = Jsoup.parse(content.html)
        val text = doc.text()

        check(!text.contains("內容加載失敗")) { "正文仍含移动站截断提示" }
        check(!text.contains("更换浏览器")) { "正文仍含浏览器提示" }
        check(text.length > 5000) { "正文过短，可能只拿到第一页: ${text.length}" }
        check(doc.select("p").size >= 20) { "段落数过少: ${doc.select("p").size}" }
        println("[BILINOVEL] ok: ${details.title}, chapters=${chapters.size}, html=${content.html.length}, text=${text.length}, images=${content.images.size}")

        val illustration = chapters.firstOrNull { it.title.orEmpty().contains("插图") || it.title.orEmpty().contains("插圖") }
        if (illustration != null) {
            val ill = checkNotNull(parser.getChapterContent(illustration)) { "插图章节正文为空" }
            check(ill.images.isNotEmpty()) { "插图章节没有解析出图片" }
            ill.images.forEach { image ->
                val request = Request.Builder().url(image.url)
                    .apply { image.headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                ContentLoaderContextMock.httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "图片不可访问 ${image.url}: ${response.code}" }
                    check(response.header("Content-Type")?.startsWith("image/") == true) {
                        "图片响应类型异常 ${image.url}: ${response.header("Content-Type")}"
                    }
                }
            }
            println("[BILINOVEL] illustration ok: ${ill.images.size} images")
        }
    }
}
