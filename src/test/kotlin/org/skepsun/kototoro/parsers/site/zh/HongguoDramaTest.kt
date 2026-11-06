package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

internal class HongguoDramaTest {

    private fun offlineContext(
        transformBody: (Request, String) -> String = { _, body -> body },
    ) = OfflineContentLoaderContext(
        fixtures = mapOf(
            "$BASE/api-key" to "hongguo/api-key.json",
            "$BASE/browse?genre=short_play&sort=hot_score&limit=20&offset=0" to "hongguo/browse.json",
            "$BASE/browse?genre=short_play&sort=hot_score&limit=20&offset=2&session_id=site" to
                "hongguo/browse_page2.json",
            "$BASE/browse?genre=short_play&sort=online_time&limit=20&offset=0" to "hongguo/browse_new.json",
            "$BASE/search?q=%E9%AD%94%E7%A5%9E%E4%BA%BA%E7%94%9F&limit=20&offset=0" to "hongguo/search.json",
            "$BASE/episodes?series_id=7474811850085895230" to "hongguo/episodes.json",
            "$BASE/video_url?vid=7474846201993497624" to "hongguo/video_url.json",
            "$BASE/video_url?vid=111" to "hongguo/video_url_bvc2.json",
        ),
        transformBody = transformBody,
    )

    private fun parser(context: OfflineContentLoaderContext = offlineContext()) = HongguoDrama(context)

    @Test
    fun `exposes genres as tags and video content type`() = runBlocking {
        val options = parser().getFilterOptions()
        assertEquals(
            listOf("short_play", "comic_series", "ai_series"),
            options.availableTags.map { it.key },
        )
        assertTrue(options.availableTags.map { it.title }.containsAll(listOf("真人短剧", "漫剧", "AI短剧")))
    }

    @Test
    fun `builds list urls for feed, sort, genre and search`() {
        val p = parser()
        assertEquals(
            "$BASE/browse?genre=short_play&sort=hot_score&limit=20&offset=0",
            p.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
        assertEquals(
            "$BASE/browse?genre=short_play&sort=online_time&limit=20&offset=0",
            p.buildListUrl(1, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
        // 体裁标签落到 genre 参数
        assertEquals(
            "$BASE/browse?genre=comic_series&sort=hot_score&limit=20&offset=0",
            p.buildListUrl(
                1,
                SortOrder.POPULARITY,
                ContentListFilter(tags = setOf(ContentTag("漫剧", "comic_series", p.source))),
            ),
        )
        // 详情里的内容标签没有筛选接口，退化成关键词搜索
        assertEquals(
            "$BASE/search?q=%E9%80%86%E8%A2%AD&limit=20&offset=0",
            p.buildListUrl(
                1,
                SortOrder.POPULARITY,
                ContentListFilter(tags = setOf(ContentTag("逆袭", "逆袭", p.source))),
            ),
        )
        assertEquals(
            "$BASE/search?q=%E9%AD%94%E7%A5%9E%E4%BA%BA%E7%94%9F&limit=20&offset=0",
            p.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter(query = "魔神人生")),
        )
    }

    @Test
    fun `uses configured domain with explicit scheme as is`() {
        val p = parser()
        (p.config as SourceConfigMock).set(p.configKeyDomain, "https://hg.example.com:8443")
        assertEquals(
            "https://hg.example.com:8443/browse?genre=short_play&sort=hot_score&limit=20&offset=0",
            p.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
    }

    @Test
    fun `parses list page and continues with gateway cursor`() = runBlocking {
        val context = offlineContext()
        val p = parser(context)
        val first = p.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY)
        assertEquals(2, first.size)
        val card = first.first()
        assertEquals("/series/7701935931787316286", card.url)
        assertEquals("十八岁太奶奶驾到，重整家族荣耀第五部", card.title)
        assertTrue(card.title.isNotEmpty())
        assertTrue(card.coverUrl!!.startsWith("https://"))
        assertEquals(RATING_UNKNOWN, card.rating)
        assertTrue(card.tags.map { it.title }.contains("穿越"))

        // 第 2 页必须沿用第 1 页返回的 session_id / next_offset，而不是 page*pageSize
        val second = p.getList(20, SortOrder.POPULARITY, ContentListFilter.EMPTY)
        assertEquals(1, second.size)
        assertTrue(
            context.requests.contains(
                "$BASE/browse?genre=short_play&sort=hot_score&limit=20&offset=2&session_id=site",
            ),
            "第 2 页地址不对: ${context.requests}",
        )

        // has_more=false 之后再翻页不再发请求
        val third = p.getList(40, SortOrder.POPULARITY, ContentListFilter.EMPTY)
        assertTrue(third.isEmpty())
        // 第 1 页 + 第 2 页两次请求，第 3 页按 has_more=false 直接短路
        assertEquals(2, context.requests.count { it.contains("/browse?") })
    }

    @Test
    fun `parses search results`() = runBlocking {
        val p = parser()
        val results = p.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "魔神人生"))
        assertEquals(2, results.size)
        assertEquals("魔神人生", results.first().title)
        assertNotEquals(results.first().id, results.last().id)
    }

