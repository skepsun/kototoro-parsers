package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import java.net.URLEncoder
import java.util.EnumSet

/**
 * 牛牛资源（api.niuniuzy.me）视频解析器
 * - 列表：默认从首页抓取；支持分类（电影 id=1）与搜索
 * - 详情：基础元信息（标题/封面/描述）；生成观看章节
 * - 视频：从 <video>、LD+JSON、脚本字符串中提取 m3u8/mp4
 */
// @Broken("Under development")
@MangaSourceParser(name = "NIUNIUZY", title = "牛牛资源", locale = "zh", type = ContentType.VIDEO)
internal class Niuniuzy(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.NIUNIUZY,
    pageSize = 30,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("api.niuniuzy.me")
    override val userAgentKey: ConfigKey.UserAgent = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isOriginalLocaleSupported = true,
        )

    // 分类标签映射（按用户提供）：
    // 电影(1)、电视剧(2)、综艺(3)、动漫(4)、伦理(55)、爽文短剧(54)、影视解说(53)、体育赛事(48)、预告片(51)
    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = linkedSetOf(
            MangaTag(title = "电影", key = "cate:1", source = source),
            MangaTag(title = "电视剧", key = "cate:2", source = source),
            MangaTag(title = "综艺", key = "cate:3", source = source),
            MangaTag(title = "动漫", key = "cate:4", source = source),
            MangaTag(title = "伦理", key = "cate:55", source = source),
            MangaTag(title = "爽文短剧", key = "cate:54", source = source),
            MangaTag(title = "影视解说", key = "cate:53", source = source),
            MangaTag(title = "体育赛事", key = "cate:48", source = source),
            MangaTag(title = "预告片", key = "cate:51", source = source),
        ),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        // 搜索走站内 HTML，API 不支持搜索
        if (!filter.query.isNullOrBlank()) {
            val url = buildListUrl(page, filter)
            val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
            val list = enrichListWithApi(parseListFromHtml(doc))
            if (list.isEmpty()) {
                // 提示用户先在浏览器完成验证码
                throw IllegalStateException("搜索需要先在浏览器完成验证码：设置-在浏览器中打开，随便搜索后填写验证码，再回到应用重试。")
            }
            return list
        }

        // 优先使用 API 列表，保证封面与标题准确；若 API 返回空并且有筛选，则回退 HTML 抓取
        fetchListFromApi(page, filter)?.let { list ->
            if (list.isNotEmpty()) return list
            if (filter.tags.isEmpty() && filter.query.isNullOrBlank()) return list
        }

        val url = buildListUrl(page, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return enrichListWithApi(parseListFromHtml(doc))
    }

    private fun parseListFromHtml(doc: org.jsoup.nodes.Document): List<Manga> {
        val out = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>()

        val anchors = doc.select(
            "a[href*=/index.php/vod/detail/], a[href*=/vod/detail/], a[href*=/index.php/vod/play/], a[href*=/vod/play/]",
        )
        for (a in anchors) {
            val href = runCatching { a.attrAsRelativeUrl("href") }.getOrNull() ?: continue
            if (!seen.add(href)) continue
            val container = a.parents().firstOrNull { p ->
                p.hasClass("stui-vodlist__item") || p.hasClass("module-item") || p.hasClass("card") ||
                    p.hasClass("vodlist_item") || p.hasClass("module-item-index") || p.hasClass("thumb")
            } ?: a.parent()

            val title = a.attrOrNull("title")?.takeIf { it.isNotBlank() }
                ?: container?.selectFirst(
                    ".stui-vodlist__title, .title, .vodlist_title, h3, h2, .name, .module-title",
                )?.text()?.takeIf { it.isNotBlank() }
                ?: a.text().takeIf { it.isNotBlank() }
                ?: container?.selectFirst("img[alt]")?.attrOrNull("alt")
                ?: "未命名"

            val img = container?.selectFirst(
                "img[data-original], img[data-src], img[srcset], img[src], img[data-lazy], img[data-echo]",
            ) ?: a.selectFirst("img")
            val cover = resolveCover(img, container)

            out.add(
                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = cover,
                    largeCoverUrl = null,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = null,
                    contentRating = ContentRating.SAFE,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
            if (out.size >= pageSize) break
        }

        if (out.isEmpty()) {
            val cards = doc.select(
                ".stui-vodlist__item, .module-item, .vodlist_item, .card, .thumb, .module-item-index",
            )
            for (c in cards) {
                val link = c.selectFirst("a[href]") ?: continue
                val href = runCatching { link.attrAsRelativeUrl("href") }.getOrNull() ?: continue
                if (!seen.add(href)) continue
                val titleEl = c.selectFirst(
                    ".stui-vodlist__title, .title, .vodlist_title, h3, h2, .name, .module-title",
                )
                val title = titleEl?.text()?.takeIf { it.isNotBlank() } ?: link.attrOrNull("title")
                    ?: link.text().takeIf { it.isNotBlank() } ?: "未命名"
                val img = c.selectFirst(
                    "img[data-original], img[data-src], img[srcset], img[src], img[data-lazy], img[data-echo]",
                )
                val cover = resolveCover(img, c)

                out.add(
                    Manga(
                        id = generateUid(href),
                        url = href,
                        publicUrl = href.toAbsoluteUrl(domain),
                        title = title,
                        altTitles = emptySet(),
                        coverUrl = cover,
                        largeCoverUrl = null,
                        authors = emptySet(),
                        tags = emptySet(),
                        state = null,
                        description = null,
                        contentRating = ContentRating.SAFE,
                        source = source,
                        rating = RATING_UNKNOWN,
                    ),
                )
                if (out.size >= pageSize) break
            }
        }

        return out
    }

    private suspend fun fetchListFromApi(page: Int, filter: MangaListFilter): List<Manga>? {
        try {
            val url = buildApiListUrl(page, filter)
            val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()
            val json = JSONObject(raw)
            val data = when {
                json.has("list") -> json.getJSONArray("list")
                json.has("data") -> json.getJSONArray("data")
                else -> return null
            }
            if (data.length() == 0) return emptyList()

            val ids = ArrayList<String>(data.length())
            val basic = HashMap<String, JSONObject>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("vod_id", item.optString("id", ""))
                if (id.isBlank()) continue
                ids.add(id)
                basic[id] = item
            }
            if (ids.isEmpty()) return emptyList()

            val detailUrl = "https://$domain/api.php/provide/vod/?ac=detail&ids=${ids.joinToString(",")}"
            val detailJson = JSONObject(webClient.httpGet(detailUrl, getRequestHeaders()).parseRaw())
            val detailArr: JSONArray = when {
                detailJson.has("list") -> detailJson.getJSONArray("list")
                detailJson.has("data") -> detailJson.getJSONArray("data")
                else -> JSONArray()
            }
            val detailMap = HashMap<String, JSONObject>()
            for (i in 0 until detailArr.length()) {
                val obj = detailArr.optJSONObject(i) ?: continue
                val id = obj.optString("vod_id", obj.optString("id", ""))
                if (id.isNotBlank()) detailMap[id] = obj
            }

            val result = ArrayList<Manga>(ids.size)
            for (id in ids) {
                val detail = detailMap[id]
                val base = detail ?: basic[id] ?: continue
                val name = detail?.optString("vod_name", null)
                    ?: basic[id]?.optString("vod_name", basic[id]?.optString("name", null))
                    ?: "未命名"
                val pic = detail?.optString("vod_pic", null)?.takeIf { it.isNotBlank() }
                    ?: basic[id]?.optString("vod_pic", null)?.takeIf { it.isNotBlank() }
                val cover = pic?.toAbsoluteUrlOrNull(domain) ?: pic
                val typeName = detail?.optString("type_name", null) ?: basic[id]?.optString("type_name", null).orEmpty()
                val tag = typeName.takeIf { it.isNotBlank() }?.let { MangaTag(it, it, source) }
                val remarks = detail?.optString("vod_remarks", null)
                    ?: basic[id]?.optString("vod_remarks", null)
                    ?: detail?.optString("vod_sub", null)
                val relUrl = "/index.php/vod/detail/id/$id.html"

                result.add(
                    Manga(
                        id = generateUid(relUrl),
                        url = relUrl,
                        publicUrl = relUrl.toAbsoluteUrl(domain),
                        title = name,
                        altTitles = remarks?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet(),
                        coverUrl = cover,
                        largeCoverUrl = cover,
                        authors = emptySet(),
                        tags = tag?.let { setOf(it) } ?: emptySet(),
                        state = null,
                        description = base.optString("vod_content", "").takeIf { it.isNotBlank() },
                        contentRating = ContentRating.SAFE,
                        source = source,
                        rating = RATING_UNKNOWN,
                    ),
                )
            }
            return result
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildApiListUrl(page: Int, filter: MangaListFilter): String {
        val sb = StringBuilder("https://").append(domain).append("/api.php/provide/vod/?ac=list&pg=").append(page)
        filter.tags.firstOrNull()?.key?.substringAfter("cate:")?.toIntOrNull()?.let { sb.append("&t=").append(it) }
        if (!filter.query.isNullOrBlank()) {
            sb.append("&wd=").append(URLEncoder.encode(filter.query, "UTF-8"))
        }
        return sb.toString()
    }

    private fun buildListUrl(page: Int, filter: MangaListFilter): String {
        // 分类优先
        val cateId = filter.tags.firstOrNull()?.key?.substringAfter("cate:")?.toIntOrNull()
        if (cateId != null) {
            val base = StringBuilder().append("https://").append(domain)
                .append("/index.php/vod/type/id/").append(cateId)
            if (page > 1) base.append("/page/").append(page)
            base.append(".html")
            return base.toString()
        }
        // 搜索
        if (!filter.query.isNullOrBlank()) {
            val wd = URLEncoder.encode(filter.query!!, "UTF-8")
            val base = StringBuilder().append("https://").append(domain)
                .append("/index.php/vod/search.html?wd=").append(wd)
            if (page > 1) base.append("&page=").append(page)
            return base.toString()
        }
        // 首页（部分模板不支持分页，尝试常规 page 参数）
        return if (page > 1) {
            "https://$domain/index.php/vod/type/id/1/page/$page.html"
        } else {
            "https://$domain/"
        }
    }

    private fun resolveCover(imgEl: Element?, container: Element?): String? {
        // 优先 data-original / data-src
        imgEl?.attrAsAbsoluteUrlOrNull("data-original")?.let { return it }
        imgEl?.attrAsAbsoluteUrlOrNull("data-src")?.let { return it }
        // 普通 src
        imgEl?.attrAsAbsoluteUrlOrNull("src")?.let { return it }
        // srcset（选取第一候选）
        val srcset = imgEl?.attrOrNull("srcset") ?: container?.selectFirst("picture source[srcset]")?.attrOrNull("srcset")
        if (!srcset.isNullOrBlank()) {
            val first = srcset.split(',').map { it.trim() }.firstOrNull()?.split(' ')?.firstOrNull()?.trim()
            if (!first.isNullOrBlank()) return first.toAbsoluteUrl(domain)
        }
        return null
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val apiDetail = fetchDetailFromApi(manga)
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val metaDesc = doc.selectFirst("meta[name=description]")?.attrOrNull("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attrOrNull("content")
        val metaTitle = doc.selectFirst("meta[property=og:title]")?.attrOrNull("content")
        var metaImage = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")

        // 站点模板未提供 og:image 时，尝试从详情页封面容器提取
        if (metaImage.isNullOrBlank()) {
            metaImage = doc.selectFirst(".xiangqing-top .img img[src]")?.attrAsAbsoluteUrlOrNull("src")
                ?: doc.selectFirst("img[src*=/upload/]")?.attrAsAbsoluteUrlOrNull("src")
        }

        val chapters = apiDetail?.chapters?.takeIf { it.isNotEmpty() } ?: listOf(
            MangaChapter(
                id = generateUid("${manga.url}|video"),
                url = manga.url,
                title = "观看",
                number = 1f,
                uploadDate = 0L,
                volume = 0,
                branch = null,
                scanlator = null,
                source = source,
            ),
        )

        return manga.copy(
            title = apiDetail?.title ?: metaTitle ?: manga.title,
            description = apiDetail?.description ?: metaDesc ?: manga.description,
            largeCoverUrl = apiDetail?.cover ?: metaImage ?: manga.largeCoverUrl,
            chapters = chapters,
        )
    }

    private data class ApiDetail(
        val title: String?,
        val description: String?,
        val cover: String?,
        val chapters: List<MangaChapter>,
    )

    private suspend fun fetchDetailFromApi(manga: Manga): ApiDetail? {
        val id = extractVodId(manga.url) ?: extractVodId(manga.publicUrl) ?: return null
        val url = "https://$domain/api.php/provide/vod/?ac=detail&ids=$id"
        val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()
        val json = JSONObject(raw)
        val data = when {
            json.has("list") -> json.getJSONArray("list")
            json.has("data") -> json.getJSONArray("data")
            else -> return null
        }
        val obj = data.optJSONObject(0) ?: return null
        val title = obj.optString("vod_name", obj.optString("name", null))
        val cover = obj.optString("vod_pic", obj.optString("pic", null)).toAbsoluteUrlOrNull(domain)
        val desc = obj.optString("vod_content", obj.optString("content", null))
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
            ?.ifBlank { null }
        val playUrl = obj.optString("vod_play_url", "")
        val chapters = parseChaptersFromPlayUrl(playUrl, id)
        return ApiDetail(title, desc, cover, chapters)
    }

    private fun parseChaptersFromPlayUrl(playUrl: String, vodId: String): List<MangaChapter> {
        if (playUrl.isBlank()) return emptyList()
        val segments = playUrl.split("#").mapNotNull { it.takeIf { seg -> seg.isNotBlank() } }
        val chapters = ArrayList<MangaChapter>(segments.size)
        var index = 1
        for (seg in segments) {
            val parts = seg.split("$", limit = 2)
            val title = parts.getOrNull(0)?.ifBlank { null } ?: "第${index}集"
            val url = parts.getOrNull(1)?.trim().orEmpty()
            if (url.isBlank()) {
                index++
                continue
            }
            val number = Regex("""(\d+(?:\.\d+)?)""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
                ?: index.toFloat()
            chapters.add(
                MangaChapter(
                    id = generateUid("$vodId|$index|$url"),
                    url = url,
                    title = title,
                    number = number,
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                ),
            )
            index++
        }
        return chapters
    }

    private suspend fun enrichListWithApi(list: List<Manga>): List<Manga> {
        if (list.isEmpty()) return list
        val idMap = HashMap<String, Manga>()
        list.forEach { m ->
            val id = extractVodId(m.url) ?: extractVodId(m.publicUrl) ?: return@forEach
            idMap[id] = m
        }
        if (idMap.isEmpty()) return list
        return try {
            val detailUrl = "https://$domain/api.php/provide/vod/?ac=detail&ids=${idMap.keys.joinToString(",")}"
            val detailJson = JSONObject(webClient.httpGet(detailUrl, getRequestHeaders()).parseRaw())
            val detailArr: JSONArray = when {
                detailJson.has("list") -> detailJson.getJSONArray("list")
                detailJson.has("data") -> detailJson.getJSONArray("data")
                else -> JSONArray()
            }
            val detailMap = HashMap<String, JSONObject>()
            for (i in 0 until detailArr.length()) {
                val obj = detailArr.optJSONObject(i) ?: continue
                val id = obj.optString("vod_id", obj.optString("id", ""))
                if (id.isNotBlank()) detailMap[id] = obj
            }
            list.map { m ->
                val id = extractVodId(m.url) ?: extractVodId(m.publicUrl)
                val detail = id?.let { detailMap[it] }
                if (detail == null) {
                    m
                } else {
                    val title = detail.optString("vod_name", "").takeIf { it.isNotBlank() }
                    val cover = detail.optString("vod_pic", "").takeIf { it.isNotBlank() }?.toAbsoluteUrlOrNull(domain)
                    val remarks = detail.optString("vod_remarks", "").takeIf { it.isNotBlank() }
                    val typeName = detail.optString("type_name", "")
                    val tag = typeName.takeIf { it.isNotBlank() }?.let { MangaTag(it, it, source) }
                    val desc = detail.optString("vod_content", "").replace(Regex("<[^>]+>"), "").trim().ifBlank { null }
                    m.copy(
                        title = title ?: m.title,
                        altTitles = remarks?.let { setOf(it) } ?: m.altTitles,
                        coverUrl = cover ?: m.coverUrl,
                        largeCoverUrl = cover ?: m.largeCoverUrl,
                        tags = tag?.let { setOf(it) } ?: m.tags,
                        description = desc ?: m.description,
                    )
                }
            }
        } catch (_: Exception) {
            list
        }
    }

    private fun extractVodId(url: String): String? {
        Regex("/(?:detail|play)/id/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("id=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        if ((chapter.url.startsWith("http://") || chapter.url.startsWith("https://")) &&
            (chapter.url.endsWith(".m3u8", true) || chapter.url.endsWith(".mp4", true))
        ) {
            return listOf(
                MangaPage(
                    id = generateUid("${chapter.id}|page1"),
                    url = chapter.url,
                    preview = null,
                    source = source,
                ),
            )
        }
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        // 优先多策略提取，其次支持站点“复制播放地址”块（.link.q1）
        val directLinkBlock = doc.selectFirst("div.link.q1")?.text()?.trim()
        val extra = buildList {
            val t = directLinkBlock
            if (!t.isNullOrBlank() && (t.endsWith(".m3u8", true) || t.endsWith(".mp4", true))) add(t)
        }
        
        // 多种策略提取视频链接
        val streams = mutableListOf<String>()
        
        // 1. 从JavaScript变量中提取
        streams.addAll(extractFromJavaScript(doc))
        
        // 2. 从视频标签提取
        streams.addAll(extractFromVideoTag(doc))
        
        // 3. 从LD+JSON提取
        streams.addAll(extractFromLdJson(doc))
        
        // 4. 正则表达式提取
        streams.addAll(extractByRegex(doc))
        
        // 5. 从特定CSS类提取（如.link.q1）
        streams.addAll(extractFromCssClasses(doc))
        
        // 6. 从iframe中提取
        streams.addAll(extractFromIframe(doc))
        
        val distinctStreams = streams.distinct().filter { 
            it.isNotBlank() && (it.endsWith(".m3u8", true) || it.endsWith(".mp4", true))
        }
        
        if (distinctStreams.isEmpty()) return emptyList()
        
        val poster = doc.selectFirst("video[poster]")?.attrOrNull("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
            ?: doc.selectFirst(".xiangqing-top .img img[src]")?.attrAsAbsoluteUrlOrNull("src")
            ?: doc.selectFirst(".detail-pic img[src]")?.attrAsAbsoluteUrlOrNull("src")
            ?: doc.selectFirst("img[src*=/upload/]")?.attrAsAbsoluteUrlOrNull("src")
            
        return distinctStreams.map { u ->
            MangaPage(
                id = generateUid(u),
                url = u,
                preview = poster,
                source = source,
            )
        }
    }

    fun extractFromVideoTag(doc: Document): List<String> {
        val res = ArrayList<String>()
        val video = doc.selectFirst("video")
        if (video != null) {
            doc.select("video source[src]").forEach { s -> s.attrOrNull("src")?.let(res::add) }
            video.attrOrNull("src")?.let(res::add)
        }
        return res
    }

    private fun extractFromLdJson(doc: Document): List<String> {
        val res = ArrayList<String>()
        val scripts = doc.select("script[type=application/ld+json]")
        for (s in scripts) {
            val raw = s.data().trim()
            if (raw.isEmpty()) continue
            runCatching {
                val node = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
                when (node) {
                    is JSONObject -> {
                        node.optString("contentUrl").takeIf { it.isNotBlank() }?.let(res::add)
                        node.optJSONObject("mainEntity")?.optString("contentUrl")?.takeIf { it.isNotBlank() }?.let(res::add)
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            node.optJSONObject(i)?.optString("contentUrl")?.takeIf { it.isNotBlank() }?.let(res::add)
                        }
                    }
                }
            }.getOrElse { }
        }
        return res
    }

    private fun extractFromJavaScript(doc: Document): List<String> {
        val res = ArrayList<String>()
        val scripts = doc.select("script:not([type]), script[type=text/javascript]")
        for (script in scripts) {
            val jsCode = script.data()
            if (jsCode.isBlank()) continue
            
            // 匹配常见的视频URL模式
            val patterns = listOf(
                Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4))""", RegexOption.IGNORE_CASE),
                Regex("""url\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4))["']""", RegexOption.IGNORE_CASE),
                Regex("""src\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4))["']""", RegexOption.IGNORE_CASE),
                Regex("""video_url\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""m3u8_url\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(jsCode).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.startsWith("http")) {
                        res.add(url)
                    }
                }
            }
        }
        return res
    }

    private fun extractFromCssClasses(doc: Document): List<String> {
        val res = ArrayList<String>()
        // 检查常见的播放地址容器
        val selectors = listOf(
            ".link.q1", ".player-url", ".video-url", 
            ".play-url", ".m3u8-url", ".video-src"
        )
        
        selectors.forEach { selector ->
            doc.select(selector).forEach { element ->
                val text = element.text().trim()
                if (text.isNotBlank() && (text.endsWith(".m3u8", true) || text.endsWith(".mp4", true))) {
                    res.add(text)
                }
            }
        }
        return res
    }

    private fun extractFromIframe(doc: Document): List<String> {
        val res = ArrayList<String>()
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("m3u8", true) || src.contains("mp4", true)) {
                res.add(src)
            }
        }
        return res
    }

    private fun extractByRegex(doc: Document): List<String> {
        val res = ArrayList<String>()
        val html = doc.outerHtml()
        val hls = Regex("https?://[^\"'\\s>]+\\.m3u8", RegexOption.IGNORE_CASE)
        val mp4 = Regex("https?://[^\"'\\s>]+\\.mp4", RegexOption.IGNORE_CASE)
        hls.findAll(html).forEach { res.add(it.value) }
        mp4.findAll(html).forEach { res.add(it.value) }
        val patterns = listOf(
            Regex("""https?://[^"'\s>]+\\.m3u8[^"'\s>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s>]+\\.mp4[^"'\s>]*""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'\s>]+/index\.m3u8[^"'\s>]*)""", RegexOption.IGNORE_CASE)
        )
        
        patterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                res.add(match.value)
            }
        }
        return res
    }
}
