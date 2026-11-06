package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 动漫456 在线验证（默认跳过）：DM456_INTEGRATION_TEST=1。
 * 覆盖分类翻页 → 详情/剧集 → 播放页 m3u8 可访问性 → 搜索与频道筛选。
 */
@EnabledIfEnvironmentVariable(named = "DM456_INTEGRATION_TEST", matches = "1")
class Dm456IntegrationTest {

    private val parser = Dm456(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndPlayback() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.isNotEmpty()) { "番剧列表为空" }
        check(first.first().title.isNotEmpty()) { "列表标题为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        val details = parser.getDetails(first.first())
        checkNotNull(details.description) { "简介为空: ${details.url}" }
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "剧集为空: ${details.url}" }
        check(chapters.first().number >= 1f) { "剧集序号异常: ${chapters.first().number}" }

        val pages = parser.getPages(chapters.first())
        check(pages.size == 1) { "单集应只返回一个播放地址" }
        val videoUrl = parser.getPageUrl(pages.single())
        check(videoUrl.startsWith("http")) { "播放地址不是绝对地址: $videoUrl" }
        val response = ContentLoaderContextMock.httpClient.newCall(
            Request.Builder().url(videoUrl).head().build(),
        ).await()
        response.use {
            check(it.isSuccessful) { "播放地址不可访问 $videoUrl: HTTP ${it.code}" }
            val type = it.header("Content-Type").orEmpty()
            check(type.contains("mpegurl") || type.contains("video")) { "播放地址类型异常 $videoUrl: $type" }
        }
        println("[DM456] ok: ${details.title}, chapters=${chapters.size}, m3u8=$videoUrl")
    }

    @Test
    fun searchAndChannelFilterWork() = runBlocking {
        val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "鬼灭"))
        check(search.isNotEmpty()) { "搜索「鬼灭」没有结果" }
        check(search.any { it.title.contains("鬼灭") }) { "搜索结果与关键字无关: ${search.map { it.title }}" }

        val kidTag = ContentTag("少儿", "YCCCCS", parser.source)
        val kids = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(kidTag)))
        check(kids.isNotEmpty()) { "少儿频道没有结果" }
        check(kids.all { content -> content.tags.any { it.key == "YCCCCS" } }) { "筛选结果没有带上频道标签" }
        println("[DM456] search=${search.size}, kids=${kids.size}, sample=${kids.first().title}")
    }
}
