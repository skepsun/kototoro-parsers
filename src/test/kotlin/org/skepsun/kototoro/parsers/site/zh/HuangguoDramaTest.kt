package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

internal class HuangguoDramaTest {

    private fun offlineContext() = OfflineContentLoaderContext(
        fixtures = mapOf(
            "https://huangguoai.com/api/videos?page=1&size=20" to "huangguo/list.json",
            "https://huangguoai.com/api/videos?page=1&size=20&sort=new" to "huangguo/list.json",
            "https://huangguoai.com/api/videos/category/ai-duanju?page=1&size=20&sort=new" to "huangguo/list.json",
            "https://huangguoai.com/api/search?q=%E5%A5%B3%E7%8E%8B&page=1&size=20" to "huangguo/search.json",
            "https://huangguoai.com/api/videos/12" to "huangguo/detail.json",
            "https://huangguoai.com/api/videos/12/play?ep=2" to "huangguo/play.json",
        ),
    )

    private fun parser(context: OfflineContentLoaderContext = offlineContext()) = HuangguoDrama(context)

    @Test
    fun `builds list urls for feeds, channels and search`() {
        val p = parser()
        assertEquals(
            "https://huangguoai.com/api/videos?page=1&size=20",
            p.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
        // 只有 UPDATED 映射到站点的 sort=new，其他排序参数会被站点静默忽略，不发送
        assertEquals(
            "https://huangguoai.com/api/videos?page=2&size=20&sort=new",
            p.buildListUrl(2, SortOrder.UPDATED, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://huangguoai.com/api/videos/category/ai-manju?page=1&size=20&sort=new",
            p.buildListUrl(
                1,
                SortOrder.UPDATED,
                ContentListFilter(tags = setOf(ContentTag("AI成人漫剧", "ai-manju", p.source))),
            ),
        )
        // 未知标签不拼进路径，回落全站流
        assertEquals(
            "https://huangguoai.com/api/videos?page=1&size=20&sort=new",
            p.buildListUrl(
                1,
                SortOrder.UPDATED,
                ContentListFilter(tags = setOf(ContentTag("自造", "not-a-channel", p.source))),
            ),
        )
        assertEquals(
            "https://huangguoai.com/api/search?q=%E5%A5%B3%E7%8E%8B&page=1&size=20",
            p.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = "女王")),
        )
    }

    @Test
    fun `parses list cards into contents`() = runBlocking {
        val list = parser().getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
        assertEquals(2, list.size)

        val first = list.first()
        assertEquals("测试剧集 A", first.title)
        assertEquals("/video/12", first.url)
        assertEquals("https://huangguoai.com/video/12/", first.publicUrl)
        // auth_key 有时效，入库前裁掉，避免收藏后封面 403
        assertEquals("https://pic.drleao.cn/upload_01/upload/20260101/cover_a.jpg", first.coverUrl)
        assertEquals(first.coverUrl, first.largeCoverUrl)
        assertEquals(0.96f, requireNotNull(first.rating), 0.001f)
        assertEquals(ContentState.FINISHED, first.state)
        assertEquals(setOf("测试作者"), first.authors)
        assertEquals("超能力", first.tags.first().title)

        val second = list[1]
        assertEquals(ContentState.ONGOING, second.state)
        assertEquals(RATING_UNKNOWN, requireNotNull(second.rating))
        assertEquals(setOf("演员甲", "演员乙", "另一个作者"), second.authors)
        assertNull(second.description)
    }

    @Test
    fun `parses details with normalized episode titles`() = runBlocking {
        val p = parser()
        val seed = p.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first()
        val details = p.getDetails(seed)

        assertEquals("测试剧集 A", details.title)
        val chapters = details.chapters.orEmpty()
        assertEquals(3, chapters.size)
        // 站点 episodes[].title 常常是整段剧情简介，这里统一成「第N集」
        assertEquals(listOf("第1集", "第2集", "第3集"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertEquals("/api/videos/12/play?ep=2", chapters[1].url)
        assertEquals(ContentState.FINISHED, details.state)
    }

    @Test
    fun `resolves episode play url to m3u8`() = runBlocking {
        val p = parser()
        val seed = p.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first()
        val chapter = p.getDetails(seed).chapters.orEmpty()[1]

        val pages = p.getPages(chapter)

        assertEquals(1, pages.size)
        assertEquals(
            "https://yd-lphu-hls.hzjvlo.cn/videos5/fedcba9876543210/fedcba9876543210.m3u8" +
                "?auth_key=1793777863-0-0-eeeeeeee&v=3",
            pages.single().url,
        )
        assertEquals("https://huangguoai.com/", pages.single().headers?.get("Referer"))
        assertNotNull(pages.single().headers?.get("User-Agent"))
    }

    @Test
    fun `searches through dedicated endpoint`() = runBlocking {
        val list = parser().getList(0, SortOrder.UPDATED, ContentListFilter(query = "女王"))
        assertEquals(1, list.size)
        assertEquals("测试剧集 A", list.single().title)
    }

    @Test
    fun `skips episodes without a valid number`() {
        val p = parser()
        val episodes = JSONArray(
            """
            [
              {"ep_num": 2, "title": "第2集"},
              {"ep_num": 0, "title": "非法"},
              {"title": "缺少集号"},
              {"ep_num": 1, "title": "第1集"},
              {"ep_num": 2, "title": "重复"}
            ]
            """.trimIndent(),
        )

        val chapters = p.parseChapters(episodes, videoId = 12)

        assertEquals(listOf(1f, 2f), chapters.map { it.number })
        assertEquals("/api/videos/12/play?ep=1", chapters.first().url)
    }

    @Test
    fun `keeps item ids stable across mirror domains`() {
        val p = parser()
        val card = JSONObject("""{"id": 12, "title": "测试剧集 A"}""")

        // id 由站内相对路径生成，与域名无关，换镜像后收藏仍然有效
        val parsed = p.parseCard(card)
        assertEquals(p.parseCard(JSONObject("""{"id": 12, "title": "另一个标题"}""")).id, parsed.id)
        assertEquals("https://huangguoai.com/video/12/", parsed.publicUrl)
        assertTrue(parsed.tags.isEmpty())
    }

    @Test
    fun `normalizes covers and detail paths`() {
        val p = parser()
        assertEquals(
            "https://pic.drleao.cn/a/b.webp",
            p.normalizeCover("https://pic.drleao.cn/a/b.webp?auth_key=1-0-0-abc"),
        )
        assertEquals("https://pic.drleao.cn/a/b.webp", p.normalizeCover("//pic.drleao.cn/a/b.webp"))
        assertNull(p.normalizeCover("   "))
        assertNull(p.normalizeCover("data:image/png;base64,AAAA"))

        assertEquals("/video/12", p.detailPath("/video/12/"))
        assertEquals("/video/12", p.detailPath("https://huangguoai.com/video/12"))
        assertEquals("/api/videos/12", p.apiDetailPath(12))
    }
}
