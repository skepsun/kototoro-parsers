package org.skepsun.kototoro.parsers.site.yealico

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.*; import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.*
import java.io.File
import kotlin.time.Duration.Companion.minutes

class YealicoRuleParserTest {

    private val context = ContentLoaderContextMock
    private val timeout = 2.minutes

    @Test
    fun `load rule JSON files from cache`() {
        val cacheDir = File("yealico_rules_cache")
        assumeTrue(cacheDir.isDirectory, "Cache dir not found - run fetch first")
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".json") && !it.name.startsWith("_") }
        assertNotNull(files)
        assertTrue(files!!.isNotEmpty(), "Should have cached rule files")
        println("Found ${files.size} cached rules")
    }

    @Test
    fun `parse all rule JSON structures`() {
        val cacheDir = File("yealico_rules_cache")
        assumeTrue(cacheDir.isDirectory)
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".json") && !it.name.startsWith("_") } ?: return

        var parsed = 0
        var failed = 0
        val types = mutableMapOf<String, Int>()

        for (f in files) {
            try {
                val json = JSONObject(f.readText())
                assertTrue(json.has("title"))
                assertTrue(json.has("indexUrl"))
                val gr = json.optJSONObject("galleryRule")
                val type = when {
                    gr?.has("videoRule") == true -> "video"
                    json.optJSONObject("detailRule")?.has("chapterRule") == true -> "manga"
                    gr?.has("pictureRule") == true -> "gallery"
                    json.optJSONObject("indexRule")?.optJSONObject("item")?.has("path") == true -> "api_gallery"
                    else -> "gallery"
                }
                types[type] = (types[type] ?: 0) + 1
                parsed++
            } catch (e: Exception) {
                failed++
                println("  FAIL: ${f.name} - ${e.message}")
            }
        }

        println("Parsed $parsed rules, $failed failures")
        println("Types: $types")
        assertEquals(0, failed, "All rules should parse")
    }

    @Test
    fun `catalog domains from rules`() {
        val cacheDir = File("yealico_rules_cache")
        assumeTrue(cacheDir.isDirectory)
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".json") && !it.name.startsWith("_") } ?: return

        val domains = mutableSetOf<String>()
        for (f in files.take(60)) {
            try {
                val json = JSONObject(f.readText())
                val url = json.optString("indexUrl", "")
                val m = Regex("https?://([^/:]+)").find(url)
                m?.groupValues?.get(1)?.let { domains.add(it) }
            } catch (_: Exception) {}
        }

        println("${domains.size} unique domains:")
        domains.sorted().forEach { println("  $it") }
        assertTrue(domains.size > 10, "Should have many unique domains")
    }

    @Test
    fun `HTML extraction logic with Jsoup`() {
        val html = """
        <html><body>
        <div class="item">
            <h2 class="title"><a href="/view/123">Test Title</a></h2>
            <img class="cover" src="/img/123.jpg" />
        </div>
        </body></html>
        """
        val doc = Jsoup.parse(html)
        val item = doc.selectFirst(".item")
        assertNotNull(item)

        val title = item!!.selectFirst(".title")!!.text()
        assertEquals("Test Title", title)

        val link = item.selectFirst(".title a")!!.attr("href")
        assertEquals("/view/123", link)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ENABLE_YEALICO_NETWORK_TESTS", matches = "true")
    fun `network - E-shuushuu rule structure`() {
        val json = loadCachedRule("E-shuushuu")
        assertNotNull(json)
        println("Rule name: ${json!!.optString("title")}")
        println("Index URL: ${json.optString("indexUrl")}")

        val ir = json.optJSONObject("indexRule")
        assertNotNull(ir, "Should have indexRule")
        assertTrue(ir!!.has("item"), "Should have item selector")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ENABLE_YEALICO_NETWORK_TESTS", matches = "true")
    fun `network - Yande re listing and pages`() = runTest(timeout = timeout) {
        val cacheDir = File("yealico_rules_cache")
        assumeTrue(cacheDir.isDirectory)

        val json = loadCachedRule("Yande.re_Post")
        assertNotNull(json)

        val source = object : ContentSource {
            override val name = "YEALICO_TEST_YANDERE"
            override val locale = ""
            override val contentType = ContentType.IMAGE_SET
        }
        val parser = YealicoRuleParser(context, source, json!!)

        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty(), "Yande.re should return items")
        println("Got ${items.size} items: ${items.take(3).map { it.title }}")

        // Test pages for first item
        val chapter = ContentChapter(
            id = items[0].id, title = items[0].title, number = 1f, volume = 0,
            url = items[0].url, scanlator = null, uploadDate = 0, branch = null, source = items[0].source,
        )
        val pages = parser.getPages(chapter)
        assertTrue(pages.isNotEmpty(), "Should have pages")
        println("Pages: ${pages.size}, first URL: ${pages.first().url}")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ENABLE_YEALICO_NETWORK_TESTS", matches = "true")
    fun `network - Konachan listing`() = runTest(timeout = timeout) {
        val json = loadCachedRule("Konachan_Post")
        assertNotNull(json)
        val source = object : ContentSource {
            override val name = "YEALICO_TEST_KONACHAN"
            override val locale = ""
            override val contentType = ContentType.IMAGE_SET
        }
        val parser = YealicoRuleParser(context, source, json!!)
        val items = parser.getList(offset = 0, order = SortOrder.UPDATED, filter = ContentListFilter.EMPTY)
        assertTrue(items.isNotEmpty(), "Konachan should return items")
        println("Konachan: ${items.size} items, first: ${items.first().title}")
    }

    private fun loadCachedRule(name: String): JSONObject? {
        val cacheDir = File("yealico_rules_cache")
        val file = cacheDir.resolve("$name.json")
        return if (file.exists()) JSONObject(file.readText()) else null
    }
}
