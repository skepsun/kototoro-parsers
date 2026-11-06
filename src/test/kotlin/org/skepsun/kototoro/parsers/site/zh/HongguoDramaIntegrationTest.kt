package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

/**
 * 需要先在本机（或环境变量 `HONGGUO_BASE_URL` 指向的机器）启动 hongguo-web：
 *
 * ```bash
 * cd hongguo-web/server && npm start         # 默认 127.0.0.1:8000
 * HONGGUO_INTEGRATION_TEST=1 HONGGUO_BASE_URL=127.0.0.1:8123 ./gradlew test --tests "*HongguoDramaIntegrationTest"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "HONGGUO_INTEGRATION_TEST", matches = "1")
class HongguoDramaIntegrationTest {

    private val parser = HongguoDrama(ContentLoaderContextMock).also { parser ->
        val baseUrl = System.getenv("HONGGUO_BASE_URL")?.trim()
        if (!baseUrl.isNullOrEmpty()) {
            (parser.config as SourceConfigMock).set(parser.configKeyDomain, baseUrl)
        }
    }

    @Test
    fun `browses details and streams first and last episode`() = runBlocking {
        val list = parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY)
        assertTrue(list.isNotEmpty(), "热播列表为空")
        assertTrue(list.all { it.url.startsWith("/series/") }, "列表条目缺少内部 id: ${list.map { it.url }}")

        val details = parser.getDetails(list.first())
        val chapters = requireNotNull(details.chapters).takeIf { it.isNotEmpty() } ?: error("分集列表为空")

        val first = fetchHead(parser.getPages(chapters.first()).single().url)
        assertTrue(first.contentType?.startsWith("video/") == true, "首集不是视频: ${first.contentType}")
        assertTrue(first.size > 60_000, "首集首块过小: ${first.size}")

        val last = fetchHead(parser.getPages(chapters.last()).single().url)
        assertTrue(last.size > 0, "末集取不到数据")
    }

    @Test
    fun `searches for a known keyword`() = runBlocking {
        val results = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "魔神人生"))
        assertTrue(results.isNotEmpty(), "搜索无结果")
        assertTrue(results.first().title.isNotEmpty())
    }

    @Test
    fun `browses comic series genre`() = runBlocking {
        val genre = parser.getFilterOptions().availableTags.first { it.key == "comic_series" }
        val list = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(tags = setOf(genre)))
        assertTrue(list.isNotEmpty(), "漫剧列表为空")
    }

    private suspend fun fetchHead(url: String): Holder {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-65535")
            .header("X-Api-Key", parser.resolveApiKey())
            .build()
        ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
            assertTrue(response.code in 200..206, "请求失败: $url -> ${response.code}")
            return Holder(response.body.contentType()?.toString(), response.body.bytes().size.toLong())
        }
    }

    private class Holder(val contentType: String?, val size: Long)
}
