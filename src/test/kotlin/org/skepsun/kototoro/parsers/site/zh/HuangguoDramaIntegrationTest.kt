package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await

@EnabledIfEnvironmentVariable(named = "HUANGGUO_INTEGRATION_TEST", matches = "1")
class HuangguoDramaIntegrationTest {

    private val parser = HuangguoDrama(ContentLoaderContextMock)

    @Test
    fun `browses list details and plays the first episode`() = runBlocking {
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
        assertTrue(list.isNotEmpty(), "列表为空")
        assertTrue(list.all { it.url.startsWith("/video/") })

        val details = parser.getDetails(list.first())
        val chapters = requireNotNull(details.chapters).takeIf { it.isNotEmpty() } ?: error("章节列表为空")
        assertTrue(chapters.all { it.url.startsWith("/api/videos/") })

        val pages = parser.getPages(chapters.first())
        assertEquals(1, pages.size)
        val streamUrl = pages.single().url
        assertTrue(streamUrl.contains(".m3u8"), "播放地址不是 m3u8: $streamUrl")

        // 校验 m3u8 与首个分片都可访问（短剧 HLS 为 AES-128 加密，密钥在播放列表里）
        val playlist = fetch(streamUrl)
        assertTrue(playlist.contains("#EXTM3U"), "播放列表非法")
        val segmentUrl = requireNotNull(
            playlist.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") },
        ).trim()
        val segment = fetchBytes(segmentUrl)
        assertTrue(segment > 1_024, "分片体积异常: $segment")
    }

    @Test
    fun `searches and browses a channel`() = runBlocking {
        val searched = parser.getList(0, SortOrder.UPDATED, ContentListFilter(query = "短剧"))
        assertTrue(searched.isNotEmpty(), "搜索无结果")

        val channel = parser.getFilterOptions().availableTags.first { it.key == "ai-duanju" }
        val channelList = parser.getList(0, SortOrder.UPDATED, ContentListFilter(tags = setOf(channel)))
        assertTrue(channelList.isNotEmpty(), "频道列表为空")
    }

    @Test
    fun `decrypts live cover bytes`() = runBlocking {
        val cover = requireNotNull(parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY).first().coverUrl)
        val request = Request.Builder().url(cover).build()
        ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
            assertEquals(200, response.code)
            val decoded = HuangguoImageDecoder.decodeResponse(response)
            assertTrue(
                decoded.body.contentType()?.type == "image",
                "封面未被解密: ${decoded.body.contentType()}",
            )
            assertTrue(decoded.body.bytes().size > 4_096)
        }
    }

    private suspend fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36")
            .header("Referer", "https://${parser.domain}/")
            .build()
        ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
            assertEquals(200, response.code, "请求失败: $url")
            return response.body.string()
        }
    }

    private suspend fun fetchBytes(url: String): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36")
            .build()
        ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
            assertEquals(200, response.code, "分片请求失败: $url")
            return response.body.bytes().size.toLong()
        }
    }
}
