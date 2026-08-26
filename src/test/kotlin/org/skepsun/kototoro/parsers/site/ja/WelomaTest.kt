package org.skepsun.kototoro.parsers.site.ja

import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.LinkResolver
import java.util.Locale

class WelomaTest {

    private val context = ContentLoaderContextMock
    private val parser = Weloma(context)

    // SATANOPHANY - RAW（详情页含作者/简介/338 章，可用于详情与阅读页验证）
    private val testToken = "0zzyL"
    private val testUrl = "https://${parser.domain}/m/$testToken"

    @Test
    fun testGetListPage() = runBlocking {
        val mangaList = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        println("Fetched ${mangaList.size} manga from latest")
        mangaList.take(5).forEach { manga ->
            println("Title: ${manga.title}, URL: ${manga.url}")
        }
        assert(mangaList.isNotEmpty()) { "Should fetch manga list" }
        assert(mangaList.all { it.url.startsWith("/m/") }) { "URLs should be relative /m/ links" }
        assert(mangaList.all { it.id != 0L }) { "IDs should be non-zero" }
    }

    @Test
    fun testSearch() = runBlocking {
        val query = "Blue Lock"
        val searchFilter = ContentListFilter(query = query)
        val searchResults = parser.getListPage(1, SortOrder.POPULARITY, searchFilter)
        println("Searched for '$query', found ${searchResults.size} results")
        searchResults.take(10).forEach { manga ->
            println("Search result: ${manga.title}, URL: ${manga.url}")
        }
        assert(searchResults.isNotEmpty()) { "Should find results for '$query'" }
        assert(searchResults.any { it.title.contains(query, ignoreCase = true) }) {
            "Should contain 'Blue Lock' in results"
        }
    }

    @Test
    fun testGetDetails() = runBlocking {
        val testContent = Content(
            id = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter()).first().id,
            title = "SATANOPHANY - RAW",
            altTitles = emptySet(),
            url = "/m/$testToken",
            publicUrl = testUrl,
            rating = 0f,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )

        val detailedContent = parser.getDetails(testContent)
        println("Content Details: ${detailedContent.title}")
        println("Authors: ${detailedContent.authors}")
        println("Tags: ${detailedContent.tags.map { it.title }}")
        println("Description: ${detailedContent.description?.take(100)}...")
        println("Chapters: ${detailedContent.chapters?.size ?: 0}")

        assert(detailedContent.title.isNotBlank())
        assert(detailedContent.chapters?.isNotEmpty() ?: false)
        assert(detailedContent.tags.isNotEmpty())
        assert(detailedContent.authors.isNotEmpty())

        // 章节升序排列
        val chapters = detailedContent.chapters!!
        if (chapters.size > 1) {
            assert(chapters[0].number < chapters.last().number) { "Chapters should be in ascending order" }
        }
        // 标题交给应用格式化（number 驱动的默认格式）
        assert(chapters.first().title == null) { "Chapter title should be null for default formatting" }
    }

    @Test
    fun testGetPages() = runBlocking {
        val seed = Content(
            id = 1L,
            title = "SATANOPHANY - RAW",
            altTitles = emptySet(),
            url = "/m/$testToken",
            publicUrl = testUrl,
            rating = 0f,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parser.source,
        )
        val detailedContent = parser.getDetails(seed)
        val firstChapter = detailedContent.chapters?.firstOrNull()

        assert(firstChapter != null) { "Should find at least one chapter" }
        println("Testing chapter: ${firstChapter!!.number}, URL: ${firstChapter.url}")

        val pages = parser.getPages(firstChapter)
        println("Fetched ${pages.size} pages")

        assert(pages.isNotEmpty()) { "Should fetch pages" }
        // data-img 内的值经 base64 解码后应是绝对地址
        assert(pages.all { it.url.startsWith("http") }) { "All page URLs should be absolute" }
    }

    // 复现 App 中文环境下的构造函数 NPE：BUILTIN_TAGS 初始化时访问尚未初始化的
    // tagTranslations（tagTranslations 已提升为文件级属性，与实例初始化顺序解耦）。
    private class ZhContentLoaderContext(
        private val delegate: ContentLoaderContext = ContentLoaderContextMock,
    ) : ContentLoaderContext() {
        override val httpClient: OkHttpClient get() = delegate.httpClient
        override val cookieJar: CookieJar get() = delegate.cookieJar
        override fun newParserInstance(source: ContentSource): ContentParser = delegate.newParserInstance(source)
        override fun newLinkResolver(link: HttpUrl): LinkResolver = delegate.newLinkResolver(link)
        override suspend fun evaluateJs(script: String): String? = delegate.evaluateJs(script)
        override suspend fun evaluateJs(baseUrl: String, script: String): String? = delegate.evaluateJs(baseUrl, script)
        override fun getConfig(source: ContentSource): ContentSourceConfig = delegate.getConfig(source)
        override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()
        override fun redrawImageResponse(response: okhttp3.Response, redraw: (org.skepsun.kototoro.parsers.bitmap.Bitmap) -> org.skepsun.kototoro.parsers.bitmap.Bitmap): okhttp3.Response =
            delegate.redrawImageResponse(response, redraw)
        override fun createBitmap(width: Int, height: Int): org.skepsun.kototoro.parsers.bitmap.Bitmap =
            delegate.createBitmap(width, height)
        override fun getPreferredLocales(): List<Locale> = listOf(Locale("zh", "CN"))
    }

    @Test
    fun testZhLocaleConstructorDoesNotCrash() = runBlocking {
        val zhParser = Weloma(ZhContentLoaderContext())
        val options = zhParser.getFilterOptions()
        val titles = options.availableTags.map { it.title }
        println("zh 环境标签样例: ${titles.take(5)}")
        assert(titles.contains("动作")) { "zh 环境下标签应翻译成中文; got $titles" }
        assert(!titles.contains("Action")) { "zh 环境下不应保留英文标签" }
        assert(options.tagGroups.isNotEmpty())
        assert(options.tagGroups.first().title == "标签")
    }
}
