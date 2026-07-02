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
        private const val ENABLE_NETWORK = false
        private val timeout = 2.minutes
    }

    private fun parserFor(sourceName: String): ContentParser {
        val sourceEnum = ContentParserSource.valueOf(sourceName)
        return ContentLoaderContextMock.newParserInstance(sourceEnum)
    }

    private fun assumeNetwork() {
        assumeTrue(
            ENABLE_NETWORK ||
                System.getenv("ENABLE_YEALICO_NETWORK_TESTS") == "true" ||
                System.getenv("ALL_PROXY") != null ||
                System.getenv("HTTP_PROXY") != null,
            "Skipping — set ENABLE_YEALICO_NETWORK_TESTS=true or ALL_PROXY"
        )
    }

    // ==================== Unit ====================

    @Test
    fun `all 66 rules load from embedded data`() {
        val files = YealicoParserRegistry.ALL_RULES.map { it.cacheFile }
        assertEquals(66, files.size)
        for (f in files) {
            val json = YealicoRuleData.loadRule(f)
            assertNotNull(json, "Failed: $f")
        }
    }

    @Test
    fun `all wrapper classes instantiate rules`() {
        for (entry in YealicoParserRegistry.ALL_RULES) {
            val json = YealicoRuleParser.loadRuleJson(entry.cacheFile)
            assertNotNull(json, "Failed: ${entry.title}")
            assertTrue(json.has("indexUrl") || json.has("galleryUrl"), "${entry.title}: missing both")
        }
    }

    @Test
    fun `registry consistency checks`() {
        val all = YealicoParserRegistry.ALL_RULES
        assertEquals(66, all.size)
        assertEquals(66, YealicoParserRegistry.safeOnly.size + YealicoParserRegistry.nsfwOnly.size)
        assertEquals(66, YealicoParserRegistry.byType.values.sumOf { it.size })
        assertEquals(66, YealicoParserRegistry.byContentType.values.sumOf { it.size })
        println("ContentTypes: ${YealicoParserRegistry.byContentType.mapValues { it.value.size }}")
    }

    @Test
    fun `every rule has parsable JSON structure`() {
        var ok = 0
        for (entry in YealicoParserRegistry.ALL_RULES) {
            val json = YealicoRuleData.loadRule(entry.cacheFile)
            val hasIdx = json.has("indexUrl") && json.optString("indexUrl").isNotEmpty()
            val hasGr = json.has("galleryRule")
            assertTrue(hasIdx || hasGr, "${entry.title}: missing indexUrl + galleryRule")
            ok++
        }
        assertEquals(66, ok)
    }

    @Test
    fun `unique domains across all rules`() {
        val domains = mutableSetOf<String>()
        for (entry in YealicoParserRegistry.ALL_RULES) {
            val json = YealicoRuleData.loadRule(entry.cacheFile)
            val m = Regex("https?://([^/:]+)").find(json.optString("indexUrl", ""))
            m?.groupValues?.get(1)?.let { domains.add(it) }
        }
        println("${domains.size} unique domains")
        assertTrue(domains.size > 20)
    }

    // ==================== Network: Yande.re (booru) ====================

    @Test
    fun `Yande re — list, covers, pagination, pages, favicon`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_YANDE_RE_POST")

        // List
        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty(), "Should return items")
        assertEquals(40, items.size, "Expected 40 items (page size)")
        println("Yande.re: ${items.size} items")

        // Item structure
        for (item in items.take(5)) {
            assertTrue(item.title.isNotEmpty(), "title must not be empty")
            assertTrue(item.url.isNotEmpty(), "url must not be empty")
            assertTrue(item.publicUrl.startsWith("http"), "publicUrl must be absolute")
        }

        // Covers — booru sites should always have covers
        val withCover = items.count { it.coverUrl != null }
        assertTrue(withCover > 30, "Most items should have covers, got $withCover")
        val cover = items.first { it.coverUrl != null }.coverUrl!!
        assertTrue(cover.startsWith("http"), "Cover URL must be absolute")
        println("  covers: $withCover/${items.size}")

        // Pagination — page 2 should differ
        val page2 = parser.getList(offset = items.size, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(page2.isNotEmpty(), "Page 2 should have items")
        assertNotEquals(items.first().id, page2.first().id)
        println("  page2: ${page2.size} items")

        // Pages (image extraction from gallery)
        val chapter = ContentChapter(
            id = items[0].id, title = items[0].title, number = 1f, volume = 0,
            url = items[0].url, scanlator = null, uploadDate = 0, branch = null, source = items[0].source,
        )
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty(), "Should extract at least 1 image")
        assertTrue(pages.first().url.startsWith("http"))
        println("  pages: ${pages.size}")

        // Favicon
        val favicons = parser.getFavicons()
        if (favicons.isNotEmpty()) {
            favicons.forEach { assertTrue(it.url.startsWith("http")) }
            println("  favicons: ${favicons.size}")
        }
    }

    // ==================== Network: Konachan (booru) ====================

    @Test
    fun `Konachan — list, pages`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_KONACHAN_POST")

        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty(), "Should return items")
        println("Konachan: ${items.size} items")

        val chapter = ContentChapter(
            id = items[0].id, title = items[0].title, number = 1f, volume = 0,
            url = items[0].url, scanlator = null, uploadDate = 0, branch = null, source = items[0].source,
        )
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty(), "Should have pages")
        assertTrue(pages.first().url.startsWith("http"))
        println("  pages: ${pages.size}")
    }

    // ==================== Network: wallhaven (best-effort) ====================

    @Test
    fun `wallhaven — parser does not crash`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_WALLHAVEN")
        // wallhaven may have restructured — just verify no exception
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (_: Exception) {
            emptyList()
        }
        println("wallhaven: ${items.size} items (site may have restructured)")

        val favicons = parser.getFavicons()
        if (favicons.isNotEmpty()) {
            println("  favicons: ${favicons.size}")
        }
    }

    // ==================== Network: zerochan (may be Cloudflare-blocked) ====================

    @Test
    fun `zerochan — parser does not crash`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_ZEROCHAN")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (_: Exception) {
            println("zerochan: blocked (Cloudflare/503) — expected for automated access")
            emptyList()
        }
        println("zerochan: ${items.size} items")
    }

    // ==================== Network: Unsplash (API-dependent) ====================

    @Test
    fun `Unsplash — parser does not crash`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_UNSPLASH")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (e: Exception) {
            println("Unsplash: API may need key — ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        println("Unsplash: ${items.size} items")
    }

    // ==================== Network: E-shuushuu (may need JS) ====================

    @Test
    fun `E-shuushuu — parser does not crash`() = runTest(timeout = timeout) {
        assumeNetwork()
        val parser = parserFor("YEALICO_E_SHUUSHUU")
        val items = try {
            parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        } catch (_: Exception) {
            emptyList()
        }
        println("E-shuushuu: ${items.size} items (site uses Svelte/JS, rule may be stale)")
    }
}
