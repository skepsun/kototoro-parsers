package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class WnacgReadTest {

    private val context = ContentLoaderContextMock

    @Test
    fun pages_specific_album() = runTest(timeout = 2.minutes) {
        val parser = context.newParserInstance(ContentParserSource.WNACG)
        val domain = parser.domain
        val aid = "327407"
        val seed = Content(
            id = aid.hashCode().toLong(),
            title = "aid-$aid",
            altTitles = emptySet(),
            url = "/photos-index-aid-$aid.html",
            publicUrl = "https://$domain/photos-index-aid-$aid.html",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = null,
            chapters = null,
            source = ContentParserSource.WNACG,
        )
        val detailed = parser.getDetails(seed)
        val chapter = detailed.chapters?.firstOrNull() ?: error("未解析到章节：aid=$aid")
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty(), "阅读页为空：aid=$aid")
        // 简单去重校验
        val distinctIds = pages.map { it.id }.toSet()
        assertTrue(distinctIds.size == pages.size, "阅读页 ID 存在重复：aid=$aid")
        val pageUrl = parser.getPageUrl(pages.first())
        // 简单绝对地址校验
        assertTrue(pageUrl.startsWith("http://") || pageUrl.startsWith("https://"), "阅读页地址非绝对：$pageUrl")

        // 真实请求验证（捕获连接关闭或 4xx/5xx）
        val resp = context.doRequest(pageUrl, pages.first().source)
        assertTrue(resp.isSuccessful, "图片请求失败：HTTP ${resp.code} ${resp.message}")
    }
}