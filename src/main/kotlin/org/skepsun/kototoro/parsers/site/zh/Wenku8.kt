package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Cookie
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.EnumSet
import java.util.LinkedHashSet

@MangaSourceParser("WENKU8", "轻小说文库", "zh", type = ContentType.NOVEL)
internal class Wenku8(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WENKU8, pageSize = 20), MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("www.wenku8.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val initialTags = LETTERS.map { letter ->
            MangaTag("首字母 $letter", "initial:$letter", source)
        }
        val categoryTags = CATEGORIES.map { (name, value) ->
            MangaTag(name, "class:$value", source)
        }
        val tagTags = buildTagGroups(source)
        val rankTags = RANKINGS.map { (name, sort) ->
            MangaTag(name, "rank:$sort", source)
        }
        val allTags = (initialTags + categoryTags + tagTags.flatMap { it.tags } + rankTags).toSet()
        return MangaListFilterOptions(
            availableTags = allTags,
            tagGroups = listOf(
                MangaTagGroup(title = "首字母", tags = initialTags.toSet()),
                MangaTagGroup(title = "文库分类", tags = categoryTags.toSet()),
            ) + tagTags + listOf(MangaTagGroup(title = "排行榜", tags = rankTags.toSet())),
        )
    }

    override val authUrl: String = "https://$domain/login.php"

    override suspend fun isAuthorized(): Boolean = context.cookieJar
        .getCookies(domain)
        .any { it.isLoginCookie() }

    override suspend fun getUsername(): String {
        val doc = webClient.httpGet("https://$domain/index.php").parseHtmlGBK("https://$domain/index.php").ensureAuthorized()
        val welcome = doc.selectFirst(".m_top .fl")?.text().orEmpty()
        val username = welcome.substringAfter("欢迎您", "").substringBefore('[').trim()
        if (username.isEmpty()) {
            throw AuthRequiredException(source)
        }
        return username
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = when {
            !filter.query.isNullOrBlank() -> buildSearchUrl(filter.query!!, page)
            else -> buildCatalogUrl(filter, page)
        }
        val doc = webClient.httpGet(url).parseHtmlGBK(url).ensureAuthorized()
        return parseCatalog(doc)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = runCatching { webClient.httpGet(url).parseHtmlGBK(url) }.getOrElse {
            return manga
        }

        // Check authorization but don't throw immediately - try to extract what we can
        val needsAuth = doc.selectFirst("form[name=frmlogin]") != null
        val infoBlock = doc.selectFirst("#content")

        // If no content block and needs auth, bail out but keep existing data
        if (infoBlock == null && needsAuth) {
            return manga
        }
        
        // If no content block but not auth issue, return manga as-is
        if (infoBlock == null) {
            return manga
        }
        
        val title = infoBlock.selectFirst("span b")?.text()?.trim().orEmpty().ifEmpty { manga.title }
        val cover = doc.selectFirst("#content img[src*=/files/]")?.attr("src")?.toAbsoluteUrl(domain)
        val description = infoBlock.extractSectionText("内容简介：")
        val tags = infoBlock.extractTags("作品Tags：").mapToLinkedTags()
        val author = infoBlock.extractValue("小说作者：")
        val state = parseState(infoBlock.extractValue("文章状态："))
        val chapterIndexUrl = infoBlock.selectFirst("a[href*=novel/][href$=index.htm]")
            ?.attr("href")
            ?.toAbsoluteUrl(domain)
            ?: buildChapterIndexUrl(manga)
        
        // Try to fetch chapters, but don't fail if auth is needed
        val chapters = try {
            fetchChapters(chapterIndexUrl)
        } catch (e: AuthRequiredException) {
            if (needsAuth) return manga.copy(
                title = title,
                description = description?.trim().takeIf { it?.isNotEmpty() == true } ?: manga.description,
                coverUrl = cover ?: manga.coverUrl,
                authors = author?.let { setOf(it) } ?: manga.authors,
                tags = if (tags.isNotEmpty()) tags else manga.tags,
                state = state ?: manga.state,
                chapters = emptyList(),
                altTitles = manga.altTitles,
                contentRating = manga.contentRating,
            )
            emptyList()
        }

        return manga.copy(
            title = title,
            description = description?.trim().takeIf { it?.isNotEmpty() == true } ?: manga.description,
            coverUrl = cover ?: manga.coverUrl,
            authors = author?.let { setOf(it) } ?: manga.authors,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            state = state ?: manga.state,
            chapters = chapters,
            altTitles = manga.altTitles,
            contentRating = manga.contentRating,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val content = getChapterContent(chapter) ?: return listOf(createErrorPage("内容为空"))
        val dataUrl = content.html.toDataUrl()
        return listOf(
            MangaPage(
                id = generateUid(chapter.url),
                url = dataUrl,
                preview = null,
                source = source,
            )
        )
    }

    override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
        val url = chapter.url.toAbsoluteUrl(domain)
        return runCatching {
            val doc = webClient.httpGet(url).parseHtmlGBK(url)
            if (doc.selectFirst("form[name=frmlogin]") != null) {
                return NovelChapterContent(
                    html = buildErrorHtml("需要先登录后才能阅读本章节"),
                    images = emptyList()
                )
            }
            
            val content = doc.selectFirst("#content") ?: return null
            content.selectFirst("ul#contentdp")?.remove()
            content.select("script,style,iframe").remove()
            
            val images = mutableListOf<NovelChapterContent.NovelImage>()
            
            // Process images
            content.select("img[src]").forEach { img ->
                val abs = img.absUrl("src").ifBlank { img.attr("src") }
                if (abs.isNullOrBlank()) {
                    img.remove()
                } else {
                    img.attr("src", abs)
                    img.attr("referrerpolicy", "no-referrer")
                    images.add(
                        NovelChapterContent.NovelImage(
                            url = abs,
                            headers = mapOf("Referer" to "https://$domain/")
                        )
                    )
                }
            }
            
            var sanitized = content.html()
            // 压缩过多空行/换行
            sanitized = sanitized.replace(Regex("(\\s*<br[^>]*>\\s*){3,}", RegexOption.IGNORE_CASE), "<br><br>")
                .replace(Regex("(\\s*\\n){3,}"), "\n\n")
            val html = buildString {
                append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
                append("<style>")
                append(
                    "body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;padding:16px;margin:0;" +
                        "line-height:1.9;font-size:1.05rem;}" +
                        "img{max-width:100%;height:auto;}p{margin:0 0 1rem;}h1{font-size:1.3rem;margin-bottom:1rem;}"
                )
                append("</style></head><body>")
                if (!chapter.title.isNullOrBlank()) {
                    append("<h1>").append(chapter.title).append("</h1>")
                }
                append(sanitized)
                append("</body></html>")
            }
            
            NovelChapterContent(html = html, images = images)
        }.getOrElse {
            NovelChapterContent(
                html = buildErrorHtml("加载章节失败：${it.message ?: "未知错误"}"),
                images = emptyList()
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

    private fun parseCatalog(doc: Document): List<Manga> {
        val blocks = doc.select("table.grid div[style*=width:373px]")
        val list = ArrayList<Manga>(blocks.size)
        for (block in blocks) {
            val titleLink = block.selectFirst("b a[href]") ?: continue
            val href = titleLink.attr("href").toRelativeUrl(domain)
            val title = titleLink.text().trim()
            if (title.isEmpty()) continue
            val info = block.select("p")
            var author: String? = null
            var category: String? = null
            var updated: String? = null
            var size: String? = null
            var status: String? = null
            var desc: String? = null
            var tagSet: Set<MangaTag> = emptySet()
            for (p in info) {
                val text = p.text().trim()
                when {
                    text.startsWith("作者:") -> {
                        val parts = text.split('/')
                        author = parts.getOrNull(0)?.substringAfter("作者:")?.trim()
                        category = parts.getOrNull(1)?.substringAfter("分类:")?.trim()
                    }
                    text.startsWith("更新:") -> {
                        val parts = text.split('/')
                        updated = parts.getOrNull(0)?.substringAfter("更新:")?.trim()
                        size = parts.getOrNull(1)?.substringAfter("字数:")?.trim()
                        status = parts.getOrNull(2)?.substringAfter("状态:")?.trim()
                    }
                    text.startsWith("Tags:") -> {
                        val tags = p.select("span").text().split(' ', '　')
                            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                        tagSet = tags.mapToLinkedTags()
                    }
                    text.startsWith("简介:") -> desc = text.substringAfter("简介:").trim()
                }
            }
            val cover = block.selectFirst("img[src]")?.attr("src")?.toAbsoluteUrl(domain)
            val state = parseState(status)
            list += Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = cover,
                largeCoverUrl = null,
                authors = author?.let { setOf(it) } ?: emptySet(),
                tags = tagSet,
                description = desc,
                rating = RATING_UNKNOWN,
                contentRating = null,
                state = state,
                source = source,
            )
        }
        return list
    }

    private suspend fun fetchChapters(indexUrl: String): List<MangaChapter> {
        val doc = webClient.httpGet(indexUrl).parseHtmlGBK(indexUrl).ensureAuthorized()
        val chapters = ArrayList<MangaChapter>()
        var currentVolume: String? = null
        var volumeIndex = 0
        val baseUrl = indexUrl.toHttpUrlOrNull()
        for (cell in doc.select("td[class]")) {
            val clazz = cell.className().lowercase()
            if (clazz.contains("vcss")) {
                currentVolume = cell.text().trim()
                volumeIndex = parseVolumeNumber(currentVolume)
                continue
            }
            if (!clazz.contains("ccss")) continue
            val link = cell.selectFirst("a[href]") ?: continue
            
            // Get the href attribute
            val rawHref = link.attr("href")
            
            // Convert to absolute URL using the index page as base
            val absoluteUrl = when {
                baseUrl != null -> baseUrl.resolve(rawHref)?.toString().orEmpty()
                else -> rawHref.toAbsoluteUrl(domain)
            }
            
            // Convert back to relative URL for storage
            val href = absoluteUrl.toRelativeUrl(domain)
            
            val title = link.text().trim().ifEmpty { "Chapter ${chapters.size + 1}" }
            chapters += MangaChapter(
                id = generateUid(href),
                title = title,
                number = extractChapterNumber(title),
                volume = volumeIndex,
                url = href,
                scanlator = null,
                uploadDate = 0,
                branch = currentVolume,
                source = source,
            )
        }
        return chapters
    }

    private fun buildCatalogUrl(filter: MangaListFilter, page: Int): String {
        // 仅取第一个标签，互斥模式
        val firstTag = filter.tags.firstOrNull()

        // 排行独立使用
        firstTag?.key?.takeIf { it.startsWith("rank:") }?.removePrefix("rank:")?.takeIf { it.isNotBlank() }?.let { sort ->
            return buildString {
                append("https://").append(domain)
                    .append("/modules/article/toplist.php?sort=").append(sort)
                if (page > 1) append("&page=").append(page)
            }
        }
        // 标签单独使用
        firstTag?.key?.takeIf { it.startsWith("tag:") }?.removePrefix("tag:")?.takeIf { it.isNotBlank() }?.let { tag ->
            return buildString {
                append("https://").append(domain)
                    .append("/modules/article/tags.php?t=").append(tag)
                if (page > 1) append("&page=").append(page)
            }
        }

        // 首字母 / 文库分类只取一个标签（如果首字母 + 分类同时选，优先首字母）
        val initialParam = firstTag?.key?.takeIf { it.startsWith("initial:") }?.removePrefix("initial:")
        val classParam = firstTag?.key?.takeIf { it.startsWith("class:") }?.removePrefix("class:")

        val url = StringBuilder()
            .append("https://").append(domain)
            .append("/modules/article/articlelist.php")
        val params = ArrayList<String>(3)
        initialParam?.takeIf { it.isNotBlank() }?.let { params += "initial=$it" }
        // 仅当未选择首字母时才使用分类
        if (initialParam.isNullOrBlank()) {
            classParam?.takeIf { it.isNotBlank() }?.let { params += "class=$it" }
        }
        if (page > 1) params += "page=$page"
        if (params.isNotEmpty()) {
            url.append('?').append(params.joinToString("&"))
        }
        return url.toString()
    }

    private fun buildSearchUrl(query: String, page: Int): String {
        return buildString {
            append("https://").append(domain)
                .append("/modules/article/search.php?searchtype=articlename&searchkey=")
                .append(query.urlEncoded())
            if (page > 1) {
                append("&page=").append(page)
            }
        }
    }

    private fun Document.ensureAuthorized(): Document {
        if (selectFirst("form[name=frmlogin]") != null) {
            throw AuthRequiredException(source)
        }
        return this
    }

    private fun Response.parseHtmlGBK(url: String): Document {
        val stream = body?.byteStream() ?: throw AuthRequiredException(source)
        return stream.use { Jsoup.parse(it, "GBK", url) }
    }

    private fun Element.extractValue(prefix: String): String? {
        return select("td").firstOrNull { it.text().contains(prefix) }
            ?.text()
            ?.substringAfter(prefix)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun Element.extractTags(prefix: String): List<String> {
        val node = selectFirst("*:containsOwn($prefix)") ?: return emptyList()
        val text = node.text().substringAfter(prefix, "")
        if (text.isEmpty()) return emptyList()
        return text.split(' ', '　').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    }

    private fun Element.extractSectionText(prefix: String): String? {
        val node = selectFirst("*:containsOwn($prefix)") ?: return null
        val parent = node.parent()
        val builder = StringBuilder()
        var reachedTags = false
        parent?.children()?.forEach { child ->
            if (child == node) return@forEach
            val text = child.text()
            if (text.contains("作品Tags") || text.contains("作品热度")) {
                reachedTags = true
            }
            if (!reachedTags) {
                builder.append(child.outerHtml())
            }
        }
        return builder.toString()
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun List<String>.mapToLinkedTags(): Set<MangaTag> = mapTo(linkedSetOf()) { name ->
        MangaTag(name, name, source)
    }

    private fun parseVolumeNumber(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val digits = Regex("""\d+""").find(text)?.value ?: return 0
        return digits.toIntOrNull() ?: 0
    }

    private fun extractChapterNumber(title: String): Float {
        return Regex("""\d+(\.\d+)?""").find(title)?.value?.toFloatOrNull() ?: 0f
    }

    private fun buildChapterIndexUrl(manga: Manga): String {
        val id = Regex("""\d+""").find(manga.url)?.value ?: return manga.url
        val num = id.toIntOrNull() ?: return manga.url
        val prefix = num / 1000
        return "https://$domain/novel/$prefix/$id/index.htm"
    }

    private fun MangaListFilter.valueFor(prefix: String): String? {
        return tags.firstOrNull { it.key.startsWith("$prefix:") }
            ?.key
            ?.substringAfter(':')
    }

	private fun createErrorPage(message: String): MangaPage {
		val html = buildErrorHtml(message)
		return MangaPage(
			id = generateUid(message),
			url = html.toDataUrl(),
			preview = null,
			source = source,
		)
	}

    private fun buildErrorHtml(message: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8"/>
        <style>body{font-family:"Noto Serif SC","PingFang SC",sans-serif;padding:16px;line-height:1.8;background:#f8f5f1;color:#222;}
        </style></head><body><h1>提示</h1><p>$message</p></body></html>
    """.trimIndent()

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(StandardCharsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun Cookie.isLoginCookie(): Boolean {
        val name = name.lowercase()
        return name.contains("jieqi") || name.contains("phpdisk")
    }

    private fun parseState(raw: String?): MangaState? = when {
        raw.isNullOrBlank() -> null
        raw.contains("完结") -> MangaState.FINISHED
        raw.contains("连载") -> MangaState.ONGOING
        else -> null
    }

    private companion object {
        private val LETTERS = ('A'..'Z').map { it.toString() } + listOf("0-9")
        private val CATEGORIES = listOf(
            "角川文库" to "1",
            "电击文库" to "2",
            "一迅社文库" to "3",
            "MF文库J" to "4",
            "Fami通文库" to "5",
            "GA文库" to "6",
            "HJ文库" to "7",
            "一迅社" to "8",
        )
        private val DAILY_TAGS = listOf(
            "校园" to "%D0%A3%D4%BA",
            "青春" to "%C7%E0%B4%BA",
            "恋爱" to "%C1%B5%B0%AE",
            "治愈" to "%D6%CE%D3%E0",
            "群像" to "%C8%BA%CF%F1",
            "竞技" to "%BE%BA%BC%B6",
            "音乐" to "%D2%F4%C0%D6",
            "美食" to "%C3%C0%CA%B3",
            "旅行" to "%C2%C3%D0%D0",
            "欢乐向" to "%BB%B6%C0%D6%CF%F2",
            "经营" to "%BE%AD%D3%AA",
            "职场" to "%D6%B0%B3%A1",
            "斗智" to "%B6%B7%D6%C7",
            "脑洞" to "%C4%D4%B6%B4",
            "宅文化" to "%D5%AC%CE%C4%BB%AF",
        )
        private val FANTASY_TAGS = listOf(
            "穿越" to "%B4%A9%D4%BD",
            "奇幻" to "%C6%E6%BB%C3",
            "魔法" to "%C4%A7%B7%A8",
            "异能" to "%D2%EC%C4%DC",
            "战斗" to "%D5%BD%B6%B7",
            "科幻" to "%BF%C6%BB%C3",
            "机战" to "%BB%FA%D5%BD",
            "战争" to "%D5%BD%D5%F9",
            "冒险" to "%C3%B2%CF%D5",
            "龙傲天" to "%C1%FA%B0%C2%CC%EC",
        )
        private val DARK_TAGS = listOf(
            "悬疑" to "%D0%F5%D2%F7",
            "犯罪" to "%B7%B8%D7%EF",
            "复仇" to "%B8%B4%B3%F0",
            "黑暗" to "%BA%DA%B0%B5",
            "猎奇" to "%C1%D8%C6%E6",
            "惊悚" to "%BE%B5%CB%FE",
            "间谍" to "%BC%E4%B5%DC",
            "末日" to "%C4%A9%C8%D5",
            "游戏" to "%D3%CE%CF%B7",
            "大逃杀" to "%B4%F3%CC%D3%C9%B1",
        )
        private val CHARACTER_TAGS = listOf(
            "青梅竹马" to "%C7%E0%C3%B7%D6%A1%C2%DE",
            "妹妹" to "%C3%C3%C3%C3",
            "女儿" to "%C5%AE%B6%F9",
            "JK" to "JK",
            "JC" to "JC",
            "大小姐" to "%B4%F3%D0%A1%BD%E3",
            "性转" to "%D0%D4%D7%AA",
            "伪娘" to "%CE%E2%C4%EF",
            "人外" to "%C8%CB%CD%E2",
        )
        private val SPECIAL_TAGS = listOf(
            "后宫" to "%BA%F3%B9%AC",
            "百合" to "%B0%D9%BA%CF",
            "耽美" to "%B5%CF%C3%C0",
            "NTR" to "NTR",
            "女性视角" to "%C5%AE%D0%D4%CA%D3%BD%C7",
        )
        private val RANKINGS = listOf(
            "总排行榜" to "allvisit",
            "总推荐榜" to "allvote",
            "月排行榜" to "monthvisit",
            "月推荐榜" to "monthvote",
            "周排行榜" to "weekvisit",
            "周推荐榜" to "weekvote",
            "日排行榜" to "dayvisit",
            "日推荐榜" to "dayvote",
            "最新入库" to "postdate",
            "最近更新" to "lastupdate",
            "总收藏榜" to "goodnum",
            "字数排行" to "size",
        )

        private fun buildTagGroups(source: MangaSource): List<MangaTagGroup> {
            fun toGroup(title: String, pairs: List<Pair<String, String>>): MangaTagGroup =
                MangaTagGroup(
                    title = title,
                    tags = pairs.map { (name, value) -> MangaTag(name, "tag:$value", source) }.toSet(),
                )
            return listOf(
                toGroup("日常系属性", DAILY_TAGS),
                toGroup("幻想系属性", FANTASY_TAGS),
                toGroup("黑深残属性", DARK_TAGS),
                toGroup("人物属性", CHARACTER_TAGS),
                toGroup("特殊属性", SPECIAL_TAGS),
            )
        }
    }
}
