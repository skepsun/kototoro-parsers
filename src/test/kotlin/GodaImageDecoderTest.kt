package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GodaImageDecoderTest {

    @Test
    fun decodeCurrentImagePayload() {
        val images = GodaImageDecoder.decode(
            "J7ry7G3BjMpvXw3UkD390BrzmB_OjtwW4skRGwpR2xcw3lnnQ",
        )

        assertEquals(1, images?.length())
        assertEquals(1, images?.getJSONObject(0)?.getInt("order"))
        assertEquals("/a.webp", images?.getJSONObject(0)?.getString("url"))
    }

    @Test
    fun rejectInvalidPayload() {
        assertNull(GodaImageDecoder.decode("invalid"))
    }
}
