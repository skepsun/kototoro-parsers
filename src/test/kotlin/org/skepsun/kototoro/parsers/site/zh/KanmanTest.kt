package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

// 看漫网离线夹具测试：
// - 列表/搜索是 /api/getsortlist/ 的 JSON（HTML 分页在服务端已经坏了），夹具取真实响应前 3 条；
// - 详情页题材链接的 class 属性被站点写了两遍，只能按 p.tags a + href 选；
// - 作者在 .zzjs 的 swiper 里，同一容器混着角色卡片，只有 /zuozhe/ 链接的那几张算作者；
// - 目录 li.item > a[title]，顺序已经是阅读顺序，话号写法不统一所以按位置编号；
// - 阅读页图片内联在 window.comicInfo 的 chapter_img_list 里（JS 字面量，不是 JSON）。
class KanmanTest {

    private val parser = Kanman(ContentLoaderContextMock)

    private fun fixture(name: String): Document = Jsoup.parse(
        fixtureText(name),
        "https://www.kanman.com/",
    )

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/kanman/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name")

    @Test
    fun `sort orders and search capability are advertised`() {
        assertEquals(setOf(SortOrder.POPULARITY, SortOrder.UPDATED), parser.availableSortOrders)
        assertTrue(parser.filterCapabilities.isSearchSupported)
        assertFalse(parser.filterCapabilities.isMultipleTagsSupported)
        assertFalse(parser.filterCapabilities.isTagsExclusionSupported)
        assertFalse(parser.filterCapabilities.isSearchWithFiltersSupported)
        // 站点自己的移动端客户端就是固定 size=30
        assertEquals(30, parser.pageSize)
    }

    @Test
    fun `filter options expose the real genre slugs and progress states`() = kotlinx.coroutines.runBlocking {
        val options = parser.getFilterOptions()
        val tags = options.tagGroups.single().tags
        assertEquals("题材", options.tagGroups.single().title)
        assertEquals(tags, options.availableTags)
        assertEquals(32, tags.size)
        assertEquals("热血", tags.first { it.key == "rexue" }.title)
        assertEquals("萝莉", tags.first { it.key == "luoli" }.title)
        assertEquals("动作", tags.last().title)
        // 地区 / 专题 / 字母分组实测不被 comic_sort 接受，不能暴露
        assertFalse(tags.any { it.key == "dalu" })
        assertEquals(setOf(ContentState.ONGOING, ContentState.FINISHED), options.availableStates)
    }

    @Test
    fun `list json maps title url cover tags and authors`() {
        val items = parser.parseListJson(fixtureText("list.json"), "https://www.kanman.com/api/getsortlist/")
        assertEquals(3, items.size)

        val first = items.first()
        assertEquals("偷星九月天", first.title)
        assertEquals("/25935/", first.url)
        assertEquals("https://www.kanman.com/25935/", first.publicUrl)
        // 接口给的是 http:// 的封面，统一升到 https
        assertEquals("https://image.yqmh.com/mh/25935_3_4.jpg-noresize.webp", first.coverUrl)
        assertEquals(setOf("周洪滨工作室"), first.authors)
        assertEquals(
            listOf("热血", "冒险", "玄幻"),
            first.tags.map { it.title },
        )
        assertEquals(listOf("rexue", "maoxian", "xuanhuan"), first.tags.map { it.key })
        assertNull(first.state)
        assertTrue(first.rating < 0f)
        assertEquals(items.size, items.map { it.id }.distinct().size)
    }

    @Test
    fun `list json parsing fails loudly on a changed payload`() {
        assertThrows(ParseException::class.java) {
            parser.parseListJson("<html>维护中</html>", "https://www.kanman.com/api/getsortlist/")
        }
        assertThrows(ParseException::class.java) {
            parser.parseListJson("""{"data":{"page":{}}}""", "https://www.kanman.com/api/getsortlist/")
        }
        // 结构合法但这一页确实没有内容，属于正常的列表结尾
        assertTrue(
            parser.parseListJson(
                """{"data":{"page":{"count":0},"data":[]}}""",
                "https://www.kanman.com/api/getsortlist/",
            ).isEmpty(),
        )
    }

    @Test
    fun `genre list ignores the progress slugs mixed into comic_type`() {
        val tags = parser.parseGenres("rexue,热血|lianzai,连载|maoxian,冒险")
        assertEquals(listOf("rexue", "maoxian"), tags.map { it.key })

        assertTrue(parser.parseGenres("").isEmpty())
        assertTrue(parser.parseGenres("没有逗号的脏数据").isEmpty())
    }

    @Test
    fun `detail page maps header fields`() {
        val details = parser.parseDetails(fixture("detail.html"))
        assertNotNull(details)
        details ?: return

        assertEquals("凤逆天下", details.title)
        // 角色卡片（/juese/）不能混进作者
        assertEquals(setOf("飒漫画", "路非", "桔子茶"), details.authors)
        assertEquals("https://image.yqmh.com/mh/17745.jpg-300x400.jpg", details.coverUrl)
        assertEquals("https://image.yqmh.com/mh/17745_2_1.jpg", details.largeCoverUrl)
        assertTrue(details.description?.startsWith("【独家/每周五更新】") == true)
        assertEquals(ContentState.ONGOING, details.state)
        assertEquals(
            listOf("冒险", "神魔", "漫改", "穿越", "精品"),
            details.tags.map { it.title },
        )
        assertEquals("maoxian", details.tags.first().key)
        // 「自制」是 span.stag 标记，不是题材
        assertFalse(details.tags.any { it.title == "自制" })
    }

