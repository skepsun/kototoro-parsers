package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

// 九二漫画（92mh.com）离线夹具测试：
// - 列表：li.list-comic，书名 h3 a，封面 a.comic_img img，作者/类型/状态在 p 文本里；
// - 分类：只有「按类型」组是顶层可用标签（其余组带分类前缀），「全部」不算标签；
// - 详情：h1 + ul.comic_deCon_liO + p.comic_deCon_d + 内联脚本 var pageImage + ul#chapter-list-1；
// - 阅读页：内联脚本 var chapterImages 给出根路径，图片域名来自 /js/config.js 的 resHost。
class JiuermanhuaTest {

    private val parser = Jiuermanhua(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/jiuermanhua/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        "https://www.92mh.com/",
    )

    @Test
    fun `capabilities advertise search with three sort orders`() {
        assertEquals(setOf(SortOrder.NEWEST, SortOrder.UPDATED, SortOrder.POPULARITY), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertEquals(36, parser.pageSize)
    }

    @Test
    fun `list page maps card title cover author tags and state`() {
        val items = parser.parseListPage(fixture("list.html"))
        assertEquals(3, items.size)

        val first = items[0]
        assertEquals("1级打架辅导", first.title)
        assertEquals("/manhua/7702/", first.url)
        assertEquals("https://www.92mh.com/manhua/7702/", first.publicUrl)
        assertEquals("http://cover.alltucdn.cc/images/cover/202610/1793237520t8QcecbDCFSCKZkI.jpg", first.coverUrl)
        assertEquals(setOf("金泰景"), first.authors)
        assertEquals(setOf("校园", "格斗"), first.tags.map { it.title }.toSet())
        assertEquals(ContentState.ONGOING, first.state)
    }

    @Test
    fun `list page ids stay domain independent`() {
        val doc = fixture("list.html")
        val withAbsolute = parser.parseListPage(doc).map { it.id }
        val relative = parser.parseListPage(doc).map { it.url }

        assertEquals(listOf("/manhua/7702/", "/manhua/7701/", "/manhua/7700/"), relative)
        assertEquals(relative.size, withAbsolute.size)
        assertTrue(withAbsolute.none { it == 0L })
    }

    @Test
    fun `search results use a different card shape`() {
        // 搜索结果卡片没有 h3 和 span.comic_list_det：书名在 p > a，作者在 p.auth
        val items = parser.parseListPage(fixture("search.html"))
        assertEquals(1, items.size)
        assertEquals("斗破苍穹", items[0].title)
        assertEquals("/manhua/5344/", items[0].url)
        assertEquals("http://cover.alltucdn.cc/images/cover/201807/1531391879L3ryjZ-Dqb5hKWbL.jpg", items[0].coverUrl)
        assertEquals(setOf("天蚕土豆知音漫客任翔"), items[0].authors)
    }

    @Test
    fun `categories keep only the top level type group`() {
        val tags = parser.parseCategories(fixture("list.html"))
        assertEquals(listOf("儿童漫画", "少年漫画", "少女漫画", "青年漫画"), tags.map { it.title })
        assertEquals(listOf("ertong", "shaonian", "shaonv", "qingnian"), tags.map { it.key })
        assertTrue(tags.none { it.title == "全部" }, "「全部」是当前分类的入口，不是标签")
    }

    @Test
    fun `detail page reads title cover author tags description and chapters`() {
        val doc = fixture("detail.html")
        assertEquals("脑洞学生会", doc.selectFirst("h1")?.text())
        assertEquals(
            "http://cover.alltucdn.cc/images/cover/202307/1690265966MiKzCALBXd0jeRdC.jpg",
            parser.pageVar(doc, "pageImage"),
        )

        val info = doc.select("ul.comic_deCon_liO li").map { it.text() }
        assertTrue(info.any { it.startsWith("作者：むちきろ") })
        assertTrue(info.any { it.startsWith("状态") && it.contains("连载中") })
        assertEquals(
            listOf("欢乐向", "生活"),
            parser.parseGenreNames(info.first { it.startsWith("类型") }.substringAfter("类型")),
        )
        assertTrue(doc.selectFirst("p.comic_deCon_d")?.text().orEmpty().startsWith("发生在学生会里的热闹校园喜剧"))

        val chapters = parser.parseChapters(doc)
        assertEquals(7, chapters.size)
        assertEquals(listOf("第01话", "第02话", "第03话", "第04话"), chapters.take(4).map { it.title })
        assertEquals(listOf(1f, 2f, 3f, 4f), chapters.take(4).map { it.number })
        assertEquals("/manhua/73/3866.html", chapters.first().url)
        assertTrue(chapters.any { it.title?.contains("番外") == true })
        assertTrue(chapters.any { it.title?.contains("单行本") == true })
    }

    @Test
    fun `chapter numbers stay monotonic even when titles repeat a number`() {
        val chapters = parser.parseChapters(fixture("detail.html"))
        val numbers = chapters.map { it.number }

        // 「03话番外」标题写的是 3，但排在「第04话」之后，不能把序号拉回去
        val fanwai = chapters.first { it.title?.contains("番外") == true }
        assertTrue(fanwai.number > 4f, "番外章节号应大于第04话，实际 ${fanwai.number}")
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.distinct().size, numbers.size, "章节号不应重复")
        // 解析不出数字的标题（单行本 / 元旦祝贺）按顺序补号
        assertEquals(6f, chapters[5].number)
        assertEquals(7f, chapters[6].number)
    }

