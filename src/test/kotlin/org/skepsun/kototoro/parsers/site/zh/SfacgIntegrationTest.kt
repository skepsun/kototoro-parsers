package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

// SF轻小说在线验证（默认跳过）：SFACG_INTEGRATION_TEST=1。
// 覆盖书库分类/PageIndex 翻页去重、写作进度筛选、聚合搜索、详情目录卷分组、正文长度。
@EnabledIfEnvironmentVariable(named = "SFACG_INTEGRATION_TEST", matches = "1")
class SfacgIntegrationTest {

    private val parser = Sfacg(ContentLoaderContextMock)

    @Test
    fun exploreLibrarySearchDetailsAndText() = runBlocking {
        val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        check(first.size == 20) { "书库每页条数不是 20：${first.size}" }
        check(first.all { it.coverUrl != null }) { "列表存在缺封面的条目" }

        val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter())
        check(second.isNotEmpty()) { "PageIndex=2 为空" }
        check(second.map { it.url }.intersect(first.map { it.url }.toSet()).isEmpty()) {
            "PageIndex=2 与第 1 页重复，分页参数可能写错"
        }

        val tags = parser.getFilterOptions().availableTags
        val finishedTag = tags.first { it.key == "if:1" }
        val finished = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(finishedTag)))
        check(finished.isNotEmpty()) { "已完结筛选为空" }

        val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "魔女"))
        check(search.isNotEmpty()) { "聚合搜索没有结果" }
        check(parser.getListPage(2, SortOrder.UPDATED, ContentListFilter(query = "魔女")).isEmpty()) {
            "搜索应当只有一页"
        }

        // 书库里新书可能还没有章节，取前几部里目录最完整的那本
        val detailed = first.take(5).map { parser.getDetails(it) }
        println("目录规模：" + detailed.joinToString { "${it.title}=${it.chapters?.size}" })
        val withChapters = detailed.filter { (it.chapters?.size ?: 0) > 10 }
        check(withChapters.isNotEmpty()) { "前 5 部作品都没有可用目录：$detailed" }
        val details = withChapters.maxBy { it.chapters!!.size }
        val chapters = details.chapters.orEmpty()
        check(chapters.map { it.number }.zipWithNext().all { (a, b) -> a < b }) { "目录编号不递增" }
        check(chapters.any { it.branch != null }) { "目录没有卷信息" }

        // 从第一章往后找一个能读到正文的免费章节
        val readable = chapters.take(5).firstNotNullOfOrNull { chapter ->
            val page = parser.getPages(chapter).firstOrNull() ?: return@firstNotNullOfOrNull null
            val html = java.util.Base64.getDecoder()
                .decode(parser.getPageUrl(page).substringAfter("base64,"))
                .toString(Charsets.UTF_8)
            val text = org.jsoup.Jsoup.parse(html).body().text()
            text.takeIf { it.length > 200 }?.let { chapter to text }
        }
        check(readable != null) { "前 5 章都读不到正文：${chapters.take(5).joinToString { it.url }}" }
        println("SF轻小说 正文抽样：${readable.first.title} -> ${readable.second.take(60)}...")
    }
}