    @Test
    fun `finished comic state is detected`() {
        val html = """
            <html><body>
              <div class="acgn-model-detail-frontcover"><h1 class="title">穿越西元3000后</h1></div>
              <div class="mhjj"><div class="update-info"><p class="status">完结<span class="spot">·</span></p></div></div>
            </body></html>
        """.trimIndent()
        assertEquals(ContentState.FINISHED, parser.parseDetails(Jsoup.parse(html, "https://www.kanman.com/"))?.state)
    }

    @Test
    fun `page without a comic title is not a detail page`() {
        val html = "<html><body><div class=\"acgn-404\">页面不存在</div></body></html>"
        assertNull(parser.parseDetails(Jsoup.parse(html, "https://www.kanman.com/")))
    }

    @Test
    fun `chapter list keeps reading order and numbers sequentially`() {
        val chapters = parser.parseChapterList(fixture("detail.html"))
        assertEquals(4, chapters.size)
        assertEquals(
            listOf("第1话 神秘的黑玉1", "第2话 神秘的黑玉2", "第3话 神秘的黑玉3", "第592话 亲手斩断8"),
            chapters.map { it.title },
        )
        // 话号不统一（番外/停更通知/第2季），按目录位置编号，保证单调
        assertEquals(listOf(1f, 2f, 3f, 4f), chapters.map { it.number })
        assertEquals("/17745/1004.html", chapters.first().url)
        assertEquals(
            "https://www.kanman.com/17745/1004.html",
            chapters.first().url.let { "https://www.kanman.com$it" },
        )
        assertEquals(chapters.size, chapters.map { it.id }.distinct().size)
        // 未购买的 li.item.lock 也保留，让客户端自己提示，而不是静默丢章
        assertEquals("/17745/di592hua-1711420890.html", chapters.last().url)
    }

    @Test
    fun `chapter links are restricted to real chapter paths`() {
        assertEquals("/17745/1004.html", parser.toChapterPath("/17745/1004.html"))
        assertEquals("/17745/di592hua-1711420890.html", parser.toChapterPath("/17745/di592hua-1711420890.html?x=1"))
        assertNull(parser.toChapterPath("/zuozhe/64110455/"))
        assertNull(parser.toChapterPath("#"))
        assertNull(parser.toChapterPath("javascript:void(0)"))
    }

    @Test
    fun `reader page exposes every image of the chapter`() {
        val images = parser.parseReaderPages(fixtureText("reader.html"))
        assertEquals(3, images.size)
        assertTrue(images.all { it.startsWith("https://hw-chapter2.kaimanhua.com/comic/") })
        // 签名（?auth_key=时间戳-两段 hash）必须原样保留，否则图片会 403
        assertTrue(images[0].contains("/1.jpg?auth_key=1700000000-"))
        assertTrue(images[0].endsWith("-0123456789abcdef0123456789abcdef"))
        assertTrue(images[1].contains("/2.jpg?"))
        assertTrue(images[2].contains("/3.jpg?"))
    }

    @Test
    fun `reader page without an image list yields nothing`() {
        val html = "<html><body><script>window.comicInfo={comic_id:1,current_chapter:{}}</script></body></html>"
        assertTrue(parser.parseReaderPages(html).isEmpty())
        assertTrue(parser.parseReaderPages("<html><body>章节已下架</body></html>").isEmpty())
    }

    @Test
    fun `reader page request is cache busted to get a live signature`() {
        // 页面缓存 12 小时、签名只活 15~60 分钟，不加随机参数就会拿到已过期的 auth_key
        assertEquals(
            "https://www.kanman.com/17745/1004.html?cachebust=42",
            parser.readerUrl("https://www.kanman.com/17745/1004.html", 42L),
        )
        assertEquals(
            "https://www.kanman.com/17745/1004.html?a=1&cachebust=42",
            parser.readerUrl("https://www.kanman.com/17745/1004.html?a=1", 42L),
        )
        assertTrue(parser.readerUrl("https://www.kanman.com/17745/1004.html", 42L) !=
            parser.readerUrl("https://www.kanman.com/17745/1004.html", 43L))
    }

    @Test
    fun `image urls are normalized to https`() {
        assertEquals("https://image.yqmh.com/mh/1.jpg", parser.normalizeImageUrl("http://image.yqmh.com/mh/1.jpg"))
        assertEquals("https://image.yqmh.com/mh/1.jpg", parser.normalizeImageUrl("//image.yqmh.com/mh/1.jpg"))
        assertEquals(
            "https://image.yqmh.com/mh/1.jpg",
            parser.normalizeImageUrl("https:\\/\\/image.yqmh.com\\/mh\\/1.jpg"),
        )
    }
}
