package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.test_util.isDistinctBy
import org.skepsun.kototoro.test_util.isUrlAbsolute
import kotlin.time.Duration.Companion.minutes

class CopyContentXigelideTest {

    private val context = ContentLoaderContextMock

    @Test
    fun details_xigelide() = runTest(timeout = 2.minutes) {
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val domain = parser.domain
        val slug = "xigelide"
        val seed = Content(
            id = slug.hashCode().toLong(),
            title = slug,
            altTitles = emptySet(),
            url = slug,
            publicUrl = "https://$domain/comic/$slug",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = null,
            chapters = null,
            source = ContentParserSource.COPYMANGA,
        )

        val detailed = parser.getDetails(seed)
        assertTrue(detailed.publicUrl.isUrlAbsolute(), "publicUrl 非绝对地址")
        assertTrue(!detailed.description.isNullOrBlank(), "详情页描述为空")
        val chapters = detailed.chapters ?: emptyList()
        assertTrue(chapters.isNotEmpty(), "章节列表为空")
        assertTrue(chapters.isDistinctBy { it.id }, "章节 ID 存在重复")
        assertTrue(chapters.all { it.source == ContentParserSource.COPYMANGA }, "章节来源不一致")
    }

    @Test
    fun pages_xigelide() = runTest(timeout = 2.minutes) {
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val domain = parser.domain
        val slug = "xigelide"
        val seed = Content(
            id = slug.hashCode().toLong(),
            title = slug,
            altTitles = emptySet(),
            url = slug,
            publicUrl = "https://$domain/comic/$slug",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = null,
            chapters = null,
            source = ContentParserSource.COPYMANGA,
        )
        val chapter = parser.getDetails(seed).chapters?.firstOrNull()
            ?: error("未解析到章节：$slug")
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty(), "阅读页为空")
        assertTrue(pages.isDistinctBy { it.id }, "阅读页 ID 存在重复")
        assertTrue(pages.all { it.source == ContentParserSource.COPYMANGA }, "阅读页来源不一致")
        val pageUrl = parser.getPageUrl(pages.first())
        assertTrue(pageUrl.isUrlAbsolute(), "阅读页地址非绝对：$pageUrl")
    }
}