    @Test
    fun `long series merges every chapter list segment in reading order`() {
        // 斗破苍穹：chapter-list-1 是最新几话、chapter-list-3 是公告、chapter-list-9 是开头全量；
        // 段内顺序拼起来不是阅读顺序，必须按章节 id 升序重排
        val chapters = parser.parseChapters(fixture("detail_multilist.html"))
        assertEquals(9, chapters.size)

        assertEquals("第1话 陨落的天才（上）13P", chapters.first().title)
        assertEquals("/manhua/5344/231276.html", chapters.first().url)
        assertEquals(1f, chapters.first().number)

        // 公告没有章节号，按顺序补号，不能把后面的章节号顶乱
        val titles = chapters.map { it.title }
        assertTrue(titles.contains("复更公告1P"))
        assertTrue(titles.contains("停更公告1P"))
        assertEquals("停更公告1P", chapters.last().title)
        assertEquals("/manhua/5344/232007.html", chapters.last().url)

        val numbers = chapters.map { it.number }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.distinct().size, numbers.size)
        assertEquals(700f, chapters.first { it.title == "第700话 噬金鼠（上）8P" }.number)
        assertEquals(726f, chapters.first { it.title == "第726话 乘风而去（下）9P" }.number)
    }

    @Test
    fun `reader page joins inline image paths with the cdn host`() {
        val document = fixture("reader.html")
        val config = javaClass.getResourceAsStream("/fixtures/jiuermanhua/config.js")
            ?.bufferedReader()?.use { it.readText() } ?: error("config.js fixture not found")
        assertTrue(config.contains("https://cover.alltucdn.cc"))

        val images = parser.parsePageImages(document, "https://cover.alltucdn.cc")
        assertEquals(3, images.size)
        assertTrue(images.all { it.startsWith("https://cover.alltucdn.cc/images/comic/132/262827/") })
        assertTrue(images.all { it.endsWith(".jpg") })
        assertTrue(images.none { it.contains("\\/") }, "转义斜杠应还原")
    }

    @Test
    fun `reader page without chapterImages yields no pages`() {
        val empty = Jsoup.parse("<html><body><script>var chapterImages = [];</script></body></html>", "https://www.92mh.com/")
        assertTrue(parser.parsePageImages(empty, "https://cover.alltucdn.cc").isEmpty())
        val missing = Jsoup.parse("<html><body><div>章节不存在</div></body></html>", "https://www.92mh.com/")
        assertTrue(parser.parsePageImages(missing, "https://cover.alltucdn.cc").isEmpty())
    }

    @Test
    fun `list url keeps sort in path and filters behind a trailing dash`() {
        val tag = org.skepsun.kototoro.parsers.model.ContentTag("少年漫画", "shaonian", parser.source)

        assertEquals("https://www.92mh.com/list/?page=2", parser.buildListUrl(2, SortOrder.NEWEST, emptySet()))
        assertEquals("https://www.92mh.com/list/update/?page=1", parser.buildListUrl(1, SortOrder.UPDATED, emptySet()))
        assertEquals("https://www.92mh.com/list/-click/?page=3", parser.buildListUrl(3, SortOrder.POPULARITY, emptySet()))

        assertEquals("https://www.92mh.com/list/shaonian-/?page=1", parser.buildListUrl(1, SortOrder.NEWEST, setOf(tag)))
        assertEquals("https://www.92mh.com/list/shaonian-/update/?page=1", parser.buildListUrl(1, SortOrder.UPDATED, setOf(tag)))
        assertEquals("https://www.92mh.com/list/shaonian-/click/?page=1", parser.buildListUrl(1, SortOrder.POPULARITY, setOf(tag)))
    }
}
