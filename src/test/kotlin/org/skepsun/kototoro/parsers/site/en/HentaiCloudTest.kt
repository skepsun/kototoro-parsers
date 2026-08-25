package org.skepsun.kototoro.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter

class HentaiCloudTest {

    private val parser = HentaiCloud(ContentLoaderContextMock)

    @Test
    fun `parse list fixture`() {
        val result = parser.parseList(fixture("list.html"))

        assertEquals(1, result.size)
        assertEquals("Goblin no Suana", result[0].title)
        assertEquals(
            "https://www.hentaicloud.com/video/3910/goblin-no-suana/episode2/english",
            result[0].publicUrl,
        )
    }

    @Test
    fun `build search url uses real form params`() {
        val query = ContentListFilter(query = "milf")
        assertEquals(
            "https://www.hentaicloud.com/search?search_type=videos&search_query=milf&page=1",
            parser.buildListUrl(1, query),
        )
        assertEquals(
            "https://www.hentaicloud.com/search?search_type=videos&search_query=milf&page=3",
            parser.buildListUrl(3, query),
        )
    }

    @Test
    fun `build list and tag urls`() {
        assertEquals(
            "https://www.hentaicloud.com/videos?page=2",
            parser.buildListUrl(2, ContentListFilter()),
        )
        val tag = ContentListFilter(
            tags = setOf(
                org.skepsun.kototoro.parsers.model.ContentTag("MILF", "milf", parser.source),
            ),
        )
        assertEquals(
            "https://www.hentaicloud.com/videos/milf?page=1",
            parser.buildListUrl(1, tag),
        )
    }

    @Test
    fun `detect verification page`() {
        assertTrue(parser.isChallengePage(fixture("challenge.html")))
        assertFalse(parser.isChallengePage(fixture("list.html")))
        assertFalse(parser.isChallengePage(fixture("detail.html")))
    }

    @Test
    fun `extract streams prefers hd over sd and ignores php endpoints`() {
        val streams = parser.extractStreams(fixture("detail.html"))

        assertEquals(2, streams.size)
        assertEquals(
            "https://www.hentaicloud.com/media/videos/hd/3910.mp4",
            streams.first(),
        )
        assertEquals(
            "https://www.hentaicloud.com/media/videos/iphone/3910.mp4",
            streams.last(),
        )
        assertTrue(streams.none { it.contains(".php") })
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/hentaicloud/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.hentaicloud.com/",
    )
}
