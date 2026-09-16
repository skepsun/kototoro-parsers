package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 电子书源在线验证。默认不执行，按需启用：
 * - LIBRARYGENESIS_INTEGRATION_TEST=1（无需账号）
 * - ZLIBRARY_INTEGRATION_TEST=1（下载需 remix_userid/remix_userkey cookie，浏览搜索匿名可用）
 */
@EnabledIfEnvironmentVariable(named = "EBOOKS_INTEGRATION_TEST", matches = "1")
class EbooksIntegrationTest {

	private val context = ContentLoaderContextMock

	@Test
	@EnabledIfEnvironmentVariable(named = "LIBRARYGENESIS_INTEGRATION_TEST", matches = "1")
	fun libraryGenesisEndToEnd() = runBlocking {
		val parser = LibraryGenesis(context)
		val list = parser.getListPage(1, SortOrder.RELEVANCE, ContentListFilter(query = "godel escher bach"))
		check(list.isNotEmpty()) { "libgen 搜索无结果" }
		val target = list.first { it.url.contains("ads.php") }
		val details = parser.getDetails(target)
		check(details.title.isNotBlank()) { "libgen 详情标题为空" }
		val chapter = checkNotNull(details.chapters?.firstOrNull()) { "libgen 缺少下载章节" }
		val page = parser.getPages(chapter).single()
		val fileUrl = parser.getPageUrl(page)
		check(fileUrl.contains("get.php")) { "libgen 下载链接异常: $fileUrl" }
		println("[LIBGEN] ok: ${details.title} -> $fileUrl")
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "ZLIBRARY_INTEGRATION_TEST", matches = "1")
	fun zLibraryBrowse() = runBlocking {
		val parser = ZLibrary(context)
		val list = parser.getListPage(1, SortOrder.RELEVANCE, ContentListFilter(query = "camus"))
		check(list.isNotEmpty()) { "z-library 搜索无结果（可能被 DiamWall 或登录墙拦截）" }
		val details = parser.getDetails(list.first())
		check(details.title.isNotBlank()) { "z-library 详情标题为空" }
		println("[ZLIB] ok: ${details.title}, chapters=${details.chapters?.size ?: 0}, auth=${parser.isAuthorized()}")
	}
}
