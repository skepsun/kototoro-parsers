package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.json.toJSONObjectOrNull

/**
 * 香香腐宅（boylove）：列表与搜索都走同源 JSON 接口，章节目录藏在 JS 字符串字面量里。
 */
internal class BoyloveParserTest {

    private val parser = BoyloveParser(ContentLoaderContextMock)

    private fun html(name: String) = Jsoup.parse(fixtureText(name))

    private fun json(name: String): JSONObject? = fixtureText(name).toJSONObjectOrNull()

    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/boylove/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun mirror(domain: String): BoyloveParser {
        val instance = BoyloveParser(ContentLoaderContextMock)
        (instance.config as SourceConfigMock).set(instance.configKeyDomain, domain)
        return instance
    }

    private fun tag(key: String) = ContentTag(key, key, parser.source)

    private fun manga(bookId: String) = Content(
        id = parser.generateUid(bookId),
        title = "",
        altTitles = emptySet(),
        url = bookId,
        publicUrl = "https://boylove.cc/home/book/index/id/$bookId",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        authors = emptySet(),
        state = null,
        source = parser.source,
        chapters = emptyList(),
        description = "",
    )

    @Test
    fun `expose known mirrors and keep boylove cc as default`() {
        assertEquals(listOf("boylove.cc", "boylove4.xyz"), parser.configKeyDomain.presetValues.toList())
        assertEquals("boylove.cc", parser.configKeyDomain.defaultValue)
    }

    @Test
    fun `offer only the sort orders the site really supports`() {
        assertEquals(setOf(SortOrder.NEWEST, SortOrder.POPULARITY), parser.availableSortOrders)
        assertEquals(SortOrder.NEWEST, parser.defaultSortOrder)
    }

