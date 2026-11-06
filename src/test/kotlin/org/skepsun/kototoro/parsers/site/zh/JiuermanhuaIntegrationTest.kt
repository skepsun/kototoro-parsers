package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await
import java.net.UnknownHostException

/**
 * 九二漫画 在线验证（默认跳过）：JIUERMANHUA_INTEGRATION_TEST=1。
 * 覆盖列表翻页 → 分类筛选 → 搜索 → 详情/章节 → 阅读页首尾图片可访问性。
 */
@EnabledIfEnvironmentVariable(named = "JIUERMANHUA_INTEGRATION_TEST", matches = "1")
class JiuermanhuaIntegrationTest {

    private val parser = Jiuermanhua(ContentLoaderContextMock)

    @Test
    fun exploreListDetailsAndImages() = runBlocking {
        val first = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter())
        check(first.isNotEmpty()) { "列表为空" }
        check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
        check(first.first().authors.isNotEmpty()) { "列表作者为空: ${first.first().url}" }

        delay(2_000)
        val second = parser.getListPage(2, SortOrder.NEWEST, ContentListFilter())
        check(second.isNotEmpty()) { "第 2 页为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

        delay(2_000)
        val details = parser.getDetails(first.first())
        checkNotNull(details.description) { "简介为空: ${details.url}" }
        check(details.authors.isNotEmpty()) { "详情作者为空: ${details.url}" }
        val chapters = details.chapters.orEmpty()
        check(chapters.isNotEmpty()) { "章节为空: ${details.url}" }
        check(chapters.first().number >= 1f) { "章节序号异常: ${chapters.first().number}" }

        delay(2_000)
        val pages = parser.getPages(chapters.first())
        check(pages.isNotEmpty()) { "阅读页没有图片: ${chapters.first().url}" }
        val imageUrls = listOf(pages.first(), pages.last()).map { it.url }
        imageUrls.forEach { url ->
            // CDN 域名的 DNS 会抽风（UnknownHostException），重试几次仍解析不出来就跳过而不是判失败
            repeat(3) { attempt ->
                val response = try {
                    ContentLoaderContextMock.httpClient.newCall(
                        Request.Builder().url(url)
                            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                            .header("Referer", "https://www.92mh.com/")
                            .head()
                            .build(),
                    ).await()
                } catch (e: UnknownHostException) {
                    if (attempt == 2) {
                        Assumptions.assumeTrue(false, "图片 CDN 域名解析失败，跳过可访问性检查: $url")
                    }
                    delay(3_000)
                    return@repeat
                }
                response.use {
                    check(it.isSuccessful) { "图片不可访问 $url: HTTP ${it.code}" }
                    val type = it.header("Content-Type").orEmpty()
                    check(type.startsWith("image/")) { "图片类型异常 $url: $type" }
                }
                return@repeat
            }
        }
        println("[92MH] ok: ${details.title}, chapters=${chapters.size}, pages=${pages.size}, first=${imageUrls.first()}")
    }

    @Test
    fun searchAndCategoryFilterWork() = runBlocking {
        val search = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "斗破"))
        check(search.isNotEmpty()) { "搜索「斗破」没有结果" }
        check(search.any { it.title.contains("斗破") }) { "搜索结果与关键字无关: ${search.map { it.title }}" }

        delay(2_000)
        val tag = ContentTag("少年漫画", "shaonian", parser.source)
        val filtered = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag)))
        check(filtered.isNotEmpty()) { "「少年漫画」筛选没有结果" }
        check(filtered.all { it.title.isNotEmpty() }) { "筛选结果标题为空" }

        delay(2_000)
        val categories = parser.getFilterOptions().availableTags
        check(categories.size >= 3) { "分类数量异常: ${categories.map { it.title }}" }
        println("[92MH] search=${search.size}, filtered=${filtered.size}, categories=${categories.map { it.title }}")
    }

    @Test
    fun filteredSortOrdersUseTheTagPathForm() = runBlocking {
        // 带标签时排序要写成 /list/{tag}-/update/、/list/{tag}-/click/（无标签才是 /list/-click/）
        val tag = ContentTag("少年漫画", "shaonian", parser.source)
        val newest = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag)))
        delay(2_000)
        val updated = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(tag)))
        delay(2_000)
        val popular = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(tags = setOf(tag)))
        check(newest.isNotEmpty() && updated.isNotEmpty() && popular.isNotEmpty()) { "带标签排序结果为空" }
        check(newest.map { it.url } != updated.map { it.url }) { "标签 + 更新排序没生效" }
        check(newest.map { it.url } != popular.map { it.url }) { "标签 + 人气排序没生效" }
        println(
            "[92MH] filtered sorts: newest=${newest.first().url}, " +
                "updated=${updated.first().url}, popular=${popular.first().url}",
        )
    }

    @Test
    fun longSeriesIsNotTruncatedByTheSplitChapterLists() = runBlocking {
        // 斗破苍穹（5344）的目录被站点拆成三段：最新 30 话 + 公告 + 开头 701 话，
        // 只取 chapter-list-1 会漏掉几乎所有章节
        val manga = Content(
            id = 0L,
            title = "斗破苍穹",
            altTitles = emptySet(),
            url = "https://www.92mh.com/manhua/5344/",
            publicUrl = "https://www.92mh.com/manhua/5344/",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )
        val details = parser.getDetails(manga)
        val chapters = details.chapters.orEmpty()
        check(chapters.size > 500) { "长篇目录疑似被截断: ${chapters.size} 话" }
        val numbers = chapters.map { it.number }
        check(numbers == numbers.sorted()) { "章节序号不是递增的" }
        check(numbers.distinct().size == numbers.size) { "章节序号有重复" }
        check(chapters.first().url.contains("/manhua/5344/")) { "首章 URL 异常: ${chapters.first().url}" }
        check(chapters.last().url.contains("/manhua/5344/")) { "末章 URL 异常: ${chapters.last().url}" }
        println("[92MH] long series: ${chapters.size} 话, first=${chapters.first().title}, last=${chapters.last().title}")
    }

    @Test
    fun sortOrdersReturnDifferentFeeds() = runBlocking {
        val newest = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter()).map { it.url }
        delay(2_000)
        val updated = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()).map { it.url }
        delay(2_000)
        val popular = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter()).map { it.url }
        check(newest.isNotEmpty() && updated.isNotEmpty() && popular.isNotEmpty()) { "排序结果为空" }
        check(popular != newest) { "人气排序与默认排序结果相同，可能没生效" }
        println("[92MH] sorts: newest=${newest.first()}, updated=${updated.first()}, popular=${popular.first()}")
    }
}
