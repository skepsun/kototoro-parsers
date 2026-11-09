package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.CloudFlareProtectedException
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * ACG漫画网 在线验证（默认跳过）：ACGXMH_INTEGRATION_TEST=1。
 * 重点验证搜索路由：`/?q=` 会被服务端忽略（永远返回推荐流），
 * 真正的路由是 `/q/<关键词>-<页>.html`：关键词命中标签/角色/作品名时站点 302 到 www 标签页；
 * 其余关键词 302 到 search.acgxmh.com，该主机对非浏览器客户端有 Cloudflare 校验
 * （应用内由宿主浏览器完成验证）。
 */
@EnabledIfEnvironmentVariable(named = "ACGXMH_INTEGRATION_TEST", matches = "1")
class AcgxmhIntegrationTest {

	private val parser = Acgxmh(ContentLoaderContextMock)
	private val videoParser = AcgxmhVideo(ContentLoaderContextMock)

	@Test
	fun browseListWorks() = runBlocking {
		val first = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
		check(first.isNotEmpty()) { "列表为空" }
		check(first.map { it.id }.distinct().size == first.size) { "列表 id 有重复" }
		check(first.all { it.url.startsWith("/") }) { "列表 url 不是站内相对路径: ${first.map { it.url }}" }
		check(first.first().coverUrl != null) { "列表封面为空: ${first.first().url}" }
		println("[ACGXMH] browse=${first.size}, first=${first.first().title}")
	}

	@Test
	fun searchDoesNotFallBackToTheBrowseFeed() = runBlocking {
		val browse = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()).map { it.url }
		delay(2_000)

		// 「八重神子」是站点角色标签名，/q/ 会 302 到 /characters/yae-miko.html（www，无校验）
		val search = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "八重神子"))
		check(search.isNotEmpty()) { "搜索「八重神子」没有结果" }
		check(search.map { it.url } != browse) { "搜索结果与浏览推荐流完全相同（搜索参数仍未生效）" }
		check(search.all { it.id != 0L }) { "搜索结果 id 异常" }
		println("[ACGXMH] search=八重神子 -> ${search.size} 条, first=${search.first().title}")
	}

	@Test
	fun titleKeywordSearchIsRoutedToTheSiteSearchHost() = runBlocking<Unit> {
		val browse = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()).map { it.url }
		delay(2_000)

		// 「诱惑病房」是作品标题而不是标签名，/q/ 会 302 到 search.acgxmh.com。
		// 该主机带 Cloudflare 托管校验：要么拿到真结果，
		// 要么抛 CloudFlareProtectedException（应用内转浏览器验证）。
		val outcome = runCatching {
			parser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "诱惑病房"))
		}
		outcome.onFailure { e ->
			check(e is CloudFlareProtectedException) { "搜索「诱惑病房」出现非 Cloudflare 的异常: $e" }
			println("[ACGXMH] title search blocked by Cloudflare (expected on datacenter IPs)")
		}.onSuccess { items ->
			check(items.map { it.url } != browse) { "搜索结果与浏览推荐流完全相同（搜索参数仍未生效）" }
			println("[ACGXMH] title search -> ${items.size} 条, first=${items.firstOrNull()?.title}")
		}
	}

	@Test
	fun doujinWorkIndexIsNotEmpty() = runBlocking<Unit> {
		// /anime/ 是「戏仿列表」，作品卡结构和其它大类索引页（dl.specials）不同
		val group = parser.loadIndexTags("戏仿", "/anime/", pages = 2, itemSelector = AcgxmhBase.WORK_ITEM_SELECTOR)
		check(group.tags.isNotEmpty()) { "戏仿 标签组为空" }
		check(group.tags.all { it.key.startsWith("path:/anime/") }) {
			"戏仿 标签 key 异常: ${group.tags.map { it.key }}"
		}
		println("[ACGXMH] 戏仿 tags=${group.tags.size}, first=${group.tags.first().title} -> ${group.tags.first().key}")
	}

	@Test
	fun videoSearchReturnsVideoItems() = runBlocking<Unit> {
		// 标签页同时含 /gif 与 /h|/hentai 条目，动画源只保留前者
		val items = videoParser.getListPage(1, SortOrder.UPDATED, ContentListFilter(query = "原神"))
		check(items.isNotEmpty()) { "动画源搜索「原神」为空（标签页里的 /gif 条目没有被保留）" }
		val nonVideo = items.filterNot { it.url.startsWith("/gif/") || it.url.startsWith("/hanime/") || it.url.startsWith("/asmr/") }
		check(nonVideo.isEmpty()) { "动画源混入非视频条目: ${nonVideo.take(3).map { it.url }}" }
		println("[ACGXMH] video search=原神 -> ${items.size} 条, first=${items.first().url}")
	}
}
