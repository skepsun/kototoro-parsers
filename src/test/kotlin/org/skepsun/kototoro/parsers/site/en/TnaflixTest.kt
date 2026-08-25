package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class TnaflixTest {

    private val parser = Tnaflix(ContentLoaderContextMock)

    @Test
    fun `parse list fixture`() {
        val result = parser.parseList(fixture("list.html"))

        assertEquals(2, result.size)
        assertEquals("Example Video One", result[0].title)
        assertEquals("https://www.tnaflix.com/video123456/example-video-one", result[0].publicUrl)
        assertEquals("https://img.example/tnf/thumb1.jpg", result[0].coverUrl)
        assertEquals("Duration: 12:34", result[0].description)
        assertEquals("Example Video Two", result[1].title)
        assertTrue(result.none { it.publicUrl.contains("/featured") })
    }

    @Test
    fun `parse details fixture`() {
        val result = parser.parseDetails(fixture("detail.html"), content())

        assertEquals("Example Video One", result.title)
        assertEquals("Example description for TNAFlix example one.", result.description)
        assertEquals("https://img.example/tnf/detail1.jpg", result.coverUrl)
        assertEquals("Watch", result.chapters?.single()?.title)
    }

    @Test
    fun `extract config url from flashvars`() {
        val doc = Jsoup.parse(
            "<script>var flashvars = { config : '/player/config/123.xml' };</script>",
            "https://www.tnaflix.com/video123456/example-video-one",
        )
        assertEquals(
            "https://www.tnaflix.com/player/config/123.xml",
            parser.extractConfigUrl(doc),
        )
    }

    @Test
    fun `extract config url from legacy flashvars config`() {
        val doc = Jsoup.parse(
            "<script>flashvars.config = 'https://cdn.example/tnaflix/config/987.xml';</script>",
            "https://www.tnaflix.com/video123456/example-video-one",
        )
        assertEquals(
            "https://cdn.example/tnaflix/config/987.xml",
            parser.extractConfigUrl(doc),
        )
    }

    @Test
    fun `extract video link from config xml`() {
        val xml = fixtureText("config.xml")
        assertEquals(
            "https://media.example/tnf/config-720p.mp4",
            parser.extractVideoLinkFromConfig(xml),
        )
    }

    @Test
    fun `extract direct source excludes trailer and picks best quality`() {
        val best = parser.extractDirectSource(fixture("detail.html"))
        assertEquals("https://media.example/tnf/example-720p.mp4", best)
    }

    @Test
    fun `resolve stream url offline without config`() = runBlocking {
        val stream = parser.resolveStreamUrl(fixture("detail.html"))
        assertEquals("https://media.example/tnf/example-720p.mp4", stream)
    }

    @Test
    fun `parse categories fixture`() {
        val tags = parser.parseCategories(fixture("categories.html")).associateBy { it.key }

        assertEquals(setOf("anal", "big-boobs"), tags.keys)
        assertEquals("Anal", tags["anal"]?.title)
        assertEquals("Big Boobs", tags["big-boobs"]?.title)
        assertTrue(parser.filterCapabilities.isMultipleTagsSupported)
    }

    @Test
    fun `build list urls combining duration period and category`() {
        val filter = ContentListFilter(
            tags = setOf(
                ContentTag("Anal", "anal", parser.source),
                ContentTag("Short (1-3 min)", "d=short", parser.source),
                ContentTag("This Week", "u=week", parser.source),
            ),
        )
        assertEquals(
            "https://www.tnaflix.com/anal?d=short&u=week",
            parser.buildListUrl(1, SortOrder.UPDATED, filter),
        )
        assertEquals(
            "https://www.tnaflix.com/anal?d=short&u=week&page=2",
            parser.buildListUrl(2, SortOrder.UPDATED, filter),
        )
        val durationOnly = ContentListFilter(
            tags = setOf(
                ContentTag("Full Length (30+ min)", "d=full", parser.source),
            ),
        )
        assertEquals(
            "https://www.tnaflix.com/new?d=full&page=2",
            parser.buildListUrl(2, SortOrder.UPDATED, durationOnly),
        )
    }

    @Test
    fun `build list urls`() {
        val empty = ContentListFilter()
        assertEquals("https://www.tnaflix.com/new", parser.buildListUrl(1, SortOrder.UPDATED, empty))
        assertEquals("https://www.tnaflix.com/new?page=2", parser.buildListUrl(2, SortOrder.UPDATED, empty))
        assertEquals("https://www.tnaflix.com/popular", parser.buildListUrl(1, SortOrder.POPULARITY, empty))
        assertEquals("https://www.tnaflix.com/toprated", parser.buildListUrl(1, SortOrder.RATING, empty))

        val search = ContentListFilter(query = "test")
        assertEquals(
            "https://www.tnaflix.com/search?what=test&dir=latest",
            parser.buildListUrl(1, SortOrder.UPDATED, search),
        )
        assertEquals(
            "https://www.tnaflix.com/search?what=test&dir=latest&page=2",
            parser.buildListUrl(2, SortOrder.UPDATED, search),
        )
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/tnaflix/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.tnaflix.com/",
    )

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/tnaflix/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name")

    private fun content() = Content(
        id = 1,
        title = "Example Video One",
        altTitles = emptySet(),
        url = "/video123456/example-video-one",
        publicUrl = "https://www.tnaflix.com/video123456/example-video-one",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = parser.source,
    )
}
