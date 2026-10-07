package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

class HanimeTest {

    private val apiUrl = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    @Test
    fun `v11 data envelope supplies current watch URLs and excludes ads`() = runBlocking {
        val context = OfflineContentLoaderContext(mapOf(apiUrl to "hanime/index.json"))
        val parser = Hanime(context)
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
        assertEquals(listOf("First 1", "Second 1"), list.map { it.title })
        assertEquals("/videos/hentai/first-1", list.first().url)
        assertEquals("https://hanime.tv/videos/hentai/first-1", list.first().publicUrl)
        assertEquals("https://img.example/first.jpg", list.first().coverUrl)
        val detail = parser.getDetails(list.first())
        assertEquals("https://img.example/first-poster.jpg", detail.largeCoverUrl)
        assertEquals("First description", detail.description)
        assertEquals(list.first().url, detail.chapters?.single()?.url)
        assertEquals(listOf(apiUrl), context.requests)
    }

    @Test
    fun `updated means recent upload while newest means recent release`() = runBlocking {
        val parser = Hanime(OfflineContentLoaderContext(mapOf(apiUrl to "hanime/index.json")))
        assertEquals("First 1", parser.getListPage(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first().title)
        assertEquals("Second 1", parser.getListPage(0, SortOrder.NEWEST, ContentListFilter.EMPTY).first().title)
        assertEquals("Second 1", parser.getListPage(0, SortOrder.POPULARITY, ContentListFilter.EMPTY).first().title)
    }

    @Test
    fun `empty search and exhausted page do not request unrelated trending content`() = runBlocking {
        val context = OfflineContentLoaderContext(mapOf(apiUrl to "hanime/index.json"))
        val parser = Hanime(context)
        assertTrue(parser.getListPage(0, SortOrder.UPDATED, ContentListFilter(query = "not found")).isEmpty())
        assertTrue(parser.getListPage(1, SortOrder.UPDATED, ContentListFilter.EMPTY).isEmpty())
        assertEquals(listOf(apiUrl), context.requests)
    }

    @Test
    fun `search and all selected tags are applied to the public index`() = runBlocking {
        val parser = Hanime(OfflineContentLoaderContext(mapOf(apiUrl to "hanime/index.json")))
        val tags = parser.getFilterOptions().availableTags
        assertEquals(setOf("hd", "uncensored"), tags.map { it.key }.toSet())
        val filter = ContentListFilter(query = "作品一", tags = setOf(
            ContentTag("HD", "hd", parser.source),
            ContentTag("Uncensored", "uncensored", parser.source),
        ))
        assertEquals("First 1", parser.getListPage(0, SortOrder.UPDATED, filter).single().title)
    }

    @Test
    fun `failed index request is surfaced and retried instead of caching an empty list`() {
        var calls = 0
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(apiUrl to "hanime/index.json"),
            transformBody = { _, body -> if (++calls == 1) "invalid response" else body },
        )
        val parser = Hanime(context)
        assertThrows(org.json.JSONException::class.java) {
            runBlocking { parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY) }
        }
        runBlocking { assertEquals(2, parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).size) }
        assertEquals(listOf(apiUrl, apiUrl), context.requests)
    }

    @Test
    fun `saved legacy chapter uses current domain and preserves signed stream query`() = runBlocking {
        val context = OfflineContentLoaderContext(mapOf(
            apiUrl to "hanime/index.json",
            "https://mirror.example/videos/hentai/first-1" to "hanime/watch.html",
        ))
        val parser = Hanime(context)
        (parser.config as SourceConfigMock).set(parser.configKeyDomain, "mirror.example")
        val detail = parser.getDetails(parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first())
        val chapter = requireNotNull(detail.chapters).single().copy(url = "/hentai-videos/first-1")
        val page = parser.getPages(chapter).first()
        assertEquals("https://media.example/first.m3u8?token=example&expires=123", page.url)
        assertEquals("https://mirror.example/videos/hentai/first-1", page.headers?.get("Referer"))
        assertEquals("https://mirror.example/videos/hentai/first-1", context.requests.last())
    }

    @Test
    fun `Astro handshake page fails without opening browser even when retried`() = runBlocking {
        val context = OfflineContentLoaderContext(mapOf(
            apiUrl to "hanime/index.json",
            "https://hanime.tv/videos/hentai/first-1" to "hanime/handshake.html",
        ))
        val parser = Hanime(context)
        val detail = parser.getDetails(parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first())
        val chapter = requireNotNull(detail.chapters).single()
        repeat(2) {
            val error = assertThrows(ParseException::class.java) {
                runBlocking { parser.getPages(chapter) }
            }
            assertEquals("https://hanime.tv/videos/hentai/first-1", error.url)
            assertTrue(error.shortMessage.orEmpty().contains("Hanime: playable video stream missing"))
        }
        assertEquals(listOf(
            apiUrl,
            "https://hanime.tv/videos/hentai/first-1",
            "https://hanime.tv/videos/hentai/first-1",
        ), context.requests)
    }

    @Test
    fun `missing player fails without treating an arbitrary page as a browser challenge`() = runBlocking {
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(
                apiUrl to "hanime/index.json",
                "https://hanime.tv/videos/hentai/first-1" to "hanime/handshake.html",
            ),
            transformBody = { request, body ->
                if (request.url.toString() == apiUrl) body else "<!doctype html><title>Unavailable</title>"
            },
        )
        val parser = Hanime(context)
        val detail = parser.getDetails(parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first())
        assertThrows(ParseException::class.java) {
            runBlocking { parser.getPages(requireNotNull(detail.chapters).single()) }
        }
        assertEquals(2, context.requests.size)
    }
}
