package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

// SF轻小说（book.sfacg.com）离线夹具测试：
// 书库卡片 ul.Comic_Pic_List、搜索结果 li.Conjunction+strong a、详情 h1.title/p.introduce/tag-list、
// 目录 div.story-catalog 按卷分组、正文 div#ChapterBody。
class SfacgTest {

    private val parser = Sfacg(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/sfacg/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://book.sfacg.com/",
    )

    @Test
    fun `capabilities`() {
        assertEquals(setOf(SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertTrue(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertEquals(20, parser.pageSize)
    }

    @Test
    fun `filter options expose nine genres and two progress states`() {
        val options = kotlinx.coroutines.runBlocking { parser.getFilterOptions() }
        assertEquals(11, options.availableTags.size)
        assertTrue(options.availableTags.any { it.key == "tid:21" && it.title == "魔幻" })
        assertTrue(options.availableTags.any { it.key == "if:1" && it.title == "已完结" })
        assertEquals(listOf("类别", "写作进度"), options.tagGroups.map { it.title })
    }

    @Test
    fun `list page maps title cover author rating genre and description`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(3, items.size)
        assertEquals("游戏加点加错成为光之魔女", items[0].title)
        assertEquals("/Novel/779689/", items[0].url)
        assertNotNull(items[0].coverUrl)
        assertEquals(setOf("佩洛尼娅"), items[0].authors)
        assertTrue(items[0].rating in 0f..1f)
        assertEquals("魔幻", items[0].tags.first().title)
        assertNotNull(items[0].description)
        assertTrue(items[0].description!!.contains("特性"))
    }

    @Test
    fun `search page reads the aggregate result cards`() {
        val items = parser.parseSearchPage(fixture("search.html"))
        assertEquals(2, items.size)
        assertTrue(items[0].title.contains("魔女"))
        assertTrue(items[0].url.startsWith("/Novel/"))
        assertNotNull(items[0].coverUrl)
        assertNotNull(items[0].description)
    }

    @Test
    fun `detail page reads title author stats tags and state`() {
        val doc = fixture("detail.html")
        assertEquals("异界转生的少女还在旅途中", doc.selectFirst("h1.title span.text")!!.clone()
            .also { it.select("span.tag").remove() }.text())
        assertEquals("白上沐晴", doc.selectFirst(".author-name span")!!.text())
        val tags = parser.parseDetailTags(doc)
        assertTrue(tags.size >= 2)
        assertTrue(tags.any { it.title == "西幻" })
        val stats = doc.select(".count-detail .text-row span.text").map { it.text() }
        assertTrue(stats.any { it.contains("字数") && it.contains("连载中") })
    }

    @Test
    fun `catalog groups chapters by volume and keeps site order`() {
        val chapters = parser.parseChapters(fixture("catalog.html"), "718891")
        assertEquals(109, chapters.size)
        assertEquals("番外", chapters.first().branch)
        assertEquals("番外篇 少女心中所想 心念落定之时", chapters.first().title)
        assertEquals(1f, chapters.first().number)
        assertEquals(109f, chapters.last().number)
        assertTrue(chapters.all { it.url.startsWith("/Novel/718891/") })
    }

    @Test
    fun `chapter body keeps paragraphs and drops page furniture`() {
        val html = parser.extractChapter(fixture("chapter.html"))
        assertNotNull(html)
        assertTrue(html!!.contains("<p>"))
        assertFalse(html.contains("<script"))
        val text = Jsoup.parseBodyFragment(html).body().text()
        assertTrue(text.length > 500)
        assertTrue(text.contains("花咲澪"))
    }

    @Test
    fun `paywalled chapter yields null so caller can show a notice`() {
        val doc = Jsoup.parse(
            """<div class="article-content font16" id="ChapterBody"><p>本章为VIP章节，请订阅后阅读</p></div>""",
            "https://book.sfacg.com/",
        )
        assertNull(parser.extractChapter(doc))
    }

    @Test
    fun `list card state is unknown but detail-style text is parsed`() {
        val doc = Jsoup.parse(
            """<ul class="Comic_Pic_List">
                <li class="Conjunction"><a href="/Novel/1/"><img src="/c.jpg" alt="书"></a></li>
                <li><strong><a href="/Novel/1/">书</a></strong><br/> 作 者： <a href="/Club/9/">某人</a><br/>
                综合信息： <span class="font_red">7.5分</span> / <a href="/List/?tid=25">校园</a> / 2026/11/6 / 1200字<br/> 一段足够长的简介文字用来验证解析</li>
            </ul>""",
            "https://book.sfacg.com/",
        )
        val item = parser.parseListPage(doc).single()
        // 书库卡片本身不带连载状态（状态只出现在详情页「字数：[连载中]」里）
        assertNull(item.state)
        assertEquals(0.75f, item.rating, 0.001f)
        assertEquals("校园", item.tags.first().title)

        val finishedDoc = Jsoup.parse(
            """<ul class="Comic_Pic_List">
                <li class="Conjunction"><a href="/Novel/2/"><img src="/c.jpg" alt="书"></a></li>
                <li><strong><a href="/Novel/2/">书</a></strong><br/> 作 者： <a href="/Club/9/">某人</a><br/>
                综合信息： <span class="font_red">8.0分</span> / <a href="/List/?tid=21">魔幻</a> / 2026/11/6 / 1200字[已完结]<br/> 另一段足够长的简介文字用来验证解析</li>
            </ul>""",
            "https://book.sfacg.com/",
        )
        assertEquals(ContentState.FINISHED, parser.parseListPage(finishedDoc).single().state)
    }
}
