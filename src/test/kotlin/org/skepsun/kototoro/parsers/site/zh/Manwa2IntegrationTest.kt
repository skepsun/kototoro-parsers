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
 * 漫蛙在线验证（默认跳过）：MANWA2_INTEGRATION_TEST=1，且需要能直连站点。
 * 覆盖排行/更新页 → 分类筛选翻页 → 详情目录 → 阅读页解密后的图片可访问性，以及站内搜索。
 */
@EnabledIfEnvironmentVariable(named = "MANWA2_INTEGRATION_TEST", matches = "1")
class Manwa2IntegrationTest {

	private val parser = Manwa2Parser(ContentLoaderContextMock)

	@Test
	fun exploreRankingDetailsAndChapter() = runBlocking {
		val ranking = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter.EMPTY)
		check(ranking.isNotEmpty()) { "漫蛙人气榜为空" }
		val updated = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter.EMPTY)
		check(updated.isNotEmpty()) { "漫蛙最新更新页为空" }
		check(ranking.map { it.url }.toSet() != updated.map { it.url }.toSet()) { "榜单与更新页内容完全相同" }

		val target = ranking.first()
		check(target.title.isNotEmpty()) { "榜单标题为空" }
		val details = parser.getDetails(target)
		check(details.title.isNotEmpty()) { "详情标题为空: ${target.url}" }
		check(details.authors.isNotEmpty()) { "作者为空: ${target.url}" }
		val chapters = details.chapters.orEmpty()
		check(chapters.isNotEmpty()) { "章节为空: ${target.url}" }
		check(chapters.map { it.url }.toSet().size == chapters.size) { "章节 URL 有重复" }

		// 阅读页：图片来自 AES 解密的 params，首尾图片都要能拿到且是图片响应
		val pages = parser.getPages(chapters.first())
		check(pages.size >= 3) { "阅读页图片过少: ${pages.size}" }
		listOf(pages.first(), pages.last()).forEach { page ->
			val pageUrl = parser.getPageUrl(page)
			val request = Request.Builder().url(pageUrl)
			page.headers?.forEach { (name, value) -> request.header(name, value) }
			val response = ContentLoaderContextMock.httpClient.newCall(request.head().build()).await()
			response.use {
				check(it.isSuccessful) { "图片不可访问 $pageUrl: HTTP ${it.code}" }
				val type = it.header("Content-Type").orEmpty()
				check(type.startsWith("image/")) { "图片响应类型异常 $pageUrl: $type" }
			}
		}
		println(
			"[MANWA2] ok: ${details.title}, authors=${details.authors}, chapters=${chapters.size}, " +
				"pages=${pages.size}, first=${pages.first().url}",
		)
	}

	@Test
	fun filtersAndSearchWork() = runBlocking {
		val korean = tag("list/3")
		val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(korean)))
		check(first.isNotEmpty()) { "韩国漫画分类为空" }
		val second = parser.getListPage(2, SortOrder.UPDATED, ContentListFilter(tags = setOf(korean)))
		check(second.isNotEmpty()) { "韩国漫画第 2 页为空" }
		check(first.map { it.url }.intersect(second.map { it.url }.toSet()).isEmpty()) { "第 2 页与第 1 页重复" }

		val finished = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(tags = setOf(korean, tag("finish/2"))))
		check(finished.isNotEmpty()) { "韩国漫画+完结 组合筛选为空" }

		val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "恋爱"))
		check(search.isNotEmpty()) { "搜索「恋爱」没有结果" }
		check(parser.getListPage(2, SortOrder.UPDATED, ContentListFilter(query = "恋爱")).isEmpty()) {
			"站内搜索不分页，第 2 页应为空"
		}
		println("[MANWA2] korean=${first.size}, finished=${finished.size}, search=${search.size}")
	}

	private suspend fun tag(segment: String): ContentTag =
		parser.getFilterOptions().availableTags.single { it.key == segment }
}
