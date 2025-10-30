package org.koitharu.kotatsu.parsers.site.galleryadults.zh

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.random.Random
import org.jsoup.HttpStatusException

@MangaSourceParser("WNACG", "紳士漫畫", "zh", type = ContentType.HENTAI)
internal class WnacgParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WNACG, 30) {

    override val configKeyDomain = ConfigKey.Domain("www.wnacg.com")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Cache-Control", "max-age=0")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    // Backoff helpers: exponential backoff with jitter and extra cooldown for 429/503
    private suspend fun httpGetHtmlWithBackoff(url: String, maxRetries: Int = 6): Document {
        var attempt = 0
        var delayMs = 1200L + Random.nextLong(0L, 1000L)
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                return webClient.httpGet(url).parseHtml()
            } catch (e: Exception) {
                lastError = e
                val extraCooldown = if (e is HttpStatusException && (e.statusCode == 429 || e.statusCode == 503)) {
                    Random.nextLong(2000L, 4000L)
                } else 0L
                delay(delayMs + extraCooldown)
                delayMs = delayMs * 2 + Random.nextLong(600L, 1400L)
                attempt++
            }
        }
        throw lastError ?: RuntimeException("Network backoff exhausted for $url")
    }

    private suspend fun httpGetRawWithBackoff(url: String, maxRetries: Int = 6): String {
        var attempt = 0
        var delayMs = 1200L + Random.nextLong(0L, 1000L)
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                return webClient.httpGet(url).parseRaw()
            } catch (e: Exception) {
                lastError = e
                val extraCooldown = if (e is HttpStatusException && (e.statusCode == 429 || e.statusCode == 503)) {
                    Random.nextLong(2000L, 4000L)
                } else 0L
                delay(delayMs + extraCooldown)
                delayMs = delayMs * 2 + Random.nextLong(600L, 1400L)
                attempt++
            }
        }
        throw lastError ?: RuntimeException("Network backoff exhausted for $url")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    // 分類映射：cateId -> 名稱
    private val cateMap = mapOf(
        5 to "同人誌",
        1 to "漢化", 12 to "日語", 16 to "English", 2 to "CG畫集", 22 to "3D漫畫", 3 to "寫真Cosplay",
        6 to "單行本", 9 to "漢化", 13 to "日語", 17 to "English",
        7 to "雜誌短篇", 10 to "漢化", 14 to "日語", 18 to "English",
        19 to "韓漫", 20 to "漢化", 21 to "生肉",
    )
    // 分組映射：cateId -> 分組（同人誌/單行本/雜誌短篇/韓漫）
    private val cateGroup = mapOf(
        // 同人誌
        5 to "同人誌", 1 to "同人誌", 12 to "同人誌", 16 to "同人誌", 2 to "同人誌", 22 to "同人誌", 3 to "同人誌",
        // 單行本
        6 to "單行本", 9 to "單行本", 13 to "單行本", 17 to "單行本",
        // 雜誌短篇
        7 to "雜誌短篇", 10 to "雜誌短篇", 14 to "雜誌短篇", 18 to "雜誌短篇",
        // 韓漫
        19 to "韓漫", 20 to "韓漫", 21 to "韓漫",
    )

    // 提供过滤器标签（与站点现有类别一致）
    private val availableCateTags: Set<MangaTag> = run {
        val ids = listOf(5, 1, 12, 16, 2, 22, 3, 6, 9, 13, 17, 7, 10, 14, 18, 19, 20, 21)
        ids.map { id ->
            val group = cateGroup[id]
            val sub = cateMap[id]
            val title = if (sub == null || sub == group) (group ?: sub ?: "分類") else "$group/$sub"
            MangaTag(title = title, key = "cate-$id", source = source)
        }.toSet()
    }

    private fun buildTag(title: String): MangaTag = MangaTag(
        key = title,
        title = title,
        source = source,
    )
    private fun composeTags(cateId: Int?, category: String?, groupTitle: String?): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>(2)
        val group = when {
            cateId != null -> cateGroup[cateId]
            !groupTitle.isNullOrBlank() -> groupTitle
            else -> null
        }
        val sub = category ?: (cateId?.let { cateMap[it] })
        if (!group.isNullOrBlank()) tags.add(buildTag(group))
        if (!sub.isNullOrBlank()) {
            // 避免重复：当子类与分组相同，不重复添加
            if (sub != group) tags.add(buildTag(sub)) else if (tags.isEmpty()) tags.add(buildTag(sub))
        }
        return tags
    }
    private fun extractCateId(li: Element): Int? {
        val pic = li.selectFirst("div.pic_box") ?: return null
        for (cls in pic.classNames()) {
            val m = Regex("cate-(\\d+)").find(cls)
            if (m != null) return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isOriginalLocaleSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableCateTags,
        availableLocales = setOf(Locale.CHINESE),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // 优先使用 cateId 过滤构建分类页 URL
        val cateId = filter.tags.firstOrNull { it.key.startsWith("cate-") }?.key?.substringAfter("cate-")?.toIntOrNull()
        val url = when {
            cateId != null -> {
                if (page <= 1) {
                    "https://$domain/albums-index-cate-$cateId.html"
                } else {
                    "https://$domain/albums-index-page-$page-cate-$cateId.html"
                }
            }
            !filter.query.isNullOrEmpty() -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/search/?q=")
                    append(filter.query!!.urlEncoded())
                    append("&syn=yes&s=create_time_DESC&p=")
                    append(page)
                }
            }
            else -> {
                if (page <= 1) {
                    "https://$domain/albums.html"
                } else {
                    "https://$domain/albums-index-page-$page.html"
                }
            }
        }
        val doc = httpGetHtmlWithBackoff(url)
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        // 优先解析首页分区：div.title_sort + div.bodywrap
        val titleBlocks = doc.select("div.title_sort")
        val contentBlocks = doc.select("div.bodywrap")
        if (titleBlocks.isNotEmpty() && contentBlocks.isNotEmpty()) {
            val n = minOf(titleBlocks.size, contentBlocks.size)
            val out = ArrayList<Manga>(n * 20)
            for (i in 0 until n) {
                val groupTitle = titleBlocks[i].selectFirst("div.title_h2")?.text()?.trim()
                val container = contentBlocks[i]
                container.select("ul.cc > li").forEach { li ->
                    val a = li.selectFirst("div.pic_box > a")
                        ?: li.selectFirst("a[href*=/photos-index-aid-], a[href*=/photos-index-page-]")
                        ?: return@forEach
                    val href = a.attrAsRelativeUrl("href")
                    val id = extractIdFromUrl(href) ?: return@forEach
                    val detailPath = "/photos-index-page-1-aid-$id.html"
                    val imgEl = a.selectFirst("img")
                    val titleEl = li.selectFirst("div.info a, .title a, h1 a, h2 a, h3 a, a")
                    val title = titleEl?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                        ?: titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: li.selectFirst("div.info > div.title, div.title, p.title, h1, h2, h3, h4, h5, h6")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: imgEl?.attr("alt")?.trim()
                        ?: ""
                    val srcset = imgEl?.attr("srcset")?.trim()
                    val srcsetFirst = srcset?.split(',')?.firstOrNull()?.trim()?.substringBefore(' ')
                    val cover = listOf(
                        imgEl?.attr("data-actual"),
                        imgEl?.attr("data-original"),
                        imgEl?.attr("data-src"),
                        imgEl?.attr("data-lazy-src"),
                        imgEl?.attr("data-cfsrc"),
                        imgEl?.attr("data-echo"),
                        srcsetFirst,
                        imgEl?.attr("src"),
                    ).firstOrNull { !it.isNullOrBlank() }

                    val info = li.selectFirst("div.info > div.info_col")?.text()?.trim()
                    val cateFromInfo = info?.let { Regex("分類[:：]\\s*([^\\s/]+)").find(it)?.groupValues?.get(1) }
                    val itemCateId = extractCateId(li)
                    val tags = composeTags(itemCateId, cateFromInfo, groupTitle)

                    out.add(
                        Manga(
                            id = generateUid(detailPath),
                            title = title,
                            altTitles = emptySet(),
                            url = detailPath,
                            publicUrl = detailPath.toAbsoluteUrl(domain),
                            rating = RATING_UNKNOWN,
                            contentRating = ContentRating.ADULT,
                            coverUrl = cover,
                            tags = tags,
                            state = null,
                            authors = emptySet(),
                            source = source,
                        )
                    )
                }
            }
            if (out.isNotEmpty()) return out
        }
        // 回退：通用列表选择器，更宽松匹配避免空结果
        val items = doc.select("div.gallary_wrap ul.cc > li")
            .ifEmpty { doc.select("li.gallary_item") }
        if (items.isEmpty()) {
            val anchors = doc.select("a[href*=/photos-index-aid-], a[href*=/photos-index-page-]")
            return anchors.mapNotNull { a ->
                val href = a.attrAsRelativeUrl("href")
                val id = extractIdFromUrl(href) ?: return@mapNotNull null
                val detailPath = "/photos-index-page-1-aid-$id.html"
                val imgEl = a.selectFirst("img")
                val srcset = imgEl?.attr("srcset")?.trim()
                val srcsetFirst = srcset?.split(',')?.firstOrNull()?.trim()?.substringBefore(' ')
                var cover = listOf(
                    imgEl?.attr("data-actual"),
                    imgEl?.attr("data-original"),
                    imgEl?.attr("data-src"),
                    imgEl?.attr("data-lazy-src"),
                    imgEl?.attr("data-cfsrc"),
                    imgEl?.attr("data-echo"),
                    srcsetFirst,
                    imgEl?.attr("src"),
                ).firstOrNull { !it.isNullOrBlank() }
                if (!cover.isNullOrBlank() && cover.startsWith("//")) {
                    cover = "https:$cover"
                }
                val li = a.parents().firstOrNull { it.tagName() == "li" }
                val titleEl = li?.selectFirst("div.info a, .title a, h1 a, h2 a, h3 a, a") ?: a
                val title = titleEl.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: titleEl.text().trim().takeIf { it.isNotBlank() }
                    ?: li?.selectFirst("div.info > div.title, div.title, p.title, h1, h2, h3, h4, h5, h6")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: imgEl?.attr("alt")?.trim().orEmpty()
                val info = li?.selectFirst("div.info > div.info_col")?.text()?.trim()
                val cateFromInfo = info?.let { Regex("分類[:：]\\s*([^\\s/]+)").find(it)?.groupValues?.get(1) }
                val itemCateId = li?.let { extractCateId(it) }
                val tags = composeTags(itemCateId, cateFromInfo, groupTitle = null)

                Manga(
                    id = generateUid(detailPath),
                    title = title,
                    altTitles = emptySet(),
                    url = detailPath,
                    publicUrl = detailPath.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.ADULT,
                    coverUrl = cover,
                    tags = tags,
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            }
        }
        return items.mapNotNull { li ->
            val a = li.selectFirst("div.pic_box > a")
                ?: li.selectFirst("a[href*=/photos-index-aid-], a[href*=/photos-index-page-]")
                ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val id = extractIdFromUrl(href) ?: return@mapNotNull null
            val detailPath = "/photos-index-page-1-aid-$id.html"
            val imgEl = a.selectFirst("img")
            val title = li.selectFirst("div.info > div.title")?.text()?.trim()
                ?: li.selectFirst("div.title, p.title, h3.title, h4.title, h2.title")?.text()?.trim()
                ?: li.selectFirst("h3, h4, h2, h5, h6")?.text()?.trim()
                ?: li.selectFirst("a[title]")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
                ?: imgEl?.attr("alt")
                ?: ""
            val srcset = imgEl?.attr("srcset")?.trim()
            val srcsetFirst = srcset?.split(',')?.firstOrNull()?.trim()?.substringBefore(' ')
            var cover = listOf(
                imgEl?.attr("data-actual"),
                imgEl?.attr("data-original"),
                imgEl?.attr("data-src"),
                imgEl?.attr("data-lazy-src"),
                imgEl?.attr("data-cfsrc"),
                imgEl?.attr("data-echo"),
                srcsetFirst,
                imgEl?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }
            if (!cover.isNullOrBlank() && cover.startsWith("//")) {
                cover = "https:$cover"
            }

            val info = li.selectFirst("div.info > div.info_col")?.text()?.trim()
            val cateFromInfo = info?.let { Regex("分類[:：]\\s*([^\\s/]+)").find(it)?.groupValues?.get(1) }
            val itemCateId = extractCateId(li)
            val tags = composeTags(itemCateId, cateFromInfo, groupTitle = null)

            Manga(
                id = generateUid(detailPath),
                title = title,
                altTitles = emptySet(),
                url = detailPath,
                publicUrl = detailPath.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover,
                tags = tags,
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = httpGetHtmlWithBackoff(manga.publicUrl)
        val id = extractIdFromUrl(manga.url) ?: extractIdFromUrl(doc.location())
        val title = doc.selectFirst("div.userwrap h2")?.text()?.trim()
            ?: doc.selectFirst("div.userwrap .asTBcell.uwconn h2")?.text()?.trim()
            ?: manga.title
        fun normalizeCover(u: String?): String? {
            if (u.isNullOrBlank()) return u
            val withScheme = if (u.startsWith("//")) "https:$u" else u
            return withScheme.replaceFirst(Regex("^https:/{3,}"), "https://")
        }
        val cover = normalizeCover(doc.selectFirst("div.userwrap .asTBcell.uwthumb img")?.src()) ?: manga.coverUrl
        val tags = doc.select("a.tagshow").mapToSet { a ->
            MangaTag(
                key = a.text().trim(),
                title = a.text().trim(),
                source = source,
            )
        }
        val chapters = if (id != null) listOf(
            MangaChapter(
                id = generateUid("/photos-slist-aid-$id.html"),
                url = "/photos-slist-aid-$id.html",
                title = title ?: manga.title,
                number = 1f,
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        ) else emptyList()
        val description = listOf(
            doc.selectFirst("div.userwrap .asTBcell.uwdesc")?.text()?.trim(),
            doc.selectFirst("#intro")?.text()?.trim(),
            doc.selectFirst("div.uwdesc")?.text()?.trim(),
            doc.selectFirst("div#info")?.text()?.trim(),
        ).firstOrNull { !it.isNullOrBlank() } ?: manga.description ?: title

        return manga.copy(
            title = title ?: manga.title,
            coverUrl = cover,
            tags = tags,
            chapters = chapters,
            description = description,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val original = chapter.url.toAbsoluteUrl(domain)
        
        // Helpers: normalize URL, prefer data-* attrs, and filter placeholders
        fun normalizeUrl(s: String?): String? {
            if (s.isNullOrBlank()) return null
            // 忽略 base64 内联图片（data:），这通常是加载动画或占位图
            if (s.startsWith("data:", ignoreCase = true)) return null
            return when {
                s.startsWith("//") -> "https:$s"
                s.startsWith("/") -> s.toAbsoluteUrl(domain)
                s.startsWith("https://") -> s
                s.startsWith("http://") -> s.replaceFirst("http://", "https://")
                else -> "https://$domain/$s"
            }
        }
        
        fun rawImgAttr(img: org.jsoup.nodes.Element?): String? {
            if (img == null) return null
            val srcset = img.attr("srcset").trim()
            val srcsetFirst = if (srcset.isNotBlank()) srcset.split(',').firstOrNull()?.trim()?.substringBefore(' ') else null
            val candidates = listOf(
                img.attr("data-actual"),
                img.attr("data-original"),
                img.attr("data-original-src"),
                img.attr("data-real-src"),
                img.attr("data-realsrc"),
                img.attr("data-src"),
                img.attr("data-url"),
                img.attr("data-lazy-src"),
                img.attr("data-lazyload"),
                img.attr("data-cfsrc"),
                img.attr("data-echo"),
                srcsetFirst,
                img.attr("src"),
            )
            return candidates.firstOrNull { !it.isNullOrBlank() }
        }
        
        fun isLikelyPlaceholder(u: String): Boolean {
            val l = u.lowercase()
            val name = l.substringAfterLast('/')
            val isGif = name.endsWith(".gif")
            val hasLoadingWord = name.contains("loading") || name.contains("spinner") || name.contains("lazy") || name.contains("placeholder") || name.contains("spacer") || name.contains("blank") || name.contains("ajax-loader")
            val inStatic = l.contains("/static/") || l.contains("/assets/") || l.contains("/images/")
            val isAd = name.contains("ad") || name.contains("banner") || name.contains("logo") || l.contains("/ads/")
            val isThumb = l.contains("/data/t/") || name.contains("thumb")
            return (isGif && (hasLoadingWord || inStatic)) || hasLoadingWord || isAd || isThumb
        }
        
        // 提取 aid，支持 index/slide/slist 三种形式
        val id = Regex("/photos-(?:index-page-\\d+-|slide-|slist-)aid-(\\d+)")
            .find(original)?.groupValues?.getOrNull(1)
        val slistUrl = id?.let { "/photos-slist-aid-$it.html".toAbsoluteUrl(domain) } ?: original
        val raw = httpGetRawWithBackoff(slistUrl)
        val unescapedRaw = raw
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003F", "?")
            .replace("\\x2F", "/")
            .replace("\\x3A", ":")
            .replace("\\x3F", "?")
            .replace("&#x2F;", "/")
            .replace("&#x3A", ":")
            .replace("&#x3F;", "?")
        // 先尝试从 slist 页脚本中提取图片（覆盖更多脚本写法：普通、协议相对、以及 http(s) + 反斜杠转义形式）
        val escapedProtocolRegex = Regex(
            """https?:\\\\/\\\\/[^'\"\\\\\\s]+?\\\\\\.(?:jpg|jpeg|png|gif|webp)(?:\\\\\\?[^'\"\\\\\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        val protocolRelativeRegex = Regex(
            """\\/\\/[^'\"\s]+?\\.(?:jpg|jpeg|png|gif|webp)(?:\\?[^'\"\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        val normalRegex = Regex(
            """https?://[^'\"\\\s]+\\.(?:jpg|jpeg|png|gif|webp)(?:\\?[^'\"\\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        val imagesFromEscapedProtocol = escapedProtocolRegex.findAll(unescapedRaw).map { it.value.replace("\\/", "/").replace("\\?", "?") }
        val imagesFromProtocolRelative = protocolRelativeRegex.findAll(unescapedRaw).map { "https:" + it.value.replace("\\/", "/").replace("\\?", "?") }
        val imagesFromNormal = normalRegex.findAll(unescapedRaw).map { it.value }
        val quotedUrlRegex = Regex("""["'](https?:[^"'\\s]+?\\.(?:jpg|jpeg|png|gif|webp)[^"'\\s]*)["']""", RegexOption.IGNORE_CASE)
        val imagesFromQuoted = quotedUrlRegex.findAll(unescapedRaw).map { it.groupValues[1] }
        val escapedRelativeRegex = Regex("""\\/(?:[^'\"\\\\\s]+?)\\.(?:jpg|jpeg|png|gif|webp)(?:\\\\\\?[^'\"\\\\\s]*)?""", RegexOption.IGNORE_CASE)
        val relativeRegex = Regex("""/(?:[^'\"\s]+?)\.(?:jpg|jpeg|png|gif|webp)(?:\?[^'\"\s]*)?""", RegexOption.IGNORE_CASE)
        val imagesFromEscapedRelative = escapedRelativeRegex.findAll(unescapedRaw).map { it.value.replace("\\/", "/").replace("\\?", "?") }
        val imagesFromRelative = relativeRegex.findAll(unescapedRaw).map { it.value }
        val imagesFromScript = (imagesFromEscapedProtocol + imagesFromProtocolRelative + imagesFromNormal + imagesFromQuoted + imagesFromEscapedRelative + imagesFromRelative).toList().distinct()
            .mapNotNull { normalizeUrl(it) }
            .filterNot { isLikelyPlaceholder(it) }
        // 不再因脚本命中而直接返回，先收集后续尝试合并
        val collected = LinkedHashSet<String>()
        collected.addAll(imagesFromScript)
        // 脚本命中不再直接返回，继续解析其他来源以合并结果
        // 回退：若存在 aid，访问 index 页并解析 view 列表，再逐页提取 img src
        val indexUrl = id?.let { "/photos-index-page-1-aid-$it.html".toAbsoluteUrl(domain) }
        val doc = httpGetHtmlWithBackoff(indexUrl ?: slistUrl)
        val viewLinks = doc.select("#pic_list a[href*=/photos-view-id-]")
            .ifEmpty { doc.select("a[href*=/photos-view-id-]") }
            .mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() } }
            .map { it.toAbsoluteUrl(domain) }
        // 移动 UA：尝试“开始阅读”按钮跳转的连续阅读页
        if (viewLinks.isEmpty()) {
            val readLinkEl = doc.selectFirst("a#btn_read, a.readbtn, a.btn[href], button#btn_read, button.readbtn, button.btn")
            var readLink = readLinkEl?.attr("href")?.toAbsoluteUrl(domain)
            if (readLink.isNullOrBlank()) {
                val onclick = readLinkEl?.attr("onclick").orEmpty()
                val m = Regex("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*['"]([^'"]+)['"]""").find(onclick)
                readLink = m?.groupValues?.get(1)?.toAbsoluteUrl(domain)
            }
            val isReadText = readLinkEl?.text()?.contains(Regex("(?i)开始阅读|開始閱讀|start\\s*reading")) == true
            val targetReadUrl = readLink ?: id?.let { "/photos-slide-aid-$it.html".toAbsoluteUrl(domain) }
            if (!targetReadUrl.isNullOrBlank() || isReadText) {
                val rdoc = httpGetHtmlWithBackoff(targetReadUrl ?: slistUrl)
                val mobilImgs = rdoc.select("#photo_view img, #picarea img, img#photo_img, img#image, img, div#list img, div#reading img")
                val urls = mobilImgs.mapNotNull { normalizeUrl(rawImgAttr(it)) }.distinct().filterNot { isLikelyPlaceholder(it) }
                if (urls.isNotEmpty()) {
                    return urls.mapIndexed { i, u ->
                        MangaPage(id = i.toLong(), url = u, preview = null, source = source)
                    }
                }
            }
        }
        // 如果列表页没有 view 链接，尝试直接提取页面中的图片节点（优先 data-*）
        val inlineImgs = if (viewLinks.isEmpty()) doc.select("#photo_view img, #picarea img, img#photo_img, img#image, img") else emptyList()
        if (inlineImgs.isNotEmpty()) {
            return inlineImgs.mapIndexed { i, img ->
                val u = normalizeUrl(rawImgAttr(img))
                MangaPage(id = i.toLong(), url = u ?: "", preview = null, source = source)
            }.filter { it.url.isNotBlank() && !isLikelyPlaceholder(it.url) }
        }
        val pages = ArrayList<MangaPage>(viewLinks.size)
        var index = 0L
        for (viewUrl in viewLinks) {
            val vdoc = httpGetHtmlWithBackoff(viewUrl)
            val img = vdoc.selectFirst("#photo_view img, #picarea img, img#photo_img, img#image, img")
            val src = normalizeUrl(rawImgAttr(img))
            if (!src.isNullOrBlank() && !isLikelyPlaceholder(src)) {
                pages.add(
                    MangaPage(
                        id = index++,
                        url = src,
                        preview = null,
                        source = source,
                    )
                )
            }
        }
        if (pages.isNotEmpty()) return pages
        // 进一步回退：尝试 gallery 页面（与 venera 配置一致）
        val galleryUrl = id?.let { "/photos-gallery-aid-$it.html".toAbsoluteUrl(domain) }
        if (galleryUrl != null) {
            val galleryRaw = httpGetRawWithBackoff(galleryUrl)
            val galleryImagesFromEscaped = escapedProtocolRegex.findAll(galleryRaw).map { it.value.replace("\\/", "/").replace("\\?", "?") }
            val galleryImagesFromProtocolRelative = protocolRelativeRegex.findAll(galleryRaw).map { "https:" + it.value }
            val galleryImagesFromNormal = normalRegex.findAll(galleryRaw).map { it.value }
            val escapedRelativeRegex = Regex("""\\/(?:[^'\"\\\\\s]+?)\\.(?:jpg|jpeg|png|gif|webp)(?:\\\\\?[^'\"\\\\\s]*)?""", RegexOption.IGNORE_CASE)
            val relativeRegex = Regex("""/(?:[^'\"\s]+?)\.(?:jpg|jpeg|png|gif|webp)(?:\?[^'\"\s]*)?""", RegexOption.IGNORE_CASE)
            val galleryImagesFromEscapedRelative = escapedRelativeRegex.findAll(galleryRaw).map { it.value.replace("\\/", "/").replace("\\?", "?") }
            val galleryImagesFromRelative = relativeRegex.findAll(galleryRaw).map { it.value }
            val uniqGallery = (galleryImagesFromEscaped + galleryImagesFromProtocolRelative + galleryImagesFromNormal + galleryImagesFromEscapedRelative + galleryImagesFromRelative).toList().distinct()
                .mapNotNull { normalizeUrl(it.trimEnd('"', '\'', '\\')) }
                .filterNot { isLikelyPlaceholder(it) }
            if (uniqGallery.isNotEmpty()) {
                return uniqGallery.mapIndexed { i, u ->
                    MangaPage(id = i.toLong(), url = u, preview = null, source = source)
                }
            }
            val gdoc = httpGetHtmlWithBackoff(galleryUrl)
            val gImgs = gdoc.select("#photo_view img, #picarea img, img#photo_img, img#image, img, div#list img")
            val urls = gImgs.mapNotNull { normalizeUrl(rawImgAttr(it)) }.distinct().filterNot { isLikelyPlaceholder(it) }
            if (urls.isNotEmpty()) {
                return urls.mapIndexed { i, u ->
                    MangaPage(id = i.toLong(), url = u, preview = null, source = source)
                }
            }
        }
        // 进一步回退：尝试 slide 页面
        val slideUrl = id?.let { "/photos-slide-aid-$it.html".toAbsoluteUrl(domain) }
        if (slideUrl != null) {
            val slideRaw = httpGetRawWithBackoff(slideUrl)
            val slideImagesFromEscaped = escapedProtocolRegex.findAll(slideRaw).map { it.value.replace("\\/", "/").replace("\\?", "?") }
            val slideImagesFromProtocolRelative = protocolRelativeRegex.findAll(slideRaw).map { "https:" + it.value }
            val slideImagesFromNormal = normalRegex.findAll(slideRaw).map { it.value }
            val escapedRelativeRegex = Regex("""\\/(?:[^'\"\\\\\s]+?)\\.(?:jpg|jpeg|png|gif|webp)(?:\\\\\?[^'\"\\\\\s]*)?""", RegexOption.IGNORE_CASE)
            val relativeRegex = Regex("""/(?:[^'\"\s]+?)\.(?:jpg|jpeg|png|gif|webp)(?:\?[^'\"\s]*)?""", RegexOption.IGNORE_CASE)
            val slideImagesFromEscapedRelative = escapedRelativeRegex.findAll(slideRaw).map { it.value.replace("\\/", "/").replace("\\?", "?") }
            val slideImagesFromRelative = relativeRegex.findAll(slideRaw).map { it.value }
            val uniqSlide = (slideImagesFromEscaped + slideImagesFromProtocolRelative + slideImagesFromNormal + slideImagesFromEscapedRelative + slideImagesFromRelative).toList().distinct()
                .mapNotNull { normalizeUrl(it) }
                .filterNot { isLikelyPlaceholder(it) }
            if (uniqSlide.isNotEmpty()) {
                return uniqSlide.mapIndexed { i, u ->
                    MangaPage(
                        id = i.toLong(),
                        url = u,
                        preview = null,
                        source = source,
                    )
                }
            }
            val slideDoc = httpGetHtmlWithBackoff(slideUrl)
            val slideImgEls = slideDoc.select("#photo_view img, #picarea img, img#photo_img, img#image, img")
            val urls = slideImgEls.mapNotNull { normalizeUrl(rawImgAttr(it)) }.distinct().filterNot { isLikelyPlaceholder(it) }
            if (urls.isNotEmpty()) {
                return urls.mapIndexed { i, u ->
                    MangaPage(
                        id = i.toLong(),
                        url = u,
                        preview = null,
                        source = source,
                    )
                }
            }
        }
        if (collected.isNotEmpty()) {
            return collected.mapIndexed { i, u ->
                MangaPage(id = i.toLong(), url = u, preview = null, source = source)
            }
        }
        return emptyList()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val lastSeg = req.url.pathSegments.lastOrNull()
        val ext = lastSeg?.substringAfterLast('.', "")?.lowercase()
        val isImage = ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "gif" || ext == "webp" ||
            (req.header("Accept")?.contains("image/") == true)
        val builder = req.newBuilder()
            .header("User-Agent", config[userAgentKey])
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "https://$domain/")
            .header("Origin", "https://$domain")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Dest", if (isImage) "image" else "document")
            .header("Sec-Fetch-Mode", if (isImage) "no-cors" else "navigate")
            .removeHeader("Authorization")
            .removeHeader("authorization")
        if (isImage) {
            builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        }
        val newReq = builder.build()
        return chain.proceed(newReq)
    }

    private fun extractIdFromUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val start = url.indexOf("-aid-")
        if (start == -1) return null
        val sub = url.substring(start + 5)
        val end = sub.indexOf('.')
        return if (end == -1) sub.filter { it.isDigit() } else sub.substring(0, end).filter { it.isDigit() }
    }
}
