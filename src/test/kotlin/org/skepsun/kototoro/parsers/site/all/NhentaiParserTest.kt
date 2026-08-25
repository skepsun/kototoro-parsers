package org.skepsun.kototoro.parsers.site.all

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentRating

class NhentaiParserTest {

    private val parser = NhentaiParser(ContentLoaderContextMock)

    @Test
    fun `parse gallery list fixture`() {
        val items = parser.parseGalleryList(fixture("list.html"))

        assertEquals(2, items.size)
        assertEquals(
            "(C93) [Chijoku An (Marquis)] Idol Saimin Ryoujoku [English]",
            items[0].title,
        )
        assertEquals("385440", items[0].url)
        assertEquals("https://nhentai.net/g/385440", items[0].publicUrl)
        assertEquals("https://t3.nhentai.net/galleries/2045096/cover.jpg", items[0].coverUrl)
        assertTrue(items[0].contentRating == ContentRating.ADULT)

        // 语言从 data-tags 推断（12227 = English）；description 字段即语言
        assertEquals("385441", items[1].url)
        assertEquals("English", items[1].description)
    }

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/nhentai/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://nhentai.net/",
    )
}
