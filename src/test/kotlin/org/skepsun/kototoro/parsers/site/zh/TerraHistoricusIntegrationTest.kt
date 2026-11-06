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
 * 明日方舟漫画在线验证（默认跳过）：TERRA_HISTORICUS_INTEGRATION_TEST=1。
 * 覆盖两个频道的列表 → 更新顺序 → 标签筛选/本地搜索 → 详情章节 → 首尾图片可访问性。
 */
@EnabledIfEnvironmentVariable(named = "TERRA_HISTORICUS_INTEGRATION_TEST", matches = "1")
class TerraHistoricusIntegrationTest {

	private val parser = TerraHistoricus(ContentLoaderContextMock)

	@Test
	fun exploreChannelsDetailsAndChapter() = runBlocking {
		val terra = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter())
		check(terra.isNotEmpty()) { "泰拉记事列表为空" }
		check(terra.all { it.url.startsWith("/terra-historicus/comic/") }) { "第 1 页混入了其他频道" }

		val talos = parser.getListPage(2, SortOrder.POPULARITY, ContentListFilter())
		check(talos.isNotEmpty()) { "塔罗斯II列表为空" }
		check(talos.all { it.url.startsWith("/talos-ii-historicus/comic/") }) { "第 2 页不是塔罗斯II" }
		check(parser.getListPage(3, SortOrder.POPULARITY, ContentListFilter()).isEmpty()) { "第 3 页应为空" }

		val updates = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
		check(updates.isNotEmpty()) { "更新列表为空" }
		check(updates.all { it.url.startsWith("/terra-historicus/comic/") }) { "更新列表混入了其他频道" }

		val tag = ContentTag("塔罗斯II", "talos-ii-historicus", parser.source)
		val filtered = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(tags = setOf(tag)))
		check(filtered.map { it.url }.toSet() == talos.map { it.url }.toSet()) { "标签筛选结果与第 2 页不一致" }

		val target = terra.first()
		val searched = parser.getListPage(1, SortOrder.POPULARITY, ContentListFilter(query = target.title.take(2)))
		check(searched.any { it.url == target.url }) { "本地搜索没命中自身标题: ${target.title}" }

		val details = parser.getDetails(target)
		checkNotNull(details.description) { "简介为空: ${target.url}" }
		check(details.authors.isNotEmpty()) { "作者为空: ${target.url}" }
		val chapters = details.chapters.orEmpty()
		check(chapters.isNotEmpty()) { "章节为空: ${target.url}" }
		check(chapters.first().number == 1f) { "章节序号未从 1 开始: ${chapters.first().number}" }
		check(chapters.zipWithNext().all { (a, b) -> a.number < b.number }) { "章节序号不是递增" }
		check(chapters.all { it.uploadDate > 0L }) { "章节缺少发布时间" }

		// 阅读页：首尾图片都要能拿到，且是图片响应（取图接口返回带签名的 CDN 地址）
		val pages = parser.getPages(chapters.last())
		check(pages.size >= 2) { "阅读页图片过少: ${pages.size}" }
		listOf(pages.first(), pages.last()).forEach { page ->
			val pageUrl = parser.getPageUrl(page)
			check(pageUrl.startsWith("https://")) { "取图地址异常: $pageUrl" }
			val response = ContentLoaderContextMock.httpClient.newCall(
				Request.Builder().url(pageUrl).head().build(),
			).await()
			response.use {
				check(it.isSuccessful) { "图片不可访问 $pageUrl: HTTP ${it.code}" }
				val type = it.header("Content-Type").orEmpty()
				check(type.startsWith("image/")) { "图片响应类型异常 $pageUrl: $type" }
			}
		}
	}
}
