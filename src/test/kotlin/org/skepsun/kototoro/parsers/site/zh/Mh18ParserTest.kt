package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag

internal class Mh18ParserTest {

    private val parser = Mh18Parser(ContentLoaderContextMock)

    @Test
    fun `browse all comics uses path based pagination`() {
        assertEquals(
            "https://18mh.org/manga/page/2",
            parser.buildListUrl(2, ContentListFilter.EMPTY),
        )
    }

    @Test
    fun `browse genre and tag paths with pagination`() {
        val genre = ContentListFilter(tags = setOf(ContentTag("韓漫", "/manga-genre/hanman", parser.source)))
        assertEquals(
            "https://18mh.org/manga-genre/hanman/page/3",
            parser.buildListUrl(3, genre),
        )

        val tag = ContentListFilter(tags = setOf(ContentTag("多人", "/manga-tag/duoren", parser.source)))
        assertEquals(
            "https://18mh.org/manga-tag/duoren/page/2",
            parser.buildListUrl(2, tag),
        )
    }

    @Test
    fun `rejects tag keys that are not paths`() {
        val filter = ContentListFilter(tags = setOf(ContentTag("坏标签", "search/genre/x", parser.source)))
        assertNull(parser.buildListUrl(1, filter))
    }

    @Test
    fun `exposed tags carry real site paths`() = runBlocking {
        val options = parser.getFilterOptions()
        val tagGroups = options.tagGroups.associateBy { it.title }
        val typeTags = tagGroups.getValue("类型").tags
        val tagTags = tagGroups.getValue("标签").tags

        assertTrue(typeTags.any { it.key == "/manga-genre/hanman" })
        // 标签列表必须以站点拼音路径作为 key，而不是用中文标题充数
        assertTrue(tagTags.isNotEmpty())
        assertTrue(tagTags.all { it.key.startsWith("/manga-tag/") })
    }

    @Test
    fun `parse comic cards with data-src cover`() {
        val document = Jsoup.parse(
            """
            <div class="container">
              <div class="cardlist">
                <div class="pb-2">
                  <a href="/book/1234.html"><img data-src="https://cdn.example/cover1.jpg"></a>
                  <h3>测试漫画 A</h3>
                </div>
                <div class="pb-2">
                  <a href="https://18mh.org/book/5678.html"><img src="https://cdn.example/cover2.jpg"></a>
                  <h3>测试漫画 B</h3>
                </div>
                <div class="pb-2"><a href="/book/empty.html"></a><span>无标题卡片</span></div>
              </div>
            </div>
            """.trimIndent(),
            "https://18mh.org/manga/page/1",
        )

        val result = parser.parseComicCards(document)

        assertEquals(2, result.size)
        assertEquals("测试漫画 A", result[0].title)
        assertEquals("/book/1234.html", result[0].url)
        assertEquals("https://18mh.org/book/1234.html", result[0].publicUrl)
        assertEquals("https://cdn.example/cover1.jpg", result[0].coverUrl)
        assertEquals("测试漫画 B", result[1].title)
        assertEquals("/book/5678.html", result[1].url)
        assertEquals("https://cdn.example/cover2.jpg", result[1].coverUrl)
    }
}
