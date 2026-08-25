package org.skepsun.kototoro.parsers.site.en

import org.json.JSONObject
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

class PlayVidsTest {

    private val parser = PlayVids(ContentLoaderContextMock)

    @Test
    fun `parse list fixture`() {
        val result = parser.parseList(fixture("list.html"))

        assertEquals(2, result.size)
        assertEquals("PlayVids Example One", result[0].title)
        assertEquals(
            "https://www.playvids.com/U3pBrYhsjXM/pc/playvids-example-one",
            result[0].publicUrl,
        )
        assertEquals("https://img.example/pv/thumb1.jpg", result[0].coverUrl)
        assertEquals("Duration: 3:21", result[0].description)
        assertEquals("PlayVids Example Two", result[1].title)
        assertTrue(result.none { it.title.contains("Trending") })
    }

    @Test
    fun `parse details fixture`() {
        val result = parser.parseDetails(fixture("detail.html"), content())

        assertEquals("PlayVids Example One", result.title)
        assertEquals("Example description for PlayVids example one.", result.description)
        assertEquals("https://img.example/pv/detail1.jpg", result.coverUrl)
        assertEquals("Watch", result.chapters?.single()?.title)
    }

    @Test
    fun `pick best stream from v-alt json`() {
        val json = fixtureJson("v-alt.json")
        assertEquals(
            "https://media.example/pv/v-alt/1080.mp4",
            parser.pickBestStream(json),
        )
    }

    @Test
    fun `extract direct source from detail fixture`() {
        assertEquals(
            "https://cdn.example/uls2/,680/5681/60df0a09432b.mp4," +
                "680/5681/6093533d47ec.mp4,.urlset/master.m3u8" +
                "?seclink=3-9Mvfb3YrZxzl1KraiMTg&sectime=1787645684",
            parser.extractDirectSource(fixture("detail.html")),
        )
    }

    @Test
    fun `parse categories fixture`() {
        val tags = parser.parseCategories(fixture("categories.html")).associateBy { it.key }

        assertEquals(
            setOf(
                "tgs/asian%20anal",
                "category/asian-interracial",
                "lesbian/category/asian-lesbian",
                "category/asian-massage",
            ),
            tags.keys,
        )
        assertEquals("Asian Anal", tags["tgs/asian%20anal"]?.title)
        assertEquals("Asian Massage", tags["category/asian-massage"]?.title)
        assertTrue(tags.none { it.key.startsWith("account") })
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
    }

    @Test
    fun `build list urls with category tag`() {
        val tag = ContentTag("Asian Massage", "category/asian-massage", parser.source)
        val filter = ContentListFilter(tags = setOf(tag))
        assertEquals(
            "https://www.playvids.com/category/asian-massage",
            parser.buildListUrl(1, SortOrder.UPDATED, filter),
        )
        assertEquals(
            "https://www.playvids.com/category/asian-massage?page=2",
            parser.buildListUrl(2, SortOrder.UPDATED, filter),
        )
    }

    @Test
    fun `build list urls`() {
        val empty = ContentListFilter()
        assertEquals("https://www.playvids.com/", parser.buildListUrl(1, SortOrder.UPDATED, empty))
        assertEquals("https://www.playvids.com/?page=2", parser.buildListUrl(2, SortOrder.UPDATED, empty))
        assertEquals(
            "https://www.playvids.com/Trending-Porn",
            parser.buildListUrl(1, SortOrder.POPULARITY, empty),
        )
        assertEquals(
            "https://www.playvids.com/Trending-Porn?page=3",
            parser.buildListUrl(3, SortOrder.POPULARITY, empty),
        )

        val search = ContentListFilter(query = "test")
        assertEquals("https://www.playvids.com/videos?q=test", parser.buildListUrl(1, SortOrder.UPDATED, search))
        assertEquals("https://www.playvids.com/videos?q=test&page=2", parser.buildListUrl(2, SortOrder.UPDATED, search))
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/playvids/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.playvids.com/",
    )

    private fun fixtureJson(name: String): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/fixtures/playvids/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
    )

    private fun content() = Content(
        id = 1,
        title = "PlayVids Example One",
        altTitles = emptySet(),
        url = "/U3pBrYhsjXM/pc/playvids-example-one",
        publicUrl = "https://www.playvids.com/U3pBrYhsjXM/pc/playvids-example-one",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = parser.source,
    )
}
