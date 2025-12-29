package org.skepsun.kototoro.parsers.site.zh

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import kotlinx.coroutines.*
import java.util.EnumSet
import java.util.Base64
import java.io.File

/**
 * 哔哩轻小说（bilinovel.com）
 * 列表 URL 模板：
 * /wenku/{order}_{tagid}_{isfull}_{anime}_{rgroupid}_{sortid}_{typeid}_{words}_{page}_{update}.html
 * 其中 tagid 支持多选（用 - 连接，最多 4 个）。
 */
@MangaSourceParser("BILINOVEL", "哔哩轻小说", "zh", type = ContentType.NOVEL)
internal class Bilinovel(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BILINOVEL, pageSize = 20),
    Interceptor,
    MangaParserAuthProvider,
    MangaParserCredentialsAuthProvider {


    companion object {
        // 简易 LRU 缓存：减少同一图片的重复下载与编码
        private const val IMAGE_CACHE_MAX = 50
        private val imageCache = object : LinkedHashMap<String, String>(IMAGE_CACHE_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > IMAGE_CACHE_MAX
            }
        }
    }

    override val configKeyDomain = ConfigKey.Domain(
        "www.bilinovel.com",
        "www.linovelib.com",
        "www.bilinovel.live",
        "linovelib.cc",
        "acg02.com",
        "bilinovel.live",
    )

    // 预设 UA：列表页使用移动端，详情/章节使用桌面端 Chrome 131
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.203 Mobile Safari/537.36"
    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    
    // 不暴露 userAgentKey 给用户配置，内部默认使用移动端 UA
    override val userAgentKey = ConfigKey.UserAgent(mobileUserAgent)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val groups = buildFilterGroups()
        val allTags = groups.flatMap { it.tags }.toSet()
        return MangaListFilterOptions(
            availableTags = allTags,
            tagGroups = groups,
        )
    }

    private fun buildFilterGroups(): List<MangaTagGroup> {
        val groups = mutableListOf<MangaTagGroup>()

        // 排序方式
        val orderTags = linkedSetOf(
            MangaTag("最新更新", "order:lastupdate", source),
            MangaTag("周点击榜", "order:weekvisit", source),
            MangaTag("总推荐榜", "order:goodnum", source),
            MangaTag("新书入库", "order:postdate", source),
        )
        groups += MangaTagGroup("排序", orderTags)

        // 文库地区
        val rgroupTags = linkedSetOf(
            MangaTag("不限", "rgroupid:0", source),
            MangaTag("日本轻小说", "rgroupid:1", source),
            MangaTag("华文轻小说", "rgroupid:2", source),
            MangaTag("Web轻小说", "rgroupid:3", source),
            MangaTag("轻改漫画", "rgroupid:4", source),
            MangaTag("韩国轻小说", "rgroupid:5", source),
        )
        groups += MangaTagGroup("地区", rgroupTags)

        // 作品主题（多选，最多 4 个）
        val themeOptions = listOf(
            "恋爱" to "64", "后宫" to "48", "校园" to "63", "百合" to "27", "转生" to "26",
            "异世界" to "47", "奇幻" to "15", "冒险" to "61", "欢乐向" to "222", "女性视角" to "231",
            "龙傲天" to "219", "魔法" to "96", "青春" to "67", "性转" to "31", "病娇" to "198",
            "妹妹" to "217", "青梅竹马" to "225", "战斗" to "18", "NTR" to "256", "人外" to "223",
            "大小姐" to "227", "黑暗" to "189", "悬疑" to "68", "科幻" to "56", "伪娘" to "201",
            "战争" to "55", "萝莉" to "185", "复仇" to "229", "斗智" to "199", "异能" to "131",
            "猎奇" to "241", "轻文学" to "191", "职场" to "60", "经营" to "226", "JK" to "246",
            "机战" to "135", "女儿" to "261", "末日" to "221", "犯罪" to "220", "旅行" to "239",
            "惊悚" to "124", "治愈" to "98", "推理" to "97", "日本文学" to "205", "游戏" to "248",
            "耽美" to "228", "美食" to "211", "群像" to "245", "大逃杀" to "249", "音乐" to "233",
            "格斗" to "132", "热血" to "28", "温馨" to "180", "脑洞" to "224", "恶役" to "328",
            "JC" to "304", "间谍" to "254", "竞技" to "146", "宅文化" to "263", "同人" to "333",
        )
        val themeTags = themeOptions.mapTo(linkedSetOf()) { (name, id) ->
            MangaTag(name, "tagid:$id", source)
        }
        groups += MangaTagGroup("主题", themeTags)

        // 是否动画
        val animeTags = linkedSetOf(
            MangaTag("不限", "anime:0", source),
            MangaTag("已动画化", "anime:1", source),
            MangaTag("未动画化", "anime:2", source),
        )
        groups += MangaTagGroup("动画", animeTags)

        // 写作状态
        val fullTags = linkedSetOf(
            MangaTag("不限", "isfull:0", source),
            MangaTag("新书上传", "isfull:1", source),
            MangaTag("情节展开", "isfull:2", source),
            MangaTag("精彩纷呈", "isfull:3", source),
            MangaTag("接近尾声", "isfull:4", source),
            MangaTag("已经完本", "isfull:5", source),
        )
        groups += MangaTagGroup("状态", fullTags)

        // 字数
        val wordTags = linkedSetOf(
            MangaTag("不限", "words:0", source),
            MangaTag("30万以下", "words:1", source),
            MangaTag("30-50万", "words:2", source),
            MangaTag("50-100万", "words:3", source),
            MangaTag("100-200万", "words:4", source),
            MangaTag("200万以上", "words:5", source),
        )
        groups += MangaTagGroup("字数", wordTags)

        return groups
    }

    // 列表页面使用移动端 UA（返回正确的 HTML 结构）
    override fun getRequestHeaders() = Headers.Builder()
        .add("User-Agent", mobileUserAgent)
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .build()

    // 详情/章节页面使用桌面端 Chrome 131 UA（避免截断和 Chrome 浏览器提示）
    private fun getDesktopHeaders() = Headers.Builder()
        .add("User-Agent", desktopUserAgent)
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        
        // Log image requests
        val isImage = url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") || 
                      url.contains("/files/article/image/") || url.contains("readpai.com")
        if (isImage) {
            // println("Bilinovel Image: $url")
        }
        
        // Use the main domain for Referer to bypass hotlink protection.
        val referer = "https://$domain/"
        val newRequest = request.newBuilder()
            .header("Referer", referer)
            .build()
            
        val response = chain.proceed(newRequest)
        
        if (isImage) {
            // println("Bilinovel Image Response: ${response.code} for $url")
        }
        
        return response
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // 搜索优先
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            // 先访问 wenku 页面以预热 cookies / cf_clearance
            runCatching {
                val warmUrl = "https://$domain/wenku/"
                val warmResp = webClient.httpGet(warmUrl, getRequestHeaders())
                val warmCode = warmResp.code
                val warmPreview = warmResp.peekBody(4096).string()
                println("Bilinovel Search warmup: url=$warmUrl code=$warmCode bodyLen=${warmPreview.length}")
            }

            val url = "https://$domain/search.html?searchkey=${query.urlEncoded()}"
            return runCatching {
                val response = webClient.httpGet(url, getRequestHeaders())
                val code = response.code
                if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                    val snippet = response.peekBody(2048).string().take(200)
                    println("Bilinovel Search: Cloudflare challenge detected for $url, snippet=$snippet")
                    context.requestBrowserAction(this, url)
                    emptyList<Manga>()
                } else {
                    val preview = response.peekBody(65536).string()
                    val doc = org.jsoup.Jsoup.parse(preview, url)
                    val bodyText = doc.body()?.text().orEmpty()
                    val title = doc.title()
                    val result = parseList(doc)
                    println("Bilinovel Search: code=$code query=\"$query\" results=${result.size} title=$title bodyLen=${bodyText.length} previewLen=${preview.length} snippet=${bodyText.take(160)}")
                    if (preview.isEmpty()) {
                        println("Bilinovel Search: empty body, requesting browser action for $url")
                        context.requestBrowserAction(this, url)
                    }
                    if (result.isEmpty() && preview.isNotEmpty()) {
                        try {
                            val dir = File("debug_htmls").apply { mkdirs() }
                            val file = File(dir, "bilinovel_search_${System.currentTimeMillis()}.html")
                            file.writeText(preview)
                            println("Bilinovel Search: saved preview to ${file.absolutePath}")
                        } catch (ex: Exception) {
                            println("Bilinovel Search: failed to save preview: ${ex.message}")
                        }
                    }
                    result
                }
            }.getOrElse {
                println("Bilinovel Search query=\"$query\" failed: ${it.message}")
                emptyList()
            }
        }

        val params = resolveFilterParams(order, filter)
        val url = "https://$domain/wenku/${params.order}_${params.tagIds}_${params.isFull}_${params.anime}_${params.rgroup}_${params.sortid}_${params.typeid}_${params.words}_${page}_${params.update}.html"
        val response = webClient.httpGet(url, getRequestHeaders())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        val doc = response.parseHtml()
        return parseList(doc)
    }

    private data class FilterParams(
        val order: String = "lastupdate",
        val tagIds: String = "0",
        val isFull: String = "0",
        val anime: String = "0",
        val rgroup: String = "0",
        val sortid: String = "0",
        val typeid: String = "0",
        val words: String = "0",
        val update: String = "0",
    )

    private fun resolveFilterParams(order: SortOrder, filter: MangaListFilter): FilterParams {
        val tagMap = filter.tags.associate { it.key.substringBefore(":") to it.key.substringAfter(":") }
        val orderParam = tagMap["order"] ?: when (order) {
            SortOrder.UPDATED -> "lastupdate"
            SortOrder.POPULARITY -> "weekvisit"
            SortOrder.RATING -> "goodnum"
            SortOrder.NEWEST -> "postdate"
            else -> "lastupdate"
        }
        val rawTagIds = filter.tags.filter { it.key.startsWith("tagid:") }.map { it.key.substringAfter(":") }
        val tagIds = if (rawTagIds.isEmpty()) {
            tagMap["tagid"] ?: "0"
        } else {
            rawTagIds.joinToString("-").ifBlank { "0" }
        }
        return FilterParams(
            order = orderParam,
            tagIds = tagIds,
            isFull = tagMap["isfull"] ?: "0",
            anime = tagMap["anime"] ?: "0",
            rgroup = tagMap["rgroupid"] ?: "0",
            sortid = tagMap["sortid"] ?: "0",
            typeid = tagMap["typeid"] ?: "0",
            words = tagMap["words"] ?: "0",
            update = tagMap["update"] ?: "0",
        )
    }

    private fun parseList(doc: Document): List<Manga> {
        val list = mutableListOf<Manga>()
        // 搜索/列表页面在不同模板下 href 可能放在 data-href，或 href 为 javascript: 占位
        for (item in doc.select("ol.book-ol li.book-li a.book-layout, li.book-li a.book-layout, .book-li a.book-layout")) {
            val rawHref = item.attrOrNull("href").orEmpty()
            val hrefValue = if (rawHref.startsWith("javascript:", ignoreCase = true) || rawHref.isBlank()) {
                item.attrOrNull("data-href").orEmpty()
            } else rawHref
            if (hrefValue.isBlank()) continue
            val absolute = hrefValue.toAbsoluteUrlOrNull(domain) ?: item.attrAsAbsoluteUrlOrNull("href")
            val href = absolute?.toRelativePath() ?: hrefValue.toRelativePath()
            val title = item.selectFirst("h4.book-title")?.text()?.trim().orEmpty()
            if (title.isEmpty() || !href.startsWith("/novel/")) continue
            val cover = item.selectFirst(".book-img img, .book-cover img")?.let { img ->
                // 优先使用真实封面 data-src，避免拿到站点的缺省 SVG
                val dataSrc = img.attrOrNull("data-src")
                val dataOriginal = img.attr("data-original")
                val src = img.attrOrNull("src")
                
                // data-src 和 src 都是绝对URL，直接使用即可
                dataSrc.orEmpty().ifBlank { dataOriginal }.ifBlank { src }
            }
            val desc = item.selectFirst("p.book-desc")?.text()?.trim().orEmpty()
            val author = item.selectFirst(".book-author")?.text()?.trim()?.removePrefix("作者")?.trim()
            val stateText = item.selectFirst(".tag-small.red")?.text()?.trim().orEmpty()
            val state = when {
                stateText.contains("完", ignoreCase = true) -> MangaState.FINISHED
                stateText.contains("连载") -> MangaState.ONGOING
                else -> null
            }
            val tags = item.select(".tag-small-group em").flatMap { em ->
                em.text().split(' ', '　', ',', '，').mapNotNull { t -> t.trim().takeIf { it.isNotEmpty() } }
            }.mapTo(linkedSetOf<MangaTag>()) { t -> MangaTag(t, t, source) }

            list += Manga(
                id = generateUid(href),
                url = href,
                publicUrl = item.absUrl(href),
                title = title,
                coverUrl = cover.orEmpty(),
                altTitle = null,
                rating = RATING_UNKNOWN,
                author = author,
                isNsfw = false,
                tags = tags,
                state = state,
                source = source,
            )
        }
        return list
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.url.let { if (it.startsWith("http")) it else "https://$domain$it" }
        // 详情页使用桌面端 UA
        val response = webClient.httpGet(url, getDesktopHeaders())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            context.requestBrowserAction(this, url)
        }
        val doc = response.parseHtml()
        val title = doc.selectFirst("h1.book-title")?.text()?.trim().orEmpty().ifEmpty { manga.title }
        val author = doc.selectFirst(".book-rand-a .authorname a")?.text()?.trim()
        val cover = doc.selectFirst(".book-img img, .module-book-cover img")?.let { img ->
            // 优先使用真实封面 data-src，避免拿到站点的缺省 SVG
            val raw = img.attrOrNull("data-src").orEmpty().ifBlank { img.attr("data-original") }
            val fallback = img.attrOrNull("src")
            val chosen = raw.ifBlank { fallback }
            chosen?.let { doc.absUrl(it).ifBlank { img.attrAsAbsoluteUrl("src") } }
        }
        val descHtml = doc.selectFirst("#bookSummary content")?.html().orEmpty()
        val desc = descHtml.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "").trim()
        val tagSet = doc.select(".tag-small-group em a").map { it.text().trim() }
            .mapTo(linkedSetOf<MangaTag>()) { name -> MangaTag(name, name, source) }
        val stateText = doc.selectFirst(".book-meta.book-layout-inline")?.text().orEmpty()
        val state = when {
            stateText.contains("完", ignoreCase = true) -> MangaState.FINISHED
            stateText.contains("连载") -> MangaState.ONGOING
            else -> null
        }

        return manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            description = desc.ifEmpty { manga.description },
            authors = author?.let { setOf(it) } ?: manga.authors,
            tags = if (tagSet.isNotEmpty()) tagSet else manga.tags,
            state = state ?: manga.state,
            chapters = fetchChapters(manga),
        )
    }

    private suspend fun fetchChapters(manga: Manga): List<MangaChapter> {
        val catalogUrl = manga.url.replace(Regex("\\.html$"), "").removeSuffix("/") + "/catalog"
        val url = catalogUrl.let { if (it.startsWith("http")) it else "https://$domain$it" }
        val doc = webClient.httpGet(url, getDesktopHeaders()).parseHtml()
        val chapters = mutableListOf<MangaChapter>()
        
        // 按顺序遍历所有 li 元素，识别卷标题和章节
        // 卷标题结构: <li class="chapter-bar chapter-li"><h3>第一卷</h3></li>
        // 章节结构: <li class="chapter-li jsChapter"><a class="chapter-li-a" href="...">章节名</a></li>
        val allItems = doc.select("ol.chapter-ol li, ul.chapter-ol li, .catalog-volume li, .volume-list li")
        var volumeIndex = 0
        var currentVolumeName: String? = null  // 记录当前卷标题文本
        
        allItems.forEach { li ->
            when {
                // 卷标题: li.chapter-bar 包含 h3
                li.hasClass("chapter-bar") -> {
                    val volumeName = li.selectFirst("h3")?.text()?.trim()
                    if (!volumeName.isNullOrEmpty()) {
                        currentVolumeName = volumeName  // 保存原始卷标题
                        volumeIndex++
                    }
                }
                // 章节: li.jsChapter 包含章节链接
                li.hasClass("jsChapter") -> {
                    val a = li.selectFirst("a.chapter-li-a, a")
                    if (a != null) {
                        val hrefValue = a.attr("href")
                        if (hrefValue.startsWith("javascript:", ignoreCase = true)) return@forEach
                        val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                        val title = a.selectFirst(".chapter-index")?.text()?.trim() ?: a.text().trim()
                        if (title.isEmpty()) return@forEach
                        chapters += MangaChapter(
                            id = generateUid(href),
                            title = title,
                            number = chapters.size + 1f,
                            volume = volumeIndex,
                            url = href,
                            scanlator = currentVolumeName,  // 使用 scanlator 存储卷名，UI 用于显示分卷标题
                            uploadDate = 0,
                            branch = null,  // branch=null 实现扁平列表，volume 用于分卷标题
                            source = source,
                        )
                    }
                }
            }
        }
        
        // 备选方案：如果上述方法没有解析到章节，尝试其他选择器
        if (chapters.isEmpty()) {
            volumeIndex = 0
            doc.select(".volume-item, .catalog-volume, .volume-title, .volume-bar").forEach { volume ->
                val volumeChapters = volume.select("li.chapter-li.jsChapter a.chapter-li-a, .chapter-item a, a.chapter-link")
                volumeChapters.forEach { a ->
                    val hrefValue = a.attr("href")
                    if (hrefValue.startsWith("javascript:", ignoreCase = true)) return@forEach
                    val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                    val title = a.selectFirst(".chapter-index, .chapter-name")?.text()?.trim() ?: a.text().trim()
                    chapters += MangaChapter(
                        id = generateUid(href),
                        title = title,
                        number = chapters.size + 1f,
                        volume = volumeIndex,
                        url = href,
                        scanlator = null,
                        uploadDate = 0,
                        branch = null,
                        source = source,
                    )
                }
                volumeIndex++
            }
        }
        
        // 最后备选：从所有列表链接中提取
        if (chapters.isEmpty()) {
            doc.select("#volumes-list > *, .catalog-content > *").forEach { element ->
                if (element.tagName() == "h3" || element.hasClass("volume-title")) {
                    volumeIndex++
                } else if (element.tagName() == "ul" || element.hasClass("chapter-list")) {
                    element.select("a").forEach { a ->
                        val hrefValue = a.attr("href")
                        if (hrefValue.startsWith("javascript:", ignoreCase = true)) return@forEach
                        val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                        val title = a.text().trim()
                        chapters += MangaChapter(
                            id = generateUid(href),
                            title = title,
                            number = chapters.size + 1f,
                            volume = maxOf(0, volumeIndex - 1),
                            url = href,
                            scanlator = null,
                            uploadDate = 0,
                            branch = null,
                            source = source,
                        )
                    }
                }
            }
        }
        
        // 如果依然为空，尝试从所有正文链接中提取（限制在 #volumes 容器内）
        if (chapters.isEmpty()) {
            val container = doc.getElementById("volumes") ?: doc.selectFirst(".catalog-content, .volume-list") ?: doc.body()
            container.select("a[href*='/novel/']").filter { 
                val href = it.attr("href")
                // 严格匹配章节路径：类似 /novel/123/456.html
                // 必须包含两级 ID，且不能包含 /catalog
                // 这样可以排除类似 /novel/1.html 的推荐榜单链接
                href.contains(Regex("/\\d+/\\d+\\.html$"))
            }.forEach { a ->
                val hrefValue = a.attr("href")
                if (hrefValue.startsWith("javascript:", ignoreCase = true)) return@forEach
                val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach
                chapters += MangaChapter(
                    id = generateUid(href),
                    title = a.text().trim(),
                    number = chapters.size + 1f,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }
        }

        return chapters
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val content = getChapterContent(chapter) ?: return listOf(createErrorPage("内容为空"))
        val dataUrl = content.html.toDataUrl(context)
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
        val initialUrl = chapter.url.let { if (it.startsWith("http")) it else "https://$domain$it" }
        val allHtml = StringBuilder()
        val allImages = mutableListOf<NovelChapterContent.NovelImage>()
        val seenPages = mutableSetOf<String>()
        var currentUrl: String? = initialUrl
        var pageCount = 0

        // 章节内容使用桌面端 UA
        val contentHeaders = getDesktopHeaders()

        while (currentUrl != null && pageCount < 30) { // Safety limit: 30 pages per chapter
            if (seenPages.contains(currentUrl)) break
            seenPages.add(currentUrl)

            val doc = runCatching {
                webClient.httpGet(currentUrl!!, contentHeaders).parseHtml()
            }.getOrNull() ?: break

            val content = doc.selectFirst("#TextContent") ?: doc.selectFirst("#acontent") ?: doc.selectFirst("#article") ?: doc.selectFirst(".read-content")
                ?: doc.selectFirst(".content") ?: break

            // 移除广告/脚本/多余空行以及防爬虫提示
            content.select("script, style, iframe, ins, .co, .google-auto-placed").remove()
            // 过滤“如需繼續閱讀請使用 [Chrome瀏覽器] 訪問”
            content.select("p").filter { p ->
                val text = p.text()
                text.contains("Chrome瀏覽器", ignoreCase = true) || 
                text.contains("Chrome浏览器", ignoreCase = true) ||
                text.contains("繼續閱讀請使用", ignoreCase = true)
            }.forEach { it.remove() }

            // 移除纯空行的段落 (但保留图片)
            content.select("p").filter { p ->
                p.select("img").isEmpty() && p.text().trim().replace(" ", "").isEmpty()
            }.forEach { it.remove() }

            // 移除段落前后的 br
            content.select("p + br").remove()
            content.select("br + p").remove()

            // 处理图片
            content.select("img").forEach { img ->
                val src = (img.attr("data-src").ifBlank { img.attr("src") }).trim()
                if (src.isBlank() || src.contains("sloading.svg")) {
                    img.remove()
                    return@forEach
                }

                val absoluteUrl = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "https://$domain$src"
                    else -> "https://$domain/$src"
                }

                img.attr("src", absoluteUrl)
                img.removeAttr("data-src")
                img.attr("referrerpolicy", "no-referrer")
                img.attr("loading", "lazy")

                allImages.add(
                    NovelChapterContent.NovelImage(
                        url = absoluteUrl,
                        headers = mapOf(
                            "Referer" to "https://$domain/",
                            "Origin" to "https://$domain",
                            "Accept-Encoding" to "gzip",
                        )
                    )
                )
            }

            allHtml.append(content.html())

            // 查找下一页链接
            val nextLink = doc.select(".Readpage a, #footlink a, .page-ctrl a").find {
                val text = it.text()
                text.contains("下一页") || text.contains("下一頁")
            }
            
            val nextHref = nextLink?.attr("href")
            if (nextHref != null && (nextHref.contains("_") || nextHref.contains("-"))) {
                val nextAbsolute = nextLink.attrAsAbsoluteUrlOrNull("href")
                // 确保下一页仍在同一个章节路径下（避免跳到下一章）
                if (nextAbsolute != null && isSameChapterUrl(initialUrl, nextAbsolute)) {
                    currentUrl = nextAbsolute
                    pageCount++
                } else {
                    currentUrl = null
                }
            } else {
                currentUrl = null
            }
        }

        if (allHtml.isEmpty()) return null

        val bodyHtml = allHtml.toString().replace("\n", "").replace("\r", "")
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<style>body{font-family:\"Noto Serif SC\",\"PingFang SC\",sans-serif;padding:16px;padding-bottom:50px;margin:0;line-height:1.9;font-size:1.05rem;}img{max-width:100%;height:auto;}p{margin:0 0 1rem;}p:last-child{margin-bottom:0;}h1{font-size:1.3rem;margin-bottom:1rem;}</style>")
            append("</head><body>")
            if (!chapter.title.isNullOrBlank()) {
                append("<h1>").append(chapter.title).append("</h1>")
            }
            append(bodyHtml)
            append("</body></html>")
        }
        println("Bilinovel: final HTML length=${html.length}, pages=$pageCount, images=${allImages.size}")

        return NovelChapterContent(html = html, images = allImages)
    }

    private fun isSameChapterUrl(original: String, next: String): Boolean {
        // 简单判断逻辑：移除后缀与分页标记后，基础路径应保持一致
        fun getBase(url: String): String {
            return url.substringBefore("_")
                .substringBefore(".html")
                .substringBefore("-")
                .removeSuffix("/")
        }
        return getBase(original) == getBase(next)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        // 不暴露 userAgentKey 给用户设置，内部已预设最优 UA 策略
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override val authUrl: String = "https://$domain/login.php"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any { it.name == "jieqiUserInfo" }
    }

    override suspend fun getUsername(): String {
        val cookies = context.cookieJar.getCookies(domain)
        val userInfo = cookies.find { it.name == "jieqiUserInfo" }?.value ?: throw AuthRequiredException(source)
        return try {
            java.net.URLDecoder.decode(userInfo, "UTF-8").substringAfter("jieqiUserName=").substringBefore("&")
        } catch (e: Exception) {
            "User"
        }
    }

    override suspend fun login(username: String, password: String): Boolean {
        val url = "https://$domain/login.php?do=submit"
        
        val body = mapOf(
            "username" to username,
            "password" to password,
            "usecookie" to "315360000",
            "action" to "login"
        )
        
        val response = try {
            webClient.httpPost(url.toHttpUrl(), body, getRequestHeaders())
        } catch (e: Exception) {
            return false
        }
        
        return isAuthorized()
    }

    /**
     * 创建错误页面
     */
    private fun createErrorPage(message: String): MangaPage {
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8"/>
            <style>body{font-family:sans-serif;padding:16px;}</style>
            </head><body><h1>错误</h1><p>$message</p></body></html>
        """.trimIndent()

        return MangaPage(
            id = generateUid(message),
            url = html.toDataUrl(context),
            preview = null,
            source = source,
        )
    }

    /**
     * 将HTML转换为Data URL
     */
    private fun String.toDataUrl(context: MangaLoaderContext): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    private fun String.toRelativePath(): String {
        // Remove common domain prefixes for Bilinovel/Linovelib and mirrors
        return this.replace(Regex("^https?://(www\\.)?(bilinovel|linovelib|bilinovel\\.live|acg02)\\.(com|live|cc|tw|net)/?"), "/")
            .let { if (it.startsWith("/")) it else "/$it" }
    }
}
