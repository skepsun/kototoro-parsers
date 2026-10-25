package org.skepsun.kototoro.parsers.site.all

import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.network.UserAgents
import java.io.File

/**
 * 手动诊断工具：抓取指定 URL 并保存响应，用于确认站点搜索/列表的真实结构与参数。
 *
 * 用法：
 * SEARCH_PROBE_URLS="https://example.com/a,https://example.com/b" ./gradlew test --tests "*SearchProbeTest"
 *
 * 响应保存在 build/reports/search-probe/。
 */
@EnabledIfEnvironmentVariable(named = "SEARCH_PROBE_URLS", matches = ".+")
class SearchProbeTest {

	@Test
	fun probe() {
		val outDir = File("build/reports/search-probe").apply { mkdirs() }
		val urls = System.getenv("SEARCH_PROBE_URLS").orEmpty()
			.split(',')
			.map { it.trim() }
			.filter { it.isNotEmpty() }

		urls.forEachIndexed { index, url ->
			val request = Request.Builder()
				.url(url)
				.header("User-Agent", UserAgents.CHROME_DESKTOP)
				.header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
				.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
				.build()
			val result = runCatching {
				ContentLoaderContextMock.httpClient.newCall(request).execute().use { response ->
					val body = response.body.string()
					val file = File(outDir, "probe-$index.html")
					file.writeText(body)
					val doc = org.jsoup.Jsoup.parse(body)
					val fictionItems = doc.select(".fiction-list-item").size
					val emptyMarkers = listOf("no results", "nothing found", "不要", "没有找到", "暫無")
						.filter { body.contains(it, ignoreCase = true) }
					"code=${response.code} size=${body.length} fictionItems=$fictionItems markers=$emptyMarkers file=${file.path}"
				}
			}
			println("probe[$index] $url -> ${result.getOrElse { "error: $it" }}")
		}
	}
}
