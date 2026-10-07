package org.skepsun.kototoro.parsers

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

internal suspend fun assertVideoParserCatalog(parser: PagedContentParser, distinctDetailCovers: Boolean = false) {
    val name = parser.source.name
    val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY)
    assertTrue(list.isNotEmpty(), "$name: empty list")
    assertTrue(list.all { it.title.isNotBlank() && it.title != "Untitled" }, "$name: invalid titles")
    val next = parser.getList(list.size, SortOrder.UPDATED, ContentListFilter.EMPTY)
    assertTrue(next.isNotEmpty(), "$name: empty second page")
    assertTrue(list.map { it.url }.toSet() != next.map { it.url }.toSet(), "$name: repeated second page")
    assertTrue(list.map { it.url }.toSet().intersect(next.map { it.url }.toSet()).isEmpty(),
        "$name: pagination contains overlapping subjects")

    val sample = list.distinctBy { it.coverUrl }.take(3)
    if (parser.filterCapabilities.isSearchSupported) {
        val query = sample.first().title
        val search = parser.getList(0, SortOrder.UPDATED, ContentListFilter(query = query))
        assertTrue(search.any { it.url == sample.first().url }, "$name: title search missed $query")
        val missing = parser.getList(0, SortOrder.UPDATED,
            ContentListFilter(query = "kototoro_no_result_13a760b409784a8ba027"))
        assertTrue(missing.isEmpty(), "$name: nonexistent query returned unrelated content")
    }
    val details = sample.map { parser.getDetails(it) }
    for (detail in details) {
        assertTrue(detail.chapters?.isNotEmpty() == true, "$name: empty chapters for ${detail.url}")
        val cover = detail.largeCoverUrl ?: detail.coverUrl
        assertNotNull(cover, "$name: missing detail cover for ${detail.url}")
        parser.context.httpClient.newCall(Request.Builder()
            .url(requireNotNull(cover))
            .headers(parser.getRequestHeaders())
            .header("Range", "bytes=0-2047")
            .build()).execute().use { response ->
            assertTrue(response.isSuccessful, "$name: cover HTTP ${response.code}")
            assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"),
                "$name: cover is not an image for ${detail.url}")
        }
    }
    if (distinctDetailCovers) {
        assertTrue(details.size >= 2, "$name: insufficient distinct cover samples")
        assertTrue(details.map { it.largeCoverUrl ?: it.coverUrl }.distinct().size > 1,
            "$name: different subjects share one detail cover")
    }
}

internal suspend fun assertVideoParserPlayback(parser: PagedContentParser) {
    val name = parser.source.name
    val item = parser.getList(0, SortOrder.UPDATED, ContentListFilter.EMPTY).first()
    val detail = parser.getDetails(item)
    val chapters = requireNotNull(detail.chapters)
    assertTrue(chapters.isNotEmpty(), "$name: empty chapters")
    val pages = parser.getPages(chapters.first())
    assertTrue(pages.isNotEmpty(), "$name: empty streams")
    for (page in listOf(pages.first(), pages.last()).distinctBy { it.url }) {
        parser.context.httpClient.newCall(Request.Builder()
            .url(page.url)
            .headers(parser.getRequestHeaders())
            .header("Range", "bytes=0-2047")
            .apply { page.headers?.forEach { (key, value) -> header(key, value) } }
            .build()).execute().use { response ->
            assertTrue(response.isSuccessful, "$name: stream HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty().lowercase()
            assertTrue(type.startsWith("video/") || type.contains("mpegurl") || type.contains("octet-stream"),
                "$name: stream is not video, type=$type")
            if (page.url.substringBefore('?').endsWith(".m3u8")) {
                assertTrue(response.body.source().readUtf8Line()?.trim() == "#EXTM3U",
                    "$name: invalid HLS manifest")
            }
        }
    }
}
