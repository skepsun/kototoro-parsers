package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentRating

class ComickTest {

    private val parser = ComickParser(ContentLoaderContextMock)

    @Test
    fun `map content rating from api strings`() {
        assertEquals(ContentRating.SAFE, parser.parseContentRating("safe"))
        assertEquals(ContentRating.SUGGESTIVE, parser.parseContentRating("suggestive"))
        assertEquals(ContentRating.ADULT, parser.parseContentRating("erotica"))
        assertEquals(ContentRating.ADULT, parser.parseContentRating("pornographic"))
        assertEquals(ContentRating.ADULT, parser.parseContentRating("adult"))
        assertNull(parser.parseContentRating("unknown"))
        assertNull(parser.parseContentRating(""))
        assertNull(parser.parseContentRating(null))
    }

    @Test
    fun `detect cloudflare challenge page`() {
        assertTrue(
            parser.isChallengePage(
                Jsoup.parse(
                    "<html><head><title>Just a moment...</title></head>" +
                        "<body><script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script></body></html>",
                    "https://comick.art/",
                ),
            ),
        )
        assertTrue(
            parser.isChallengePage(
                Jsoup.parse(
                    "<html><head><title>Checking your browser</title></head><body><p>Please wait...</p></body></html>",
                    "https://comick.art/",
                ),
            ),
        )
        assertFalse(
            parser.isChallengePage(
                Jsoup.parse(
                    "<html><head><title>Comick</title></head>" +
                        "<body><script type=\"application/json\" id=\"comic-data\">{}</script></body></html>",
                    "https://comick.art/",
                ),
            ),
        )
    }
}
