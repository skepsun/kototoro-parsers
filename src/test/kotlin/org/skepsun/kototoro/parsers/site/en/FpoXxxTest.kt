package org.skepsun.kototoro.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class FpoXxxTest {

    private val parser = FpoXxx(ContentLoaderContextMock)

    @Test
    fun `parse list fixture`() {
        val result = parser.parseList(fixture("list.html"))

        assertEquals(3, result.size)
        assertEquals("Example Video One", result[0].title)
        assertEquals("https://www.fpo.xxx/video/example-slug-one/", result[0].publicUrl)
        assertEquals("https://img.example/fpo/thumbs/thumb1.jpg", result[0].coverUrl)
        assertEquals("Duration: 12:34", result[0].description)
        assertEquals("Example Video Three", result[2].title)
        assertTrue(result.none { it.publicUrl.contains("/categories/") })
    }

    @Test
    fun `parse details fixture`() {
        val content = content()
        val result = parser.parseDetails(fixture("detail.html"), content)

        assertEquals("Example Video One", result.title)
        assertEquals("Example description for video one.", result.description)
        assertEquals("https://img.example/fpo/detail1.jpg", result.coverUrl)
        assertEquals("Watch", result.chapters?.single()?.title)
    }

    @Test
    fun `resolve kvs stream url from details fixture`() {
        assertEquals(
            "https://www.fpo.xxx/get_file/1/0c678e567eb073707ec8c87ebf1c3833/secure/65299/example-720p.mp4/",
            parser.resolveStreamUrl(fixture("detail.html")),
        )
    }

    @Test
    fun `kvs decoder matches reference vector`() {
        val obfuscated =
            "function/0/https://www.fpo.xxx/get_file/1/8e76cc7f67b83e70708e7c8b31e303c5/secure/65299/example-720p.mp4/"
        assertEquals(
            "https://www.fpo.xxx/get_file/1/0c678e567eb073707ec8c87ebf1c3833/secure/65299/example-720p.mp4/",
            kvsDecodeUrl(obfuscated, "$62417872059274"),
        )
    }

    @Test
    fun `plain url passes kvs decoder unchanged`() {
        val url = "https://www.fpo.xxx/get_file/1/abc/example.mp4/"
        assertEquals(url, kvsDecodeUrl(url, "$62417872059274"))
    }

    @Test
    fun `parse categories fixture`() {
        val tags = parser.parseCategories(fixture("categories.html")).associateBy { it.key }

        assertEquals(setOf("categories/anal", "categories/big-boobs"), tags.keys)
        assertEquals("Anal", tags["categories/anal"]?.title)
        assertEquals("Big Boobs", tags["categories/big-boobs"]?.title)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
    }

    @Test
    fun `build list urls with category tag`() {
        val tag = ContentTag("Anal", "categories/anal", parser.source)
        val filter = ContentListFilter(tags = setOf(tag))
        assertEquals(
            "https://www.fpo.xxx/categories/anal/",
            parser.buildListUrl(1, SortOrder.RATING, filter),
        )
        assertEquals(
            "https://www.fpo.xxx/categories/anal/2/",
            parser.buildListUrl(2, SortOrder.RATING, filter),
        )
    }

    @Test
    fun `build list urls`() {
        val empty = ContentListFilter()
        assertEquals("https://www.fpo.xxx/", parser.buildListUrl(1, SortOrder.UPDATED, empty))
        assertEquals("https://www.fpo.xxx/new-1/2/", parser.buildListUrl(2, SortOrder.UPDATED, empty))
        assertEquals("https://www.fpo.xxx/popular-2/", parser.buildListUrl(1, SortOrder.POPULARITY, empty))
        assertEquals(
            "https://www.fpo.xxx/top-2/3/",
            parser.buildListUrl(3, SortOrder.RATING, empty),
        )

        val search = ContentListFilter(query = "example query")
        assertEquals(
            "https://www.fpo.xxx/search/example+query/",
            parser.buildListUrl(1, SortOrder.UPDATED, search),
        )
        assertEquals(
            "https://www.fpo.xxx/search/example+query/2/",
            parser.buildListUrl(2, SortOrder.UPDATED, search),
        )
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/fpo/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.fpo.xxx/",
    )

    private fun content() = Content(
        id = 1,
        title = "Example Video One",
        altTitles = emptySet(),
        url = "/video/example-slug-one/",
        publicUrl = "https://www.fpo.xxx/video/example-slug-one/",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = parser.source,
    )
}
