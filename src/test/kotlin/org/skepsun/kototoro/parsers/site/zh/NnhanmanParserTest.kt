package org.skepsun.kototoro.parsers.site.zh

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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid

/**
 * 鸟鸟韩漫（nnhanman）：域名会随站点公告轮换，题材用中文路径段，章节目录倒序。
 */
internal class NnhanmanParserTest {

    private val parser = NnhanmanParser(ContentLoaderContextMock)

    private fun fixture(name: String) = Jsoup.parse(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/nnhanman/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() },
    )

    private fun mirror(domain: String): NnhanmanParser {
        val instance = NnhanmanParser(ContentLoaderContextMock)
        (instance.config as SourceConfigMock).set(instance.configKeyDomain, domain)
        return instance
    }

    private fun manga(slug: String) = Content(
        id = parser.generateUid(slug),
        title = "",
        altTitles = emptySet(),
        url = slug,
        publicUrl = "https://nnhanman.xyz/comic/$slug.html",
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
    fun `expose announced mirrors and keep nnhanman as default`() {
        assertEquals(
            listOf(
                "nnhanman.xyz",
                "nnhanman66.com",
                "nnhanman88.com",
                "nnhm81.com",
                "nnhm92.com",
                "nnhm91.com",
            ),
            parser.configKeyDomain.presetValues.toList(),
        )
        assertEquals("nnhanman.xyz", parser.configKeyDomain.defaultValue)
    }

    @Test
    fun `offer only the sort orders the site really supports`() {
        assertEquals(setOf(SortOrder.NEWEST, SortOrder.POPULARITY), parser.availableSortOrders)
        assertEquals(SortOrder.NEWEST, parser.defaultSortOrder)
    }

    @Test
    fun `build list urls on the default domain`() {
        assertEquals(
            "https://nnhanman.xyz/comics/all/ob/time/st/all",
            parser.buildListUrl(1, SortOrder.NEWEST, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://nnhanman.xyz/comics/all/ob/time/st/all/page/2",
            parser.buildListUrl(2, SortOrder.NEWEST, ContentListFilter.EMPTY),
        )
        assertEquals(
            "https://nnhanman.xyz/comics/all/ob/hits/st/all/page/3",
            parser.buildListUrl(3, SortOrder.POPULARITY, ContentListFilter.EMPTY),
        )
    }

    @Test
    fun `encode chinese genre as path segment and map state filter`() {
        val filter = ContentListFilter(
            tags = setOf(ContentTag("正妹", "genre:正妹", parser.source)),
            states = setOf(ContentState.FINISHED),
        )
        assertEquals(
            "https://nnhanman.xyz/comics/%E6%AD%A3%E5%A6%B9/ob/time/st/completed",
            parser.buildListUrl(1, SortOrder.NEWEST, filter),
        )
        assertEquals(
            "https://nnhanman.xyz/comics/%E6%AD%A3%E5%A6%B9/ob/time/st/serialized/page/2",
            parser.buildListUrl(2, SortOrder.NEWEST, filter.copy(states = setOf(ContentState.ONGOING))),
        )
        // 站点一次只认一个状态，多选等价于「全部」
        assertEquals(
            "https://nnhanman.xyz/comics/all/ob/time/st/all",
            parser.buildListUrl(
                1,
                SortOrder.NEWEST,
                ContentListFilter(states = setOf(ContentState.ONGOING, ContentState.FINISHED)),
            ),
        )
    }

    @Test
    fun `build list urls follow the selected mirror`() {
        val mirror = mirror("nnhm92.com")
        assertEquals(
            "https://nnhm92.com/comics/all/ob/time/st/all/page/2",
            mirror.buildListUrl(2, SortOrder.NEWEST, ContentListFilter.EMPTY),
        )
        assertEquals("https://nnhm92.com/catalog.php?key=a", mirror.searchUrl("a", 1))
    }

    @Test
    fun `encode search keyword and paginate`() {
        assertEquals(
            "https://nnhanman.xyz/catalog.php?key=%E7%A7%98%E5%AF%86%E6%95%99%E5%AD%B8",
            parser.searchUrl("秘密教學", 1),
        )
        assertEquals(
            "https://nnhanman.xyz/catalog.php?key=%E7%A7%98%E5%AF%86%E6%95%99%E5%AD%B8&page=2",
            parser.searchUrl("秘密教學", 2),
        )
    }

    @Test
    fun `parse list cards into domain independent content`() {
        val list = parser.parseComicList(fixture("list.html"))
        assertEquals(3, list.size)
        val first = list.first()
        assertEquals("暢所慾言筆記本", first.title)
        assertEquals("chang-suo-yu-yan-bi-ji-ben", first.url)
        assertEquals("https://nnhanman.xyz/comic/chang-suo-yu-yan-bi-ji-ben.html", first.publicUrl)
        assertEquals("https://new.niaopic.com/202611/20261103142149480.jpg", first.coverUrl)
        assertEquals(parser.source, first.source)
        assertTrue(list.all { it.url.isNotEmpty() && !it.url.contains("/") }, list.toString())
    }

    @Test
    fun `parse search results with the same card markup`() {
        val list = parser.parseComicList(fixture("search.html"))
        assertEquals(2, list.size)
        assertTrue(list.all { it.title.isNotEmpty() && it.url.isNotEmpty() })
    }

    @Test
    fun `parse detail header from fixture`() {
        val details = parser.parseDetails(fixture("detail.html"), manga("mi-mi-jiao-xue"))
        // 标题在 h1 里带书名号，需要剥掉
        assertEquals("秘密教學", details.title)
        assertEquals(setOf("美娜讚", "鋼鐵王"), details.authors)
        assertEquals("https://thumb.niaopic.com/upload_s/202209/20220930012101936.jpg", details.coverUrl)
        assertEquals(ContentState.ONGOING, details.state)
        val description = details.description.orEmpty()
        assertTrue(description.startsWith("13歲那年成為孤兒的子豪"), description)
        assertFalse(description.startsWith("介绍"), description)
        assertEquals(
            setOf("正妹", "有夫之婦", "後宮", "不倫"),
            details.tags.map { it.title }.toSet(),
        )
    }

    @Test
    fun `reverse the chapter list into reading order`() {
        val manga = manga("mi-mi-jiao-xue")
        val chapters = parser.parseChapters(fixture("detail.html"), manga)
        assertEquals(6, chapters.size)
        // 站点倒序输出（第326話在最前），反转后第一章是第321話
        assertTrue(chapters.first().title.orEmpty().startsWith("第321話"), chapters.first().title.orEmpty())
        assertTrue(chapters.last().title.orEmpty().startsWith("第326話"), chapters.last().title.orEmpty())
        assertEquals(1f, chapters.first().number)
        assertEquals(6f, chapters.last().number)
        assertTrue(chapters.all { !it.url.startsWith("/") && it.url.endsWith(".html") })
        assertEquals("comic/mi-mi-jiao-xue/chapter-85899.html", chapters.first().url)
    }

    @Test
    fun `parse reader images in order and skip non page images`() {
        val pages = parser.parsePages(fixture("chapter.html"))
        assertEquals(4, pages.size)
        assertTrue(pages.all { it.url.startsWith("https://img.nnpic.xyz/upload_s/") })
        assertTrue(pages.none { it.url.contains("/images/logo.png") })
        assertEquals(
            "https://img.nnpic.xyz/upload_s/2026111014/20261110144832634.jpg",
            pages.first().url,
        )
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
    }

    @Test
    fun `parser is registered as an adult chinese source`() {
        assertEquals(ContentType.HENTAI_MANGA, parser.source.contentType)
        assertEquals("zh", parser.source.locale)
    }
}
