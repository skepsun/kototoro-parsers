package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import java.io.File
import java.net.URLEncoder
import java.util.EnumSet
import org.skepsun.kototoro.parsers.util.json.toJSONObjectOrNull
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy

/**
 * Hanime video source parser (skeleton). This minimal implementation focuses on structure.
 * It is annotated and registered via KSP and can be iterated on later.
 */
@MangaSourceParser(name = "HANIME1", title = "Hanime1", locale = "zh", type = ContentType.VIDEO)
internal class Hanime1(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.HANIME1,
    pageSize = 24,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "hanime1.me",
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        // Include UA in config for possible video CDN requirements later
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        // 与站点枚举对齐的核心排序项
        SortOrder.NEWEST,
        SortOrder.ADDED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY_YEAR,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    // 站点 filters 固化为静态枚举，不依赖外部 JSON
    private val GENRE_VALUES: Set<String> = linkedSetOf(
        "裏番",
        "泡麵番",
        "Motion Anime",
        "3D動畫",
        "同人作品",
        "MMD",
        "Cosplay",
    )

    private val DATE_OPTION_VALUES: Set<String> = linkedSetOf(
        "過去 24 小時",
        "過去 2 天",
        "過去 1 週",
        "過去 1 個月",
        "過去 3 個月",
        "過去 1 年",
    )

    private val DURATION_VALUES: Set<String> = linkedSetOf(
        "1 分鐘 +",
        "5 分鐘 +",
        "10 分鐘 +",
        "20 分鐘 +",
        "30 分鐘 +",
        "60 分鐘 +",
        "0 - 10 分鐘",
        "0 - 20 分鐘",
    )

    // 站点标签分组，提取自 search.html 的 <input name="tags[]" value="..."> 列表
    private val SITE_TAG_GROUPS: List<Pair<String, List<String>>> = listOf(
        "影片屬性" to listOf(
            "無碼",
            "AI解碼",
            "中文字幕",
            "中文配音",
            "1080p",
            "60FPS",
            "ASMR",
            "斷面圖",
        ),
        "人際關係" to listOf(
            "近親",
            "姐",
            "妹",
            "母",
            "女兒",
            "師生",
            "情侶",
            "青梅竹馬",
            "同事",
        ),
        "角色/職業" to listOf(
            "JK",
            "處女",
            "御姐",
            "熟女",
            "人妻",
            "女教師",
            "男教師",
            "女醫生",
            "女病人",
            "護士",
            "OL",
            "女警",
            "大小姐",
            "偶像",
            "女僕",
            "巫女",
            "魔女",
            "修女",
            "風俗娘",
            "公主",
            "女忍者",
            "女戰士",
            "女騎士",
            "魔法少女",
        ),
        "幻想種族" to listOf(
            "異種族",
            "天使",
            "妖精",
            "魔物娘",
            "魅魔",
            "吸血鬼",
            "女鬼",
            "獸娘",
            "乳牛",
            "機械娘",
        ),
        "性格/屬性" to listOf(
            "碧池",
            "痴女",
            "雌小鬼",
            "不良少女",
            "傲嬌",
            "病嬌",
            "無口",
            "無表情",
            "眼神死",
            "正太",
            "偽娘",
            "扶他",
        ),
        "外觀體型" to listOf(
            "短髮",
            "馬尾",
            "雙馬尾",
            "巨乳",
            "乳環",
            "舌環",
            "貧乳",
            "黑皮膚",
            "曬痕",
            "眼鏡娘",
            "獸耳",
            "尖耳朵",
            "異色瞳",
            "美人痣",
            "肌肉女",
            "白虎",
            "陰毛",
            "腋毛",
            "大屌",
            "著衣",
        ),
        "服飾道具" to listOf(
            "水手服",
            "體操服",
            "泳裝",
            "比基尼",
            "死庫水",
            "和服",
            "兔女郎",
            "圍裙",
            "啦啦隊",
            "絲襪",
            "吊襪帶",
            "熱褲",
            "迷你裙",
            "性感內衣",
            "緊身衣",
            "丁字褲",
            "高跟鞋",
            "睡衣",
            "婚紗",
            "旗袍",
            "古裝",
            "哥德蘿莉塔",
            "口罩",
            "刺青",
            "淫紋",
            "身體寫字",
        ),
        "劇情/題材" to listOf(
            "純愛",
            "戀愛喜劇",
            "後宮",
            "十指緊扣",
            "開大車",
            "校園",
            "教室",
            "公眾場合",
            "公共廁所",
            "出軌",
            "醉酒",
            "攝影",
            "性轉換",
            "百合",
            "耽美",
            "時間停止",
            "異世界",
            "怪獸",
            "哥布林",
            "世界末日",
        ),
        "調教/獵奇" to listOf(
            "NTR",
            "精神控制",
            "藥物",
            "痴漢",
            "阿嘿顏",
            "精神崩潰",
            "獵奇",
            "BDSM",
            "綑綁",
            "眼罩",
            "項圈",
            "調教",
            "異物插入",
            "尋歡洞",
            "肉便器",
            "性奴隸",
            "胃凸",
            "強制",
            "輪姦",
            "凌辱",
            "性暴力",
            "逆強制",
            "女王樣",
            "母女丼",
            "姐妹丼",
            "睡眠姦",
            "機械姦",
            "蟲姦",
        ),
        "玩法/體位/體液" to listOf(
            "手交",
            "指交",
            "乳交",
            "乳頭交",
            "肛交",
            "雙洞齊下",
            "腳交",
            "素股",
            "拳交",
            "3P",
            "群交",
            "口交",
            "深喉嚨",
            "口爆",
            "吞精",
            "舔蛋蛋",
            "舔穴",
            "69",
            "自慰",
            "腋交",
            "舔腋下",
            "髮交",
            "舔耳朵",
            "內射",
            "外射",
            "顏射",
            "潮吹",
            "懷孕",
            "噴奶",
            "放尿",
            "排便",
            "騎乘位",
            "背後位",
            "顏面騎乘",
            "火車便當",
            "車震",
            "性玩具",
            "飛機杯",
            "跳蛋",
            "毒龍鑽",
            "觸手",
            "獸交",
            "頸手枷",
            "扯頭髮",
            "掐脖子",
            "打屁股",
            "肉棒打臉",
            "陰道外翻",
            "接吻",
            "舌吻",
            "POV",
        ),
    )

    private val BROAD_SUPPORTED: Boolean = true

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val (tags, tagGroups) = buildStaticFilterTags()
        return MangaListFilterOptions(
            availableTags = tags,
            tagGroups = tagGroups,
        )
    }

    private fun buildStaticFilterTags(): Pair<Set<MangaTag>, List<MangaTagGroup>> {
        val tags = LinkedHashSet<MangaTag>()
        val tagGroups = ArrayList<MangaTagGroup>()

        fun addGroup(
            groupName: String,
            values: Iterable<String>,
            keyBuilder: (String) -> String,
        ) {
            val groupTags = LinkedHashSet<MangaTag>()
            values.forEach { value ->
                val tag = MangaTag(title = value, key = keyBuilder(value), source = source)
                groupTags.add(tag)
                tags.add(tag)
            }
            if (groupTags.isNotEmpty()) {
                tagGroups.add(MangaTagGroup(groupName, groupTags))
            }
        }

        addGroup("類別", GENRE_VALUES) { value -> "genre:$value" }
        addGroup("日期", DATE_OPTION_VALUES) { value -> "date:$value" }
        val years = (2025 downTo 1990).map { "$it 年" }
        addGroup("年份", years) { value -> "date-year:${value.substringBefore(' ')}" }
        val months = (1..12).map { "$it 月" }
        addGroup("月份", months) { value -> "date-month:${value.substringBefore(' ')}" }
        addGroup("時長", DURATION_VALUES) { value -> "duration:$value" }

        SITE_TAG_GROUPS.forEach { (groupName, values) ->
            addGroup(groupName, values) { value -> "tag:$value" }
        }

        if (BROAD_SUPPORTED) {
            val broadTag = MangaTag(title = "广泛配对", key = "broad:on", source = source)
            tags.add(broadTag)
            tagGroups.add(MangaTagGroup("其他", linkedSetOf(broadTag)))
        }

        return tags to tagGroups
    }

    // JSON 解析函数废弃，不再使用（保留构建兼容性）

    

    

    

    

    

    

    private fun parseTypeTags(json: JSONObject?): Set<MangaTag> {
        val arr = json?.optJSONArray("type") ?: JSONArray()
        if (arr.length() > 0) {
            val tags = LinkedHashSet<MangaTag>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val value = o.optString("value").takeIf { it.isNotBlank() } ?: continue
                val label = o.optString("label").takeIf { it.isNotBlank() } ?: value
                tags.add(MangaTag(title = label, key = "type:$value", source = source))
            }
            return tags
        }
        return emptySet()
    }

    private suspend fun fetchTypeTagsFromNetwork(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/search", getRequestHeaders()).parseHtml()
        val anchors = doc.select("a[href*=type=]")
        val tags = LinkedHashSet<MangaTag>(maxOf(anchors.size, 1))
        anchors.forEach { a ->
            val href = a.attrOrNull("href") ?: return@forEach
            val value = queryParam(href, "type") ?: return@forEach
            val label = a.text().takeIf { it.isNotBlank() } ?: value
            tags.add(MangaTag(title = label, key = "type:$value", source = source))
        }
        if (tags.isEmpty()) {
            val label = doc.selectFirst(".search-type-input")?.text()?.takeIf { it.isNotBlank() } ?: "搜寻作者"
            tags.add(MangaTag(title = label, key = "type:artist", source = source))
        }
        return tags
    }

    private fun queryParam(url: String, name: String): String? {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return null
        q.split('&').forEach { pair ->
            if (pair.startsWith(name + '=')) return pair.substringAfter('=')
        }
        return null
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        // 構造 search 地址，支持 query、genre、type、date、duration、sort
        val url = buildSearchUrl(page = page, order = order, filter = filter)

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val items = ArrayList<Manga>(pageSize)
        val seen = LinkedHashSet<String>(pageSize * 2)

        // Strategy 1: cards with explicit watch links (hanime1.me uses overlay anchor inside card)
        val anchors = doc.select("a[href*=/watch]")
        if (anchors.isNotEmpty()) {
            for (a in anchors) {
                val href = runCatching { a.attrAsRelativeUrl("href") }.getOrNull() ?: continue
                if (!seen.add(href)) continue
                val container = a.parents().firstOrNull { it.hasClass("multiple-link-wrapper") }
                    ?: a.parents().firstOrNull { it.hasClass("card-mobile-panel") }
                    ?: a.parent()
                val title = a.attrOrNull("title")?.takeIf { it.isNotBlank() }
                    ?: container?.selectFirst(".card-mobile-title")?.text()?.takeIf { it.isNotBlank() }
                    ?: container?.selectFirst(".title, .card-title, h3, h2, .name")?.text()?.takeIf { it.isNotBlank() }
                    ?: a.text().takeIf { it.isNotBlank() }
                    ?: "Untitled"
                var coverAbs = a.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src")
                    ?: a.selectFirst("img")?.attrAsAbsoluteUrlOrNull("data-src")
                if (coverAbs.isNullOrBlank() && container != null) {
                    val imgs = container.select("img")
                    coverAbs = imgs.firstOrNull { it.attr("src").contains("/image/thumbnail/") }
                        ?.attrAsAbsoluteUrlOrNull("src")
                        ?: imgs.firstOrNull { it.hasAttr("data-src") }?.attrAsAbsoluteUrlOrNull("data-src")
                        ?: imgs.firstOrNull { it.hasAttr("src") }?.attrAsAbsoluteUrlOrNull("src")
                }

                items.add(
                    Manga(
                        id = generateUid(href),
                        url = href,
                        publicUrl = href.toAbsoluteUrl(domain),
                        title = title,
                        altTitles = emptySet(),
                        coverUrl = coverAbs,
                        largeCoverUrl = null,
                        authors = emptySet(),
                        tags = emptySet(),
                        state = null,
                        description = null,
                        contentRating = ContentRating.ADULT,
                        source = source,
                        rating = RATING_UNKNOWN,
                    ),
                )
                if (items.size >= pageSize) break
            }
        }

        // Strategy 2: fallback to generic grid cards (common on hanime.tv)
        if (items.isEmpty()) {
            val cards = doc.select(".card, .video-item, .video-card, .thumb, .hentai-item")
            for (c in cards) {
                val link = c.selectFirst("a[href]") ?: continue
                val href = runCatching { link.attrAsRelativeUrl("href") }.getOrNull() ?: continue
                val titleEl = c.selectFirst(".title, .card-title, h3, h2, .name")
                val title = titleEl?.text()?.takeIf { it.isNotBlank() } ?: link.attrOrNull("title")
                    ?: link.text().takeIf { it.isNotBlank() } ?: "Untitled"
                val img = c.selectFirst("img")
                val coverAbs = img?.attrAsAbsoluteUrlOrNull("src")
                    ?: img?.attrAsAbsoluteUrlOrNull("data-src")

                items.add(
                    Manga(
                        id = generateUid(href),
                        url = href,
                        publicUrl = href.toAbsoluteUrl(domain),
                        title = title,
                        altTitles = emptySet(),
                        coverUrl = coverAbs,
                        largeCoverUrl = null,
                        authors = emptySet(),
                        tags = emptySet(),
                        state = null,
                        description = null,
                        contentRating = ContentRating.ADULT,
                        source = source,
                        rating = RATING_UNKNOWN,
                    ),
                )
                if (items.size >= pageSize) break
            }
        }

        return items
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun buildSearchUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val base = StringBuilder()
            .append("https://")
            .append(domain)
            .append("/search")

        val params = ArrayList<String>(8)

        // query 可以為空，但仍保留參數，符合用戶期望
        params.add("query=" + encode(filter.query ?: ""))

        // sort 使用站点繁体枚举 value
        sortMapping(order)?.let { value ->
            params.add("sort=" + encode(value))
        }

        // 解析 tags，支持 genre/type/date/date-year/date-month/duration/tag 前綴
        if (filter.tags.isNotEmpty()) {
            // 支持多選 genre
            filter.tags.filter { it.key.startsWith("genre:") }.forEach { t ->
                // 统一传繁体 value
                params.add("genre=" + encode(t.key.substringAfter(':')))
            }
            filter.tags.find { it.key.startsWith("date:") }?.let { t ->
                params.add("date=" + encode(t.key.substringAfter(':')))
            }
            // 年份与月份：若选择了具体年月，也一并传递
            filter.tags.find { it.key.startsWith("date-year:") }?.let { t ->
                params.add("year=" + encode(t.key.substringAfter(':')))
            }
            filter.tags.find { it.key.startsWith("date-month:") }?.let { t ->
                params.add("month=" + encode(t.key.substringAfter(':')))
            }
            filter.tags.find { it.key.startsWith("duration:") }?.let { t ->
                params.add("duration=" + encode(t.key.substringAfter(':')))
            }
            // 站点标签（影片属性/人物关系）：多选支持，重复提交 tags[] 参数
            filter.tags.filter { it.key.startsWith("tag:") }.forEach { t ->
                params.add("tags[]=" + encode(t.key.substringAfter(':')))
            }

            // 广泛配对（OR 组合标签）
            if (filter.tags.any { it.key == "broad:on" }) {
                params.add("broad=on")
            }
        }

        // 分頁：search.html 顯示以 page=N 參數控制
        if (page > 1) {
            params.add("page=$page")
        }

        if (params.isNotEmpty()) {
            base.append('?').append(params.joinToString("&"))
        }

        return base.toString()
    }

    private fun sortMapping(order: SortOrder): String? {
        // 不在此调用 suspend，使用静态映射以保证编译安全
        return when (order) {
            SortOrder.ADDED -> "最新上市"
            SortOrder.NEWEST -> "最新上傳"
            SortOrder.POPULARITY_TODAY -> "本日排行"
            SortOrder.POPULARITY_WEEK -> "本週排行"
            SortOrder.POPULARITY_MONTH -> "本月排行"
            SortOrder.POPULARITY_YEAR -> "本年排行"
            SortOrder.POPULARITY -> "他們在看"
            else -> null
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        // Try to pull description and tags from meta and LD+JSON
        val metaDesc = doc.selectFirst("meta[name=description]")?.attrOrNull("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attrOrNull("content")

        val metaImage = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
        val keywordsRaw = doc.selectFirst("meta[name=keywords]")?.attrOrNull("content")

        val tags = if (!keywordsRaw.isNullOrBlank()) {
            keywordsRaw.split(',')
                .mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
                .map { kw ->
                    MangaTag(title = kw.replaceFirstChar { ch -> ch.uppercase() }, key = kw.lowercase(), source = source)
                }
                .toSet()
        } else emptySet()

        // Create a single chapter pointing to the watch page
        val chapter = MangaChapter(
            id = generateUid("${manga.url}|video"),
            url = manga.url,
            title = "Watch",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            description = metaDesc,
            largeCoverUrl = metaImage ?: manga.largeCoverUrl,
            tags = if (manga.tags.isEmpty()) tags else manga.tags,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

        // Try multiple strategies to extract streams
        val fromVideoTag = extractFromVideoTag(doc)
        val fromLdJson = extractFromLdJson(doc)
        val fromRegex = extractByRegex(doc)

        val streams = (fromVideoTag + fromLdJson + fromRegex)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?: return emptyList()

        // Poster for preview, if available
        val poster = doc.selectFirst("video[poster]")?.attrOrNull("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")

        return streams.map { s ->
            MangaPage(
                id = generateUid(s.toRelativeUrl(domain)),
                url = s,
                preview = poster,
                source = source,
            )
        }
    }

    private fun extractFromVideoTag(doc: Document): List<String> {
        val res = ArrayList<String>()
        val video = doc.selectFirst("video")
        if (video != null) {
            // Sources inside video tag
            val sources = doc.select("video source[src]")
            for (src in sources) {
                val u = src.attrOrNull("src")
                if (!u.isNullOrBlank()) {
                    res.add(u)
                }
            }
            // Direct src on <video>
            video.attrOrNull("src")?.let { res.add(it) }
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
                        node.optString("contentUrl").takeIf { it.isNotBlank() }?.let { res.add(it) }
                        // Some sites embed nested graph
                        node.optJSONObject("mainEntity")?.optString("contentUrl")?.takeIf { it.isNotBlank() }?.let(res::add)
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            val obj = node.optJSONObject(i) ?: continue
                            obj.optString("contentUrl").takeIf { it.isNotBlank() }?.let { res.add(it) }
                        }
                    }
                }
            }.getOrElse { /* ignore malformed json */ }
        }
        return res
    }

    private fun extractByRegex(doc: Document): List<String> {
        val res = ArrayList<String>()
        val html = doc.outerHtml()
        // Common patterns for HLS and MP4
        val hls = Regex("https?://[^\"'\\s>]+\\.m3u8", RegexOption.IGNORE_CASE)
        val mp4 = Regex("https?://[^\"'\\s>]+\\.mp4", RegexOption.IGNORE_CASE)
        hls.findAll(html).forEach { m -> res.add(m.value) }
        mp4.findAll(html).forEach { m -> res.add(m.value) }
        return res
    }
}
