package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.ArrayList
import java.util.EnumSet

/**
 * 笔趣阁 - 网络小说
 * 
 * API端点：
 * - 首页: /api/index?sort=index
 * - 分类: /api/sort?sort={category}
 * - 搜索: /api/search?q={keyword}
 * - 详情: /api/book?id={id}
 * - 章节: /api/chapter?id={bookId}&chapterid={chapterId}
 */
@ContentSourceParser("BIQUGE", "笔趣阁", "zh", type = ContentType.NOVEL)
internal class Biquge(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.BIQUGE, pageSize = 150) {

    override val configKeyDomain = ConfigKey.Domain("www.fab00db.icu")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableTags = buildFilterTags(),
        )
    }

    private fun buildFilterTags(): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        
        // 分类标签
        tags += ContentTag("玄幻", "xuanhuan", source)
        tags += ContentTag("武侠", "wuxia", source)
        tags += ContentTag("都市", "dushi", source)
        tags += ContentTag("历史", "lishi", source)
        tags += ContentTag("网游", "wangyou", source)
        tags += ContentTag("科幻", "kehuan", source)
        tags += ContentTag("女生", "mm", source)
        tags += ContentTag("完本", "finish", source)
        
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 笔趣阁API不支持分页，每次返回所有结果（约150本）
        if (page > 1) {
            return emptyList()
        }
        
        val category = filter.tags.firstOrNull()?.key ?: "xuanhuan"
        
        val apiUrl = if (!filter.query.isNullOrBlank()) {
            // 搜索
            "https://$domain/api/search?q=${filter.query.urlEncoded()}"
        } else {
            // 分类列表
            "https://$domain/api/sort?sort=$category"
        }
        
        val json = webClient.httpGet(apiUrl).parseJson()
        return parseNovelList(json)
    }

    override suspend fun getDetails(manga: Content): Content {
        val bookId = manga.url.substringAfterLast("/")
        val apiUrl = "https://$domain/api/book?id=$bookId"
        
        val json = webClient.httpGet(apiUrl).parseJson()
        return parseNovelDetail(manga, json)
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val content = getChapterContent(chapter) ?: return listOf(createErrorPage("内容为空"))
        val dataUrl = content.html.toDataUrl()
        return listOf(
            ContentPage(
                id = generateUid(chapter.url),
                url = dataUrl,
                preview = null,
                source = source,
            )
        )
    }

    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? {
        // URL格式: /book/{bookId}/{chapterId}
        val parts = chapter.url.split("/")
        if (parts.size < 4) {
            return NovelChapterContent(
                html = buildErrorHtml("Invalid chapter URL"),
                images = emptyList()
            )
        }
        
        val bookId = parts[2]
        val chapterId = parts[3]
        
        val apiUrl = "https://$domain/api/chapter?id=$bookId&chapterid=$chapterId"
        
        return try {
            val json = webClient.httpGet(apiUrl).parseJson()
            val content = json.optString("txt", "")
            val chapterName = json.optString("chaptername", chapter.title ?: "")
            
            if (content.isEmpty()) {
                return NovelChapterContent(
                    html = buildErrorHtml("章节内容为空"),
                    images = emptyList()
                )
            }
            
            val html = buildChapterHtml(chapterName, content)
            NovelChapterContent(html = html, images = emptyList())
        } catch (e: Exception) {
            NovelChapterContent(
                html = buildErrorHtml("加载失败: ${e.message}"),
                images = emptyList()
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        return page.url
    }

    // ========== 辅助方法（需要根据实际API实现） ==========

    /**
     * 解析小说列表
     */
    private fun parseNovelList(json: JSONObject): List<Content> {
        val result = ArrayList<Content>()
        val items = json.optJSONArray("data") ?: return result
        
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            
            val id = item.optString("id", "")
            if (id.isEmpty()) continue
            
            val title = item.optString("title", "")
            if (title.isEmpty()) continue
            
            val author = item.optString("author", "")
            val intro = item.optString("intro", "")
            
            // 生成封面URL
            val coverUrl = generateCoverUrl(id)
            
            result += Content(
                id = generateUid("/book/$id"),
                url = "/book/$id",
                publicUrl = "https://$domain/#/book/$id",
                title = title,
                altTitles = emptySet(),
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
                tags = emptySet(),
                description = intro.ifEmpty { null },
                rating = RATING_UNKNOWN,
                contentRating = null,
                state = null,
                source = source,
            )
        }
        
        return result
    }

    /**
     * 解析小说详情
     */
    private fun parseNovelDetail(manga: Content, json: JSONObject): Content {
        val title = json.optString("title", manga.title)
        val author = json.optString("author", "")
        val intro = json.optString("intro", "")
        val sortname = json.optString("sortname", "")
        val full = json.optString("full", "")
        val lastChapterId = json.optInt("lastchapterid", 0)
        
        // 状态
        val state = when {
            full.contains("完结") || full.contains("完本") -> ContentState.FINISHED
            full.contains("连载") -> ContentState.ONGOING
            else -> null
        }
        
        // 标签
        val tags = if (sortname.isNotEmpty()) {
            setOf(ContentTag(sortname, sortname, source))
        } else {
            emptySet()
        }
        
        // 生成章节列表
        // 注意：API不直接返回章节列表，需要根据lastchapterid推断
        val chapters = ArrayList<ContentChapter>()
        if (lastChapterId > 0) {
            for (i in 1..lastChapterId) {
                chapters += ContentChapter(
                    id = generateUid("/book/${json.optString("id")}/$i"),
                    title = "第${i}章",
                    number = i.toFloat(),
                    volume = 0,
                    url = "/book/${json.optString("id")}/$i",
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }
        }
        
        // 生成封面URL
        val bookId = json.optString("id", "")
        val coverUrl = if (bookId.isNotEmpty()) generateCoverUrl(bookId) else manga.coverUrl
        
        return manga.copy(
            title = title,
            authors = if (author.isNotEmpty()) setOf(author) else manga.authors,
            description = intro.ifEmpty { manga.description },
            state = state,
            tags = tags,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            chapters = chapters,
        )
    }

    /**
     * 生成封面URL
     * 格式: https://www.fab00db.icu/bookimg/{前3位数字}/{完整ID}.jpg
     */
    private fun generateCoverUrl(bookId: String): String? {
        if (bookId.length < 3) return null
        
        val prefix = bookId.take(3)
        return "https://$domain/bookimg/$prefix/$bookId.jpg"
    }

    /**
     * 构建章节HTML
     */
    private fun buildChapterHtml(title: String, content: String): String {
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n")
            append("<style>\n")
            append("body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;")
            append("padding:16px;margin:0;line-height:1.9;font-size:1.05rem;}\n")
            append("h1{font-size:1.3rem;margin-bottom:1rem;}\n")
            append("p{margin:0 0 1rem;text-indent:2em;display:block;}\n")
            append("</style>\n</head>\n<body>\n")
            append("<h1>").append(title).append("</h1>\n")
            
            // 将内容按段落分割
            val paragraphs = content.split("\n")
            for (para in paragraphs) {
                val trimmed = para.trim()
                if (trimmed.isNotEmpty()) {
                    append("<p>").append(trimmed).append("</p>\n")
                }
            }
            
            append("</body>\n</html>")
        }
    }

    private fun buildErrorHtml(message: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8"/>
        <style>body{font-family:sans-serif;padding:16px;}</style>
        </head><body><h1>错误</h1><p>$message</p></body></html>
    """.trimIndent()

    private fun createErrorPage(message: String): ContentPage {
        val html = buildErrorHtml(message)
        return ContentPage(
            id = generateUid(message),
            url = html.toDataUrl(),
            preview = null,
            source = source,
        )
    }

    /**
     * 将HTML转换为Data URL
     */
    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