    @Test
    fun `parses details into sorted chapters`() = runBlocking {
        val p = parser()
        // 魔神人生：搜索结果的 series_id 与 episodes 夹具对应
        val card = p.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "魔神人生")).first()
        val details = p.getDetails(card)
        assertEquals("魔神人生", details.title)
        assertTrue(details.description!!.startsWith("叶秋流落二十年"))
        assertEquals(ContentState.FINISHED, details.state)
        val chapters = requireNotNull(details.chapters)
        assertEquals(4, chapters.size)
        assertEquals(listOf(1f, 2f, 3f, 100f), chapters.map { it.number })
        assertEquals(listOf("第1集", "第2集", "第3集", "第100集"), chapters.map { it.title })
        assertEquals("/video/7474846201993497624", chapters.first().url)
        assertTrue(details.tags.map { it.title }.contains("逆袭"))
    }

    @Test
    fun `builds playable stream url with api key header`() = runBlocking {
        val p = parser()
        val detailCard = p.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "魔神人生")).first()
        val chapter = requireNotNull(p.getDetails(detailCard).chapters).first()
        val page = p.getPages(chapter).single()
        assertEquals("$BASE/stream?vid=7474846201993497624&quality=best", page.url)
        assertEquals("test-key-0001", page.headers!!["X-Api-Key"])
    }

    @Test
    fun `sends configured api key instead of fetching it`() = runBlocking {
        var header: String? = null
        val context = offlineContext(transformBody = { request, body ->
            header = request.header("X-Api-Key")
            body
        })
        val p = parser(context)
        (p.config as SourceConfigMock).set(p.apiKeyConfig, "manual-key")
        assertTrue(p.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY).isNotEmpty())
        assertEquals("manual-key", header)
        assertTrue(context.requests.none { it.endsWith("/api-key") }, "手填密钥时不应再请求 /api-key")
    }

    @Test
    fun `reports actionable error when service is unreachable`() {
        val p = HongguoDrama(OfflineContentLoaderContext())
        val error = assertThrows(ParseException::class.java) {
            runBlocking { p.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY) }
        }
        assertTrue(error.shortMessage!!.contains("hongguo-web"), error.shortMessage)
    }

    @Test
    fun `refuses episode with bytevc2 only source`() {
        val p = parser()
        val chapter = ContentChapter(
            id = 1L,
            title = "第1集",
            number = 1f,
            volume = 0,
            url = "/video/111",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = p.source,
        )
        val error = assertThrows(ParseException::class.java) {
            runBlocking { p.getPages(chapter) }
        }
        assertTrue(error.shortMessage!!.contains("ByteVC2"), error.shortMessage)
    }

    @Test
    fun `fails on details without episodes`() {
        val p = parser()
        val error = assertThrows(ParseException::class.java) {
            p.parseChapters(JSONArray(), "$BASE/episodes?series_id=1")
        }
        assertTrue(error.shortMessage!!.contains("分集"))
    }

    private companion object {
        private const val BASE = "http://127.0.0.1:8000"
    }
}
