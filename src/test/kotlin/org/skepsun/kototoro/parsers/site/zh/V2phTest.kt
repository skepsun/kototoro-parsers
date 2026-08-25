package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentRating

class V2phTest {

    private val parser = V2ph(ContentLoaderContextMock)

    @Test
    fun `list items are marked adult for nsfw filtering`() {
        val items = parser.parseList(fixture("list.html"))

        // actor-cover cards are skipped
        assertEquals(2, items.size)
        assertTrue(items.all { it.contentRating == ContentRating.ADULT })
        assertEquals("模特秀场", items[0].title)
        assertEquals("https://www.v2ph.com/album/12345/xiuren", items[0].publicUrl)
        assertEquals("https://www.v2ph.com/images/covers/1.jpg", items[0].coverUrl)
        assertTrue(items[0].tags.any { it.title == "性感美女" })
        assertTrue(items[0].tags.any { it.title.startsWith("机构:") })
        assertTrue(items[0].tags.any { it.title.startsWith("模特:") })
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/v2ph/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.v2ph.com/",
    )
}
