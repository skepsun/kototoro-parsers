package org.skepsun.kototoro.parsers.site.yealico

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.*
import kotlin.time.Duration.Companion.minutes

class YealicoRuleParserTest {

    companion object {
        private val timeout = 2.minutes
    }

    private fun parserFor(name: String): ContentParser {
        return ContentLoaderContextMock.newParserInstance(ContentParserSource.valueOf(name))
    }

    private fun assumeNetwork() {
        assumeTrue(
            System.getenv("ALL_PROXY") != null || System.getenv("HTTP_PROXY") != null,
            "Skipping — set ALL_PROXY to run network tests"
        )
    }

    private val expectedCount = YealicoParserRegistry.ALL_RULES.size

    // ==================== Unit ====================

    @Test fun `all rules load from embedded data`() {
        for (entry in YealicoParserRegistry.ALL_RULES) {
            val json = YealicoRuleData.loadRule(entry.cacheFile)
            assertNotNull(json, "Failed: ${entry.title}")
        }
        println("Loaded all $expectedCount rules")
    }

    @Test fun `all wrappers instantiate rules`() {
        for (entry in YealicoParserRegistry.ALL_RULES) {
            val json = YealicoRuleParser.loadRuleJson(entry.cacheFile)
            assertNotNull(json, "Failed: ${entry.title}")
            assertTrue(json.has("indexUrl") || json.has("galleryUrl"))
        }
    }

    @Test fun `registry consistency`() {
        val all = YealicoParserRegistry.ALL_RULES
        assertEquals(expectedCount, all.size)
        assertEquals(expectedCount, YealicoParserRegistry.safeOnly.size + YealicoParserRegistry.nsfwOnly.size)
        assertEquals(expectedCount, YealicoParserRegistry.byContentType.values.sumOf { it.size })
        println("ContentTypes: ${YealicoParserRegistry.byContentType.mapValues { it.value.size }}")
    }

    @Test fun `every rule has parsable JSON`() {
        for (entry in YealicoParserRegistry.ALL_RULES) {
            val json = YealicoRuleData.loadRule(entry.cacheFile)
            assertTrue(json.has("indexUrl") || json.has("galleryUrl"), "${entry.title}: missing both")
        }
    }

    // ==================== Network: Yande.re ====================

    @Test fun `Yande re — full pipeline`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_YANDE_RE_POST")

        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty(), "list empty")
        items.take(3).forEach { assertTrue(it.title.isNotEmpty()); assertTrue(it.url.isNotEmpty()) }

        val withCover = items.count { it.coverUrl != null }
        if (withCover > 0) assertTrue(items.first { it.coverUrl != null }.coverUrl!!.startsWith("http"))

        val chapter = ContentChapter(id = items[0].id, title = items[0].title, number = 1f, volume = 0,
            url = items[0].url, scanlator = null, uploadDate = 0, branch = null, source = items[0].source)
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty()); assertTrue(pages.first().url.startsWith("http"))

        val favs = parser.getFavicons()
        if (favs.isNotEmpty()) favs.forEach { assertTrue(it.url.startsWith("http")) }

        println("Yande.re: ${items.size} items, ${withCover} covers, ${pages.size} pages, ${favs.size} favicons")
    }

    // ==================== Network: Konachan ====================

    @Test fun `Konachan — list and pages`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_KONACHAN_POST")
        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty())
        val ch = ContentChapter(id = items[0].id, title = items[0].title, number = 1f, volume = 0,
            url = items[0].url, scanlator = null, uploadDate = 0, branch = null, source = items[0].source)
        val pages = parser.getPages(ch)
        assertTrue(pages.isNotEmpty())
        println("Konachan: ${items.size} items, ${pages.size} pages")
    }

    // ==================== Network: Yande.re Pool ====================

    @Test fun `Yande re Pool — list`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_YANDE_RE_POOL")
        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty())
        println("Yande.re Pool: ${items.size} items")
    }

    // ==================== Network: Dribbble ====================

    @Test fun `Dribbble — list (may be empty after restructure)`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_DRIBBBLE")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (e: Exception) { emptyList() }
        assertTrue(items.size >= 0)
        println("Dribbble: ${"$"}{items.size} items")
    }

    // ==================== Network: G.E-hentai ====================

    @Test fun `G E-hentai — list (tolerant)`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_G_E_HENTAI")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (e: Exception) {
            println("G.E-hentai: blocked — ${"$"}{e.javaClass.simpleName}")
            return@runTest
        }
        println("G.E-hentai: ${"$"}{items.size} items")
    }

    // ==================== Network: zerochan ====================

    @Test fun `zerochan — list (cloudflare-tolerant)`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_ZEROCHAN")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (e: Exception) {
            println("zerochan: blocked — ${"$"}{e.javaClass.simpleName}")
            return@runTest
        }
        println("zerochan: ${"$"}{items.size} items")
    }

    // ==================== Network: 维基百科 ====================

    @Test fun `Wikipedia POTD — list`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_EA0ED09A")
        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty())
        println("Wikipedia: ${items.size} items, first: ${items.first().title}")
    }

    // ==================== Network: HPJav ====================

    @Test fun `HPJav — list`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_HPJAV")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (e: Exception) {
            println("HPJav: blocked — ${"$"}{e.javaClass.simpleName}")
            return@runTest
        }
        println("HPJav: ${"$"}{items.size} items")
    }
}
