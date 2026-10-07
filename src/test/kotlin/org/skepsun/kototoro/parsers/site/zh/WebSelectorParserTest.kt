package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

class WebSelectorParserTest {

    @Test
    fun `fantuan keeps each subject cover instead of shared advertisement`() = runBlocking {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://acgfta.com/ft/recent/file/recent/page/1.html" to "web-selector/fantuan-list.html",
                "https://acgfta.com/anime/101.html" to "web-selector/fantuan-detail.html",
                "https://acgfta.com/anime/102.html" to "web-selector/fantuan-detail.html",
            ),
            transformBody = { request, body ->
                body.replace("COVER", if (request.url.encodedPath.endsWith("101.html")) "first" else "second")
            },
        )
        val parser = Fantuan(context)
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
        assertEquals(listOf("作品一", "作品二"), list.map { it.title })
        assertEquals(listOf("https://img.example/first.jpg", "https://img.example/second.jpg"),
            list.map { it.coverUrl })

        val details = list.map { parser.getDetails(it) }
        assertEquals(list.map { it.coverUrl }, details.map { it.coverUrl })
        assertNotEquals(details[0].largeCoverUrl, details[1].largeCoverUrl)
        assertEquals(listOf(1f, 2f), details[0].chapters?.map { it.number })
    }

    @Test
    fun `fantuan missing poster preserves list cover without selecting an advertisement`() = runBlocking {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://acgfta.com/ft/recent/file/recent/page/1.html" to "web-selector/fantuan-list.html",
                "https://acgfta.com/anime/101.html" to "web-selector/fantuan-detail.html",
            ),
            transformBody = { request, body ->
                if (request.url.encodedPath.startsWith("/anime/")) body.replace("anime-cover", "missing") else body
            },
        )
        val parser = Fantuan(context)
        val item = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first()
        assertEquals(item.coverUrl, parser.getDetails(item).largeCoverUrl)
    }

    @Test
    fun `fantuan builds native search and browse pagination using configured domain`() {
        val parser = Fantuan(OfflineContentLoaderContext())
        (parser.config as SourceConfigMock).set(parser.configKeyDomain, "mirror.example")
        assertEquals("https://mirror.example/ft/recent/file/recent/page/2.html",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter.EMPTY))
        assertEquals("https://mirror.example/ft/leaderboard/file/leaderboard/page/1.html",
            parser.buildListUrl(1, SortOrder.POPULARITY, ContentListFilter.EMPTY))
        assertEquals("https://mirror.example/search/page/2/wd/full%20query.html",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter(query = "full query")))
    }

    @Test
    fun `uzvod uses native sort category and page fields`() {
        val parser = Uzvod(OfflineContentLoaderContext())
        assertEquals("https://uzvod.com/vodshow/dongman--time------1---.html",
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter.EMPTY))
        val filter = ContentListFilter(tags = setOf(ContentTag("日韩动漫", "type:rihandongman", parser.source)))
        assertEquals("https://uzvod.com/vodshow/rihandongman--hits------2---.html",
            parser.buildListUrl(2, SortOrder.POPULARITY, filter))
        assertEquals("https://uzvod.com/vodsearch/full%20query----------2---.html",
            parser.buildListUrl(2, SortOrder.UPDATED, ContentListFilter(query = "full query")))
    }

    @Test
    fun `uzvod parses current cards details chapters and stream`() = runBlocking {
        val context = OfflineContentLoaderContext(mapOf(
            "https://uzvod.com/vodshow/dongman--time------1---.html" to "web-selector/uzvod-list.html",
            "https://uzvod.com/voddetail/101.html" to "web-selector/uzvod-detail.html",
            "https://uzvod.com/vodplay/101-1-1.html" to "web-selector/play.html",
        ))
        val parser = Uzvod(context)
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
        assertEquals(2, list.size)
        assertNotEquals(list[0].coverUrl, list[1].coverUrl)
        val detail = parser.getDetails(list.first())
        assertEquals("https://uzvod.com/cover/101.jpg", detail.largeCoverUrl)
        val chapters = requireNotNull(detail.chapters)
        assertEquals(listOf(1f, 2f), chapters.map { it.number })
        val page = parser.getPages(chapters.first()).single()
        assertEquals("https://media.example/episode/index.m3u8?token=example&expires=123", page.url)
        assertEquals("", page.headers?.get("referer"))
    }

    @Test
    fun `uzvod search selects the title instead of the adjacent episode label`() = runBlocking {
        val context = OfflineContentLoaderContext(mapOf(
            "https://uzvod.com/vodsearch/query----------1---.html" to "web-selector/uzvod-search.html",
        ))
        val parser = Uzvod(context)
        val result = parser.getList(0, SortOrder.UPDATED, ContentListFilter(query = "query")).single()
        assertEquals("作品一", result.title)
        assertEquals("https://uzvod.com/cover/101.jpg", result.coverUrl)
    }

    @Test
    fun `player config decrypts percent and base64 encoded stream URLs`() = runBlocking {
        for (encrypt in 1..2) {
            val encoded = "https%3A%2F%2Fmedia.example%2Fepisode%2Findex.m3u8%3Ftoken%3Dexample"
            val value = if (encrypt == 1) encoded else java.util.Base64.getEncoder()
                .encodeToString(encoded.toByteArray())
            val context = OfflineContentLoaderContext(
                fixtures = mapOf(
                    "https://uzvod.com/vodshow/dongman--time------1---.html" to "web-selector/uzvod-list.html",
                    "https://uzvod.com/voddetail/101.html" to "web-selector/uzvod-detail.html",
                    "https://uzvod.com/vodplay/101-1-1.html" to "web-selector/play.html",
                ),
                transformBody = { request, body ->
                    if (request.url.encodedPath.startsWith("/vodplay/")) {
                        "<script>var player_aaaa={\"encrypt\":$encrypt,\"url\":\"$value\"};</script>"
                    } else body
                },
            )
            val parser = Uzvod(context)
            val detail = parser.getDetails(parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first())
            assertEquals("https://media.example/episode/index.m3u8?token=example",
                parser.getPages(requireNotNull(detail.chapters).first()).single().url)
        }
    }

    @Test
    fun `advertisement iframe is never accepted as a playable stream`() {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://uzvod.com/vodshow/dongman--time------1---.html" to "web-selector/uzvod-list.html",
                "https://uzvod.com/voddetail/101.html" to "web-selector/uzvod-detail.html",
                "https://uzvod.com/vodplay/101-1-1.html" to "web-selector/play.html",
            ),
            transformBody = { request, body ->
                if (request.url.encodedPath.startsWith("/vodplay/")) {
                    "<iframe src=\"https://ads.example/banner.html\"></iframe>"
                } else body
            },
        )
        val parser = Uzvod(context)
        val exception = assertThrows(UnsupportedOperationException::class.java) {
            runBlocking {
                val detail = parser.getDetails(parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first())
                parser.getPages(requireNotNull(detail.chapters).first())
            }
        }
        assertTrue(exception.message.orEmpty().contains("Browser"))
    }

    @Test
    fun `generic detail extraction preserves cover when only an unscoped ad is present`() = runBlocking {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://uzvod.com/vodshow/dongman--time------1---.html" to "web-selector/uzvod-list.html",
                "https://uzvod.com/voddetail/101.html" to "web-selector/uzvod-detail.html",
            ),
            transformBody = { request, body ->
                if (request.url.encodedPath.startsWith("/voddetail/")) {
                    body.replace("<meta property=\"og:image\" content=\"/cover/101.jpg\">",
                        "<img data-src=\"https://img.example/shared-banner.jpg\">")
                } else body
            },
        )
        val parser = Uzvod(context)
        val item = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first()
        assertEquals(item.coverUrl, parser.getDetails(item).coverUrl)
    }

    @Test
    fun `plain stream regex preserves the whole signed URL`() = runBlocking {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                "https://uzvod.com/vodshow/dongman--time------1---.html" to "web-selector/uzvod-list.html",
                "https://uzvod.com/voddetail/101.html" to "web-selector/uzvod-detail.html",
                "https://uzvod.com/vodplay/101-1-1.html" to "web-selector/play.html",
            ),
            transformBody = { request, body ->
                if (request.url.encodedPath.startsWith("/vodplay/")) {
                    "<video src=\"https://media.example/index.m3u8?sign=example&expires=123\"></video>"
                } else body
            },
        )
        val parser = Uzvod(context)
        val detail = parser.getDetails(parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first())
        assertEquals("https://media.example/index.m3u8?sign=example&expires=123",
            parser.getPages(requireNotNull(detail.chapters).first()).single().url)
    }
}
