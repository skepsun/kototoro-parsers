package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

class ExHentaiSearchTest {
    private fun url(query: String?, retry: Boolean = false): String = "https://e-hentai.org/".toHttpUrl().newBuilder()
        .addEncodedQueryParameter("next", "0")
        .addQueryParameter("f_search", query)
        .apply { if (retry) addQueryParameter("inline_set", "dm_e") }
        .addQueryParameter("advsearch", "1")
        .build().toString()

    private fun context(expected: Set<String?>, inputs: List<String?>): OfflineContentLoaderContext {
        val urls = (inputs + expected).flatMap { listOf(url(it), url(it, retry = true)) }
        return OfflineContentLoaderContext(urls.associateWith { "exhentai/search.html" }, transformBody = { request, html ->
            if (request.url.queryParameter("f_search") in expected) html else "<html><body>No hits found</body></html>"
        })
    }

    @Test
    fun `namespaced multiword search returns matching galleries`() = runBlocking {
        val queries = mapOf(
            "other:ai generated" to "other:\"ai generated\"",
            "o:ai generated$" to "o:\"ai generated$\"",
            "female:big breasts" to "female:\"big breasts\"",
            "char:john smith" to "char:\"john smith\"",
        )
        for ((raw, expected) in queries) {
            val ctx = context(setOf(expected), listOf(raw))
            val result = ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, ContentListFilter(query = raw))
            assertEquals(1, result.size)
            assertEquals(expected, ctx.requests.first().toHttpUrl().queryParameter("f_search"))
        }
    }

    @Test
    fun `gallery tag preserves namespace and searches as a single exact term`() = runBlocking {
        val expected = "other:\"ai generated$\""
        val ctx = context(setOf(null, expected), listOf("tag:\"ai generated\"$", "other:\"ai generated\"$"))
        val parser = ExHentaiParser(ctx)
        val gallery = parser.getListPage(0, SortOrder.NEWEST, ContentListFilter.EMPTY).single()
        assertEquals("other:ai generated", gallery.tags.single().key)
        val result = parser.getListPage(0, SortOrder.NEWEST, ContentListFilter(tags = gallery.tags))
        assertEquals(1, result.size)
        assertEquals(expected, ctx.requests.last().toHttpUrl().queryParameter("f_search"))
    }

    @Test
    fun `selected namespaced tag sends one exact term`() = runBlocking {
        val expected = "other:\"ai generated$\""
        val current = "other:\"ai generated\"$"
        val ctx = context(setOf(expected), listOf(current))
        val parser = ExHentaiParser(ctx)
        val tag = parser.getFilterOptions().availableTags.single { it.key == "other:ai generated" }
        val result = parser.getListPage(0, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag)))
        assertEquals(1, result.size)
        assertEquals(expected, ctx.requests.first().toHttpUrl().queryParameter("f_search"))
    }

    @Test
    fun `filter options expose ai generated and retain the namespace`() = runBlocking {
        val parser = ExHentaiParser(context(emptySet(), emptyList()))
        val options = parser.getFilterOptions()
        assertTrue(options.availableTags.any { it.key == "other:ai generated" })
        assertTrue(options.tagGroups.any { group -> group.tags.any { it.key == "other:ai generated" } })
    }

    @Test
    fun `already quoted and compound queries keep their existing syntax`() = runBlocking {
        val queries = listOf("other:\"ai generated$\"", "other:ai generated -female:foo", "ordinary title")
        for (query in queries) {
            val ctx = context(setOf(query), listOf(query))
            assertEquals(1, ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, ContentListFilter(query = query)).size)
            assertEquals(query, ctx.requests.single().toHttpUrl().queryParameter("f_search"))
        }
    }

    @Test
    fun `details id preserves namespace even when identical tag names occur in different namespaces`() = runBlocking {
        val ctx = OfflineContentLoaderContext(mapOf(url(null) to "exhentai/search.html"), transformBody = { _, html ->
            html.replace("<div class=\"gt\" title=\"other:ai generated\">ai generated</div>",
                "<a id=\"ta_other:ai_generated\">ai generated</a>" +
                    "<a id=\"ta_female:ai_generated\">ai generated</a>")
        })
        val gallery = ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, ContentListFilter.EMPTY).single()
        assertEquals(setOf("other:ai generated", "female:ai generated"), gallery.tags.map { it.key }.toSet())
    }

    @Test
    fun `excluded tags and locale remain separate exact search terms`() = runBlocking {
        val expected = "-other:\"ai generated$\" language:\"english$\""
        val ctx = context(setOf(expected), emptyList())
        val tag = ContentTag("AI generated", "other:ai generated", ContentParserSource.EXHENTAI)
        val filter = ContentListFilter(tagsExclude = setOf(tag), locale = java.util.Locale.ENGLISH)
        assertEquals(1, ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, filter).size)
        assertEquals(expected, ctx.requests.single().toHttpUrl().queryParameter("f_search"))
    }
}
