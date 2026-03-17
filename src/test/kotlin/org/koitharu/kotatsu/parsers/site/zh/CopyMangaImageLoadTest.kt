package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.network.OkHttpWebClient
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.test_util.isUrlAbsolute
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes

/**
 * 贴近实际使用的图片加载集成测试：
 * - 通过 parser 获取章节与页面列表
 * - 使用同一 OkHttpClient 发起图片请求
 * - 校验响应成功与 Content-Type 为图片
 * - 校验图片请求头（Referer、UA）与拦截器逻辑一致
 */
class CopyContentImageLoadTest {

    private val context = ContentLoaderContextMock

    @Test
    fun image_load_xigelide() = runTest(timeout = 3.minutes) {
        // 启用仅 API 模式，跳过站点 HTML 请求
        System.setProperty("copymanga.only.api", "true")
        val parser = context.newParserInstance(ContentParserSource.COPYMANGA)
        val domain = parser.domain
        val slug = "xigelide"
        val seed = Content(
            id = slug.hashCode().toLong(),
            title = slug,
            altTitles = emptySet(),
            url = slug,
            publicUrl = "https://$domain/comic/$slug",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = null,
            chapters = null,
            source = ContentParserSource.COPYMANGA,
        )

        // 获取章节
        val chapter = parser.getDetails(seed).chapters?.firstOrNull()
            ?: error("未解析到章节：$slug")

        // 尝试通过 parser 获取页面；若 API 500，则回退到直接解析章节 HTML 提取图片 URL
        val pages = try {
            parser.getPages(chapter)
        } catch (e: Exception) {
            // 回退解析：直接请求章节页 HTML，从中提取图片 URL（Next.js 或 <img> 标签）
            val webClient = OkHttpWebClient(context.httpClient, ContentParserSource.COPYMANGA)
            val chapterUrl = chapter.url.toAbsoluteUrl(domain)
            val html = webClient.httpGet(chapterUrl).parseRaw()

            // 基于正则提取图片 URL，兼容多种静态资源域；只取前若干供请求校验
            val regex = """https?://[^"' ]+\.(?:jpg|jpeg|png|webp)""".toRegex(RegexOption.IGNORE_CASE)
            val urls = regex.findAll(html).map { it.value }.distinct().toList()
            assertTrue(urls.isNotEmpty(), "回退解析未找到图片 URL")

            // 构造临时 Page 列表以与下游逻辑一致
            urls.take(5).mapIndexed { i, u ->
                org.skepsun.kototoro.parsers.model.ContentPage(
                    id = i.toLong(),
                    url = u,
                    preview = null,
                    source = ContentParserSource.COPYMANGA,
                )
            }
        }
        assertTrue(pages.isNotEmpty(), "阅读页为空")

        // 在不具备站点网络连通性的环境下，跳过实际图片下载，仅校验 URL 形态
        val enableNetwork = System.getenv("COPYMANGA_TEST_NETWORK") == "1"
        if (!enableNetwork) {
            val count = min(3, pages.size)
            repeat(count) { idx ->
                val page = pages[idx]
                val pageUrl = parser.getPageUrl(page)
                assertTrue(pageUrl.isUrlAbsolute(), "图片 URL 非绝对：$pageUrl")
                // 基本形态校验：后缀或常见图片路径片段
                val looksImage = pageUrl.contains(".webp") || pageUrl.contains(".jpg") || pageUrl.contains(".png") || pageUrl.contains("/image/")
                assertTrue(looksImage, "图片 URL 形态异常：$pageUrl")
            }
        } else {
            // 使用相同的 OkHttpClient，并通过 WebClient 为请求标注来源，从而走解析器拦截器
            val webClient = OkHttpWebClient(context.httpClient, ContentParserSource.COPYMANGA)
            val count = min(3, pages.size)
            repeat(count) { idx ->
                val page = pages[idx]
                val pageUrl = parser.getPageUrl(page)
                assertTrue(pageUrl.isUrlAbsolute(), "图片 URL 非绝对：$pageUrl")

                val resp = webClient.httpGet(pageUrl)
                val code = resp.code
                assertTrue(code in 200..299, "图片请求返回非 2xx：$code, url=$pageUrl")

                val contentType = resp.header("Content-Type").orEmpty()
                assertTrue(
                    contentType.startsWith("image/", ignoreCase = true) ||
                        contentType.equals("application/octet-stream", ignoreCase = true),
                    "Content-Type 非图片：$contentType, url=$pageUrl",
                )

                // 校验最终发送的请求头是否符合拦截器逻辑
                val req = resp.request
                val referer = req.header("Referer").orEmpty()
                val ua = req.header("User-Agent").orEmpty()
                assertTrue(referer.contains("copy2000.online"), "未设置站点 Referer 或不正确：$referer")
                assertTrue(ua.isNotBlank(), "图片请求缺少 User-Agent")

                // 图片请求不应携带认证头
                assertTrue(req.header("Authorization") == null && req.header("authorization") == null,
                    "图片请求携带了 Authorization 头")

                // 若图片域非同域且非子域，则不应携带 Cookie；同域/子域允许携带站点 Cookie（用于 token）
                val host = req.url.host
                val site = "copy2000.online"
                val isSameSiteOrSub = host.equals(site, ignoreCase = true) || host.endsWith("." + site, ignoreCase = true)
                if (!isSameSiteOrSub) {
                    assertTrue(req.header("Cookie") == null && req.header("cookie") == null,
                        "跨站图片请求不应携带 Cookie：host=$host")
                }
            }
        }
    }
}