    @Test
    fun `build list urls on the default domain`() {
        // cate-tag-progress-order-page-is18-1-vip
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-2-1-1-2-1-2?mt=0",
            parser.buildListUrl(1, SortOrder.NEWEST, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-2-2-3-2-1-2?mt=0",
            parser.buildListUrl(3, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
    }

    @Test
    fun `map origin tags onto the mt query parameter`() {
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-2-1-1-2-1-2?mt=1",
            parser.buildListUrl(1, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag("origin:1")))),
        )
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-2-1-1-2-1-2?mt=2",
            parser.buildListUrl(1, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag("origin:2")))),
        )
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-2-1-1-2-1-2?mt=3",
            parser.buildListUrl(1, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag("origin:3")))),
        )
    }

    @Test
    fun `map state adult and payment filters onto path segments`() {
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-1-1-1-2-1-2?mt=1",
            parser.buildListUrl(
                1,
                SortOrder.NEWEST,
                ContentListFilter(tags = setOf(tag("origin:1")), states = setOf(ContentState.FINISHED)),
            ),
        )
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-0-1-1-1-1-0?mt=1",
            parser.buildListUrl(
                1,
                SortOrder.NEWEST,
                ContentListFilter(
                    tags = setOf(tag("origin:1"), tag("is18:1"), tag("vip:0")),
                    states = setOf(ContentState.ONGOING),
                ),
            ),
        )
        // 接口每个维度只吃一个值，状态多选等价于「全部」
        assertEquals(
            "https://boylove.cc/home/api/cate/tp/1-0-2-1-1-2-1-2?mt=0",
            parser.buildListUrl(
                1,
                SortOrder.NEWEST,
                ContentListFilter(states = setOf(ContentState.ONGOING, ContentState.FINISHED)),
            ),
        )
    }

    @Test
    fun `build urls follow the selected mirror`() {
        val mirror = mirror("boylove4.xyz")
        assertEquals(
            "https://boylove4.xyz/home/api/cate/tp/1-0-2-1-2-2-1-2?mt=1",
            mirror.buildListUrl(2, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag("origin:1")))),
        )
        assertEquals(
            "https://boylove4.xyz/home/api/searchk?keyword=a&type=0&pageNo=1",
            mirror.searchUrl("a", 1),
        )
    }

    @Test
    fun `encode search keyword and pin the manga type`() {
        assertEquals(
            "https://boylove.cc/home/api/searchk?keyword=%E9%9F%A9%E6%BC%AB&type=0&pageNo=1",
            parser.searchUrl("韩漫", 1),
        )
        assertEquals(
            "https://boylove.cc/home/api/searchk?keyword=%E9%9F%A9%E6%BC%AB&type=0&pageNo=2",
            parser.searchUrl("韩漫", 2),
        )
    }

    @Test
    fun `parse list json into domain independent content`() {
        val list = parser.parseContentList(json("list.json"))
        assertEquals(4, list.size)
        val first = list.first()
        assertEquals("Passion：Raga", first.title)
        assertEquals("34991", first.url)
        assertEquals("https://boylove.cc/home/book/index/id/34991", first.publicUrl)
        // 接口里的封面是相对路径，站点模板固定拼到图床域名
        assertEquals(
            "https://img.boylove.cc/bookimages/img/20250404/65b6b82d4c74b336e76c8cf936f45229.webp",
            first.coverUrl,
        )
        assertEquals(setOf("알터", "듀비듐", "유우지"), first.authors)
        assertEquals(ContentState.ONGOING, first.state)
        assertEquals(parser.source, first.source)
        // id 必须是站点内部 id，不能带域名信息
        assertTrue(list.all { it.url.toIntOrNull() != null }, list.toString())
        assertEquals(ContentState.FINISHED, list.last().state)
    }

    @Test
    fun `parse search json with the same item shape`() {
        val list = parser.parseContentList(json("search.json"))
        assertEquals(2, list.size)
        assertEquals("Passion：Raga", list.first().title)
        assertTrue(list.all { it.title.isNotEmpty() && it.url.isNotEmpty() })
    }

    @Test
    fun `tolerate an empty payload without a list key`() {
        // 无结果时站点只回 {firstPage,lastPage,code,test}，根本没有 list
        assertFalse(json("search_empty.json")!!.optJSONObject("result")!!.has("list"))
        assertTrue(parser.parseContentList(json("search_empty.json")).isEmpty())
        assertTrue(parser.parseContentList(null).isEmpty())
    }

    @Test
    fun `parse detail header and metadata`() {
        val details = parser.parseDetails(html("detail.html"), manga("20513"))
        assertEquals("日出之家/日昇之屋", details.title)
        // 页头有一份被注释掉的旧作者行，解析时应跳过注释
        assertEquals(setOf("정석찬"), details.authors)
        assertEquals(
            "https://img.boylove.cc/bookimages/img/20230819/3bb3ce89ecd42031e5a1f0939a76935a.webp",
            details.coverUrl,
        )
        assertEquals(ContentState.ONGOING, details.state)
        val description = details.description.orEmpty()
        assertTrue(description.startsWith("《해 뜨는 집》"), description)
        assertFalse(description.contains("简介"), description)
        assertEquals(17, details.tags.size)
        assertEquals("韩漫", details.tags.first().title)
        assertTrue(details.tags.all { it.key == it.title })
    }

    @Test
    fun `parse chapters in reading order`() {
        val chapters = parser.parseChapters(html("detail.html"))
        assertEquals(5, chapters.size)
        // 站点已按阅读顺序输出，不要反转
        assertEquals(listOf("預告", "第01話", "第02話", "第03話", "第04話"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), chapters.map { it.number })
        assertEquals("1056112", chapters.first().url)
        assertTrue(chapters.all { it.url.toIntOrNull() != null })
        assertTrue(chapters.all { it.uploadDate > 0L })
        assertEquals(chapters.size, chapters.map { it.id }.toSet().size)
    }

    @Test
    fun `skip paid chapters but keep the site chapter position`() {
        val chapters = parser.parseChapters(html("detail_vip.html"))
        assertEquals(listOf("預告", "第01話", "第03話", "第04話"), chapters.map { it.title })
        // 第 3 章是 isvip=1，被剔除后编号仍反映它在站点的真实位置
        assertEquals(listOf(1f, 2f, 4f, 5f), chapters.map { it.number })
        assertTrue(chapters.none { it.url == "1056266" })
    }

    @Test
    fun `parse reader images in order and skip the footer ad`() {
        val pages = parser.parsePages(html("chapter.html"))
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.url.startsWith("https://img.boylove.cc/bookimages/") })
        assertEquals(
            "https://img.boylove.cc/bookimages/20250108/DZRUjqchCKaXMD1Lk1UZBbqphUX1OyREPKZ.webp?w=2250_3200",
            pages.first().url,
        )
        // 容器末尾的站内推荐按钮 data-original 为空，不能当成页面
        assertTrue(pages.none { it.url.isBlank() })
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
    }

    @Test
    fun `advertise single tag selection and support search`() {
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isSearchWithFiltersSupported)
    }

    @Test
    fun `parser is registered as an adult chinese source`() {
        assertEquals(ContentType.HENTAI_MANGA, parser.source.contentType)
        assertEquals("zh", parser.source.locale)
    }
}
