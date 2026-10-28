package org.skepsun.kototoro.parsers.site.zh

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * 离线夹具测试：验证 51cg (Mirages 主题) 的噪音过滤与视频/图集提取规则。
 */
class Cg51ParserTest {

    private val parser = Cg51(ContentLoaderContextMock)

    private fun fixture(name: String, baseUri: String = "https://51cg1.com/") = Jsoup.parse(
        javaClass.getResourceAsStream("/fixtures/cg51/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: $name"),
        baseUri,
    )

    private fun content(url: String = "/archives/200001/") = Content(
        id = 200001L,
        title = "测试视频帖",
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://51cg1.com$url",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        description = null,
        chapters = null,
        source = parser.source,
    )

    @Test
    fun `list keeps archive posts and drops ad items`() {
        val result = parser.parseList(fixture("list.html"))

        // 2 个 /archives/ 文章保留; 2 个 ad-item 广告与 1 个暴力关键词文章被过滤
        assertEquals(2, result.size)
        assertEquals("https://51cg1.com/archives/100001/", result[0].url)
        // 标题不带 "热搜 HOT" 角标
        assertEquals("测试标题甲 第一集", result[0].title)
        // 封面取 loadBannerDirect 脚本地址
        assertEquals("https://img.example.com/cover-100001.jpeg", result[0].coverUrl)
        // 第二条封面走 data-xkrkllgl 懒加载属性
        assertEquals("https://img.example.com/cover-100002.jpeg", result[1].coverUrl)
    }

    @Test
    fun `list cover uses z-bg-loader-url in 2026 template`() {
        val result = parser.parseList(fixture("list_bg_loader.html"))

        assertEquals(2, result.size)
        // 2026-10 模板: 卡片里只有 div.blog-background[z-bg-loader-url]
        assertEquals("https://pic.example.com/cover-200001.jpeg", result[0].coverUrl)
        // 标题不带 post-card-info 里的"热门"角标
        assertEquals("新模板标题甲 第一集", result[0].title)
        // 备用写法: background-image 内联样式
        assertEquals("https://pic.example.com/cover-200002.jpeg", result[1].coverUrl)
        assertEquals("新模板标题乙 第二集", result[1].title)
    }

    @Test
    fun `detail description excludes noise blocks`() {
        val result = parser.parseDetails(fixture("detail_video.html"), content())

        val desc = result.description ?: error("描述为空")
        assertTrue(desc.contains("这是测试视频帖的正文描述第一段"), "描述应包含正文: $desc")
        assertTrue(desc.contains("正文的第二段"), "描述应包含正文第二段: $desc")
        // 各类噪音不得混入描述
        assertFalse(desc.contains("51推广"), "广告按钮混入描述")
        assertFalse(desc.contains("测试站最新地址"), "站址公告混入描述")
        assertFalse(desc.contains("下载APP"), "下载按钮混入描述")
        assertFalse(desc.contains("上一篇"), "上下篇混入描述")
        assertFalse(desc.contains("热门推荐"), "推荐区混入描述")
        assertFalse(desc.contains("评论内容"), "评论区混入描述")
        assertFalse(desc.contains("吃瓜中心"), "导航表格混入描述")
        assertFalse(desc.contains("转载请注明"), "版权声明混入描述")
        // 元信息不应出现在开头（标题/作者/日期/面包屑）
        assertTrue(desc.startsWith("这是测试"), "描述应以正文开头: $desc")
    }

    @Test
    fun `detail tags come from post tags block only`() {
        val result = parser.parseDetails(fixture("detail_video.html"), content())

        assertEquals(setOf("测试标签一", "测试标签二", "测试标签三"), result.tags.mapTo(linkedSetOf()) { it.title })
    }

    @Test
    fun `detail video chapter uses main dplayer url only`() {
        val result = parser.parseDetails(fixture("detail_video.html"), content())

        val chapters = result.chapters ?: error("章节为空")
        // 只识别一个视频章节: H.265 版与贴片广告不得生成额外章节
        assertEquals(1, chapters.size)
        assertEquals("Video 1", chapters[0].title)
        assertTrue(chapters[0].url.endsWith("#video_index=0"))
    }

    @Test
    fun `gallery detail creates single gallery chapter`() {
        val result = parser.parseDetails(fixture("detail_gallery.html"), content("/archives/300001/"))

        val chapters = result.chapters ?: error("章节为空")
        assertEquals(1, chapters.size)
        assertEquals("Gallery", chapters[0].title)
        assertFalse(chapters[0].url.contains("#video_index="), "图集章节不应带视频索引")
    }

    @Test
    fun `detail cover prefers itemprop image over og image`() {
        val result = parser.parseDetails(fixture("detail_video.html"), content())

        // og:image 是主题默认分享图, 应使用 meta[itemprop=image]
        assertEquals("https://img.example.com/cover-200001.jpeg", result.largeCoverUrl)
    }

    @Test
    fun `gallery images exclude assets and ads`() {
        val doc = fixture("detail_gallery.html")
        val body = doc.selectFirst("#post .post-content[itemprop=articleBody]") ?: error("正文容器未找到")

        val images = parser.parseGalleryImages(body)
        // 3 张内容图保留; 主题 icon 与广告脚本图被过滤
        assertEquals(
            listOf(
                "https://img.example.com/gallery/001.jpeg",
                "https://img.example.com/gallery/002.jpeg",
                "https://img.example.com/gallery/003.jpeg",
            ),
            images,
        )
    }
}
