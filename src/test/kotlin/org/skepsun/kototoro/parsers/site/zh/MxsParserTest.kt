package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * 漫小肆 / 92韩漫站群：四个域名共用一套后端，切换域名不能改变解析结果，也不能裂图。
 */
internal class MxsParserTest {

    private val parser = MxsParser(ContentLoaderContextMock)

    private fun mirror(domain: String): MxsParser {
        val instance = MxsParser(ContentLoaderContextMock)
        (instance.config as SourceConfigMock).set(instance.configKeyDomain, domain)
        return instance
    }

    @Test
    fun `expose every domain of the same backend and keep mxshm as default`() {
        assertEquals(
            listOf("www.mxshm.top", "ikanmh.xyz", "jjmhw2.top", "www.jjmhw12.top"),
            parser.configKeyDomain.presetValues.toList(),
        )
    }

    @Test
    fun `build list urls on the default domain`() {
        assertEquals(
            "https://www.mxshm.top/update?page=2",
            parser.buildListUrl(2, ContentListFilter.EMPTY),
        )

        val all = ContentListFilter(tags = setOf(ContentTag("全部漫画", "rec:all", parser.source)))
        val booklist = parser.buildListUrl(2, all)
        assertTrue(booklist!!.startsWith("https://www.mxshm.top/booklist?"), booklist)
        assertTrue(booklist.endsWith("area=-1&end=-1&page=2"), booklist)
    }

    @Test
    fun `build list urls follow the selected mirror`() {
        val ikan = mirror("ikanmh.xyz")
        assertEquals(
            "https://ikanmh.xyz/update?page=1",
            ikan.buildListUrl(1, ContentListFilter.EMPTY),
        )
        assertTrue(
            ikan.buildListUrl(3, ContentListFilter.EMPTY)!!.startsWith("https://ikanmh.xyz/"),
        )
    }

    @Test
    fun `ranklists have a single page`() {
        val rank = ContentListFilter(tags = setOf(ContentTag("人气榜", "rank:popular", parser.source)))
        assertEquals("https://www.mxshm.top/rank", parser.buildListUrl(1, rank))
        assertNull(parser.buildListUrl(2, rank))
    }

    @Test
    fun `parse comic cards`() {
        val document = Jsoup.parse(
            """
            <div class="mh-list col7">
              <div class="mh-item">
                <a href="/book/1054">
                  <div class="mh-cover" style="background-image: url(https://cdn.example/1054.jpg)"></div>
                </a>
                <p class="title"><a href="/book/1054">测试韩漫</a></p>
              </div>
              <div class="mh-item"><p class="title"><a href="/book/1">无封面</a></p></div>
              <div class="mh-item"><a href="/not-a-book/2"></a></div>
            </div>
            """.trimIndent(),
            "https://www.mxshm.top/update?page=1",
        )

        val result = parser.parseComicList(document)

        assertEquals(2, result.size)
        assertEquals("测试韩漫", result[0].title)
        assertEquals("1054", result[0].url)
        assertEquals("https://www.mxshm.top/book/1054", result[0].publicUrl)
        // 站点的封面走背景图，卡片里没有 img 时回落到约定的封面路径
        assertEquals("https://www.mxshm.top/static/upload/book/1054/cover.jpg", result[0].coverUrl)
        assertEquals("https://www.mxshm.top/static/upload/book/1/cover.jpg", result[1].coverUrl)
    }

    @Test
    fun `parse the whole chapter list from a single detail page`() {
        val document = Jsoup.parse(
            """
            <div class="detail-list-title" id="detail-list-title"></div>
            <div id="chapterlistload">
              <ul class="view-win-list detail-list-select" id="detail-list-select">
                <li><a href="/chapter/47438" target="_blank">第1話-开端</a></li>
                <li><a href="/chapter/47439" target="_blank">第2話-承接</a></li>
                <li><a href="/chapter/47440" target="_blank">第3話-收束</a></li>
              </ul>
            </div>
            """.trimIndent(),
            "https://ikanmh.xyz/book/1054",
        )

        val chapters = mirror("ikanmh.xyz").parseChapters(document, content("1054"))

        assertEquals(listOf("47438", "47439", "47440"), chapters.map { it.url })
        assertEquals(listOf("第1話-开端", "第2話-承接", "第3話-收束"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
    }

    @Test
    fun `rewrite image hosts onto the selected mirror`() {
        // 真实阅读页里的图片属于站群中的另一个域，必须重写到当前域名才能取到
        val document = Jsoup.parse(
            """
            <div class="imgBox">
              <img class="lazy" data-original="https://www.jjmhw12.top/static/upload/book/1054/47438/1.jpg">
              <img class="lazy" data-original="https://www.jjmhw12.top/static/upload/book/1054/47438/2.jpg">
            </div>
            """.trimIndent(),
            "https://ikanmh.xyz/chapter/47438",
        )

        val pages = mirror("ikanmh.xyz").parsePages(document)

        assertEquals(
            listOf(
                "https://ikanmh.xyz/static/upload/book/1054/47438/1.jpg",
                "https://ikanmh.xyz/static/upload/book/1054/47438/2.jpg",
            ),
            pages.map { it.url },
        )
        assertEquals(pages.map { it.url }, pages.map { it.preview })
    }

    private fun content(url: String = "1054") = Content(
        id = 1,
        title = "测试韩漫",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://ikanmh.xyz/book/$url",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = parser.source,
    )
}
