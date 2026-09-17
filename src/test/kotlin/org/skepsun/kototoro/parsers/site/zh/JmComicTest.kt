package org.skepsun.kototoro.parsers.site.zh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentTag

class JmComicTest {

    private val parser = JmParser(ContentLoaderContextMock)

    @Test
    fun `weekly tags are sorted by time descending (newest issue first)`() {
        // 数据形态取自 /week 接口：id 为期数序号（越大越新），time 为展示字符串
        val tags = listOf(
            ContentTag("2026第255期09.04 - 08.28", "w:256", parser.source),
            ContentTag("2021第1期10.21 - 10.14", "w:1", parser.source),
            ContentTag("2026第256期09.11 - 09.04", "w:257", parser.source),
            ContentTag("2025第219期12.26 - 12.19", "w:220", parser.source),
            ContentTag("2026第254期08.28 - 08.21", "w:255", parser.source),
        )

        val sorted = with(parser) { tags.sortedWeeklyByTimeDesc() }

        assertEquals(listOf("w:257", "w:256", "w:255", "w:220", "w:1"), sorted.map { it.key })
    }
}
