@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.skepsun.kototoro.parsers.util.json.mapJSONIndexed
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import java.util.EnumSet

/**
 * Comick (comick.art)
 * 基于公开 API + HTML sv-data 解析。
 */
@ContentSourceParser("COMICK", "Comick", "en")
internal class ComickParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.COMICK, pageSize = 20) {

    private val baseUrl = "https://comick.art"
    override val configKeyDomain = ConfigKey.Domain("comick.art")

    private val langNames: Map<String, String> = mapOf(
        "en" to "英文",
        "pt-br" to "巴西葡萄牙文",
        "es-419" to "拉丁美洲西班牙文",
        "ru" to "俄文",
        "vi" to "越南文",
        "fr" to "法文",
        "pl" to "波兰文",
        "id" to "印度尼西亚文",
        "tr" to "土耳其文",
        "it" to "意大利文",
        "es" to "西班牙文",
        "uk" to "乌克兰文",
        "ar" to "阿拉伯文",
        "zh-hk" to "繁体中文",
        "hu" to "匈牙利文",
        "zh" to "中文",
        "de" to "德文",
        "ko" to "韩文",
        "th" to "泰文",
        "bg" to "保加利亚文",
        "ca" to "加泰罗尼亚文",
        "fa" to "波斯文",
        "ro" to "罗马尼亚文",
        "cs" to "捷克文",
        "mn" to "蒙古文",
        "he" to "希伯来文",
        "pt" to "葡萄牙文",
        "hi" to "印地文",
        "tl" to "他加禄文",
        "fi" to "芬兰文",
        "ms" to "马来文",
        "eu" to "巴斯克文",
        "kk" to "哈萨克文",
        "sr" to "塞尔维亚文",
        "my" to "缅甸文",
        "el" to "希腊文",
        "nl" to "荷兰文",
        "ja" to "日文",
        "uz" to "乌兹别克文",
        "eo" to "世界语",
        "bn" to "孟加拉文",
        "lt" to "立陶宛文",
        "ka" to "格鲁吉亚文",
        "da" to "丹麦文",
        "ta" to "泰米尔文",
        "sv" to "瑞典文",
        "be" to "白俄罗斯文",
        "cv" to "楚瓦什文",
        "hr" to "克罗地亚文",
        "la" to "拉丁文",
        "ne" to "尼泊尔文",
        "ur" to "乌尔都文",
        "gl" to "加利西亚文",
        "no" to "挪威文",
        "sq" to "阿尔巴尼亚文",
        "ga" to "爱尔兰文",
        "te" to "泰卢固文",
        "jv" to "爪哇文",
        "sl" to "斯洛文尼亚文",
        "et" to "爱沙尼亚文",
        "az" to "阿塞拜疆文",
        "sk" to "斯洛伐克文",
        "af" to "南非荷兰文",
        "lv" to "拉脱维亚文",
    )

    private val tagDict: Map<String, String> = mapOf(
        "romance" to "浪漫",
        "comedy" to "喜剧",
        "drama" to "剧情",
        "fantasy" to "奇幻",
        "slice-of-life" to "日常",
        "action" to "动作",
        "adventure" to "冒险",
        "psychological" to "心理",
        "mystery" to "悬疑",
        "historical" to "历史",
        "tragedy" to "悲剧",
        "sci-fi" to "科幻",
        "horror" to "恐怖",
        "isekai" to "异世界",
        "sports" to "运动",
        "thriller" to "惊悚",
        "mecha" to "机甲",
        "philosophical" to "哲学",
        "wuxia" to "武侠",
        "medical" to "医疗",
        "magical-girls" to "魔法少女",
        "superhero" to "超级英雄",
        "shounen-ai" to "少年爱",
        "mature" to "成年",
        "gender-bender" to "性转",
        "shoujo-ai" to "少女爱",
        "oneshot" to "单篇",
        "web-comic" to "网络漫画",
        "doujinshi" to "同人志",
        "full-color" to "全彩",
        "long-strip" to "长条",
        "adaptation" to "改编",
        "anthology" to "选集",
        "4-koma" to "四格",
        "user-created" to "用户创作",
        "award-winning" to "获奖",
        "official-colored" to "官方上色",
        "fan-colored" to "粉丝上色",
        "school-life" to "校园生活",
        "supernatural" to "超自然",
        "magic" to "魔法",
        "monsters" to "怪物",
        "martial-arts" to "武术",
        "animals" to "动物",
        "demons" to "恶魔",
        "harem" to "后宫",
        "reincarnation" to "转生",
        "office-workers" to "上班族",
        "survival" to "生存",
        "military" to "军事",
        "crossdressing" to "女装",
        "loli" to "萝莉",
        "shota" to "正太",
        "yuri" to "百合",
        "yaoi" to "耽美",
        "video-games" to "电子游戏",
        "monster-girls" to "魔物娘",
        "delinquents" to "不良少年",
        "ghosts" to "幽灵",
        "time-travel" to "时间旅行",
        "cooking" to "烹饪",
        "police" to "警察",
        "aliens" to "外星人",
        "music" to "音乐",
        "mafia" to "黑帮",
        "vampires" to "吸血鬼",
        "samurai" to "武士",
        "post-apocalyptic" to "后末日",
        "gyaru" to "辣妹",
        "villainess" to "恶役千金",
        "reverse-harem" to "逆后宫",
        "ninja" to "忍者",
        "zombies" to "僵尸",
        "traditional-games" to "传统游戏",
        "virtual-reality" to "虚拟现实",
        "adult" to "成人",
        "ecchi" to "情色",
        "sexual-violence" to "性暴力",
        "smut" to "肉欲",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val genreTags = tagDict.entries.map { ContentTag(title = it.value, key = it.key, source = source) }.toSet()
        return ContentListFilterOptions(
            availableTags = genreTags,
            tagGroups = listOf(ContentTagGroup("类型", genreTags)),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        val genre = filter.tags.firstOrNull()?.key ?: ""
        val orderParam = when (order) {
            SortOrder.POPULARITY -> "follow" // Hot
            SortOrder.UPDATED -> "uploaded"
            SortOrder.NEWEST -> "created_at"
            SortOrder.RATING -> "rating"
            else -> "uploaded"
        }
        val url = buildString {
            append("$baseUrl/api/search?")
            append("page=").append(page)
            append("&sort=").append(orderParam.urlEncoded())
            append("&order=desc")
            if (genre.isNotEmpty()) append("&genres=").append(genre.urlEncoded())
            append("&tachiyomi=true")
        }
        return fetchSearchApi(url)
    }

    private suspend fun search(query: String, page: Int): List<Content> {
        val url = "$baseUrl/api/search?q=${query.urlEncoded()}&page=$page"
        return fetchSearchApi(url)
    }

    private suspend fun fetchSearchApi(url: String): List<Content> {
        val res = webClient.httpGet(url, getRequestHeaders())
        if (!res.isSuccessful) return emptyList()
        val root = res.parseJsonObject()
        val data = root.optJSONArray("comic") ?: root.optJSONArray("data") ?: return emptyList()
        return data.mapJSONIndexed { _, obj ->
            val item = obj as? JSONObject ?: return@mapJSONIndexed null
            val relates = item.optJSONObject("relates")
            val target = relates ?: item
            val hid = target.optString("hid").ifEmpty { target.optString("id") }.ifEmpty { item.optString("slug") }
            val slug = target.optString("slug").ifEmpty { hid }
            val title = target.optString("title").ifEmpty { item.optString("title") }
            val cover = resolveCover(target, slug)
            val tags = (target.optJSONArray("md_comics_md_genres") ?: org.json.JSONArray()).mapJSONIndexed { _, g ->
                val genreObj = (g as? JSONObject)?.optJSONObject("md_genres")
                val slugTag = genreObj?.optString("slug").orEmpty()
                val name = tagDict[slugTag] ?: genreObj?.optString("name").orEmpty()
                if (slugTag.isNotEmpty()) ContentTag(name.ifEmpty { slugTag }, slugTag, source) else null
            }.filterNotNull().toSet()
            Content(
                id = generateUid(slug),
                url = slug,
                publicUrl = "$baseUrl/comic/$slug",
                coverUrl = cover ?: "https://comick.art/images/default-thumbnail.webp",
                title = title,
                altTitles = emptySet(),
                rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                tags = tags,
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }.filterNotNull()
    }

    override suspend fun getDetails(manga: Content): Content {
        val res = webClient.httpGet("$baseUrl/comic/${manga.url}", getRequestHeaders())
        if (!res.isSuccessful) return manga
        val doc = res.parseHtml()
        val sv = doc.getElementById("comic-data")?.data() ?: return manga
        val comicData = runCatching { JSONObject(sv) }.getOrNull() ?: return manga
        val title = comicData.optString("title", manga.title).ifEmpty { manga.title }
        val cover = resolveCover(comicData, manga.url) ?: manga.coverUrl
        val status = comicData.optString("status")
        val authorsArr = comicData.optJSONArray("authors") ?: org.json.JSONArray()
        val authors = authorsArr.mapJSONIndexed { _, a ->
            (a as? JSONObject)?.optString("name").orEmpty().ifEmpty { null }
        }.filterNotNull().toSet()

        val tags = extractTags(comicData).toSet()

        val chaptersAndTime = runCatching { loadChapters(manga.url, comicData) }.getOrNull()
        val chapters = chaptersAndTime?.first ?: emptyMap<String, List<ContentChapter>>()
        val updateInfo = chaptersAndTime?.second ?: comicData.optString("last_chapter")

        return manga.copy(
            title = title,
            coverUrl = cover,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters.values.flatten(),
            description = comicData.optString("desc", manga.description ?: ""),
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    private fun extractTags(comicData: JSONObject): Set<ContentTag> {
        val genres = comicData.optJSONArray("md_comic_md_genres") ?: return emptySet()
        return genres.mapJSONIndexed { _, g ->
            val slug = (g as? JSONObject)?.optJSONObject("md_genres")?.optString("slug").orEmpty()
            if (slug.isEmpty()) return@mapJSONIndexed null
            val name = tagDict[slug] ?: slug
            ContentTag(name, slug, source)
        }.filterNotNull().toSet()
    }

    private suspend fun loadChapters(slug: String, comicData: JSONObject): Pair<Map<String, List<ContentChapter>>, String> {
        val langBuckets = LinkedHashMap<String, MutableList<JSONObject>>()
        var latestTimestamp: String? = null
        var page = 1
        var lastPage = 1
        while (page <= lastPage) {
            val url = "$baseUrl/api/comics/$slug/chapter-list?page=$page"
            val res = webClient.httpGet(url, getRequestHeaders())
            if (!res.isSuccessful) break
            val payload = res.parseJsonObject()
            val dataArr = payload.optJSONArray("data") ?: org.json.JSONArray()
            if (page == 1 && dataArr.length() > 0) {
                val first = dataArr.optJSONObject(0)
                latestTimestamp = first?.optString("updated_at")
                    ?: first?.optString("publish_at")
                    ?: first?.optString("created_at")
            }
            dataArr.mapJSONIndexed { _, any ->
                val obj = any as? JSONObject ?: return@mapJSONIndexed null
                val lang = obj.optString("lang", "unknown")
                langBuckets.getOrPut(lang) { mutableListOf() }.add(obj)
            }
            val pagination = payload.optJSONObject("pagination")
            val lp = pagination?.optString("last_page")?.toIntOrNull()
            if (lp != null && lp > 0) lastPage = lp
            page++
        }

        val grouped = LinkedHashMap<String, List<ContentChapter>>()
        langBuckets.forEach { (lang, items) ->
            val langLabel = langNames[lang.lowercase()]?.let { "$it ($lang)" } ?: lang
            val chapters = items.reversed().mapIndexedNotNull { idx, obj ->
                val hid = obj.optString("hid").ifEmpty { return@mapIndexedNotNull null }
                val chap = obj.optString("chap")
                val vol = obj.optString("vol")
                val keyType = when {
                    chap.isNotEmpty() -> "chapter"
                    vol.isNotEmpty() -> "volume"
                    else -> "no"
                }
                val label = when (keyType) {
                    "chapter" -> "第$chap 话"
                    "volume" -> "第$vol 卷"
                    else -> obj.optString("title").ifEmpty { "无标卷" }
                }
                val url = listOf(slug, hid, keyType, chap.ifEmpty { "-1" }, lang).joinToString("|")
                ContentChapter(
                    id = generateUid("$slug-$hid-$lang-$idx"),
                    url = url,
                    title = label,
                    number = (idx + 1).toFloat(),
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0,
                    branch = langLabel,
                    source = source,
                )
            }
            grouped[langLabel] = chapters
        }

        val updateStr = latestTimestamp ?: comicData.optString("last_chapter", "")
        return grouped to updateStr
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val parts = chapter.url.split("|")
        if (parts.size < 5) return emptyList()
        val slug = parts[0]
        val hid = parts[1]
        val type = parts[2]
        val chapterNo = parts[3]
        val lang = parts[4]
        val url = if (type == "no") {
            "$baseUrl/comic/$slug/$hid"
        } else {
            "$baseUrl/comic/$slug/$hid-$type-$chapterNo-$lang"
        }
        val pages = mutableListOf<ContentPage>()
        var nextUrl: String? = url
        var attempts = 0
        while (nextUrl != null && attempts < 50) {
            val res = webClient.httpGet(nextUrl, getRequestHeaders())
            if (!res.isSuccessful) break
            val doc = res.parseHtml()
            val sv = doc.getElementById("sv-data")?.data() ?: break
            val json = runCatching { JSONObject(sv) }.getOrNull() ?: break
            val images = json.optJSONObject("chapter")?.optJSONArray("images") ?: org.json.JSONArray()
            images.mapJSONIndexed { _, img ->
                val obj = img as? JSONObject ?: return@mapJSONIndexed null
                val imgUrl = obj.optString("url")
                if (imgUrl.isNotEmpty()) {
                    pages.add(
                        ContentPage(
                            id = generateUid("$imgUrl-${pages.size}"),
                            url = imgUrl,
                            preview = imgUrl,
                            source = source,
                        )
                    )
                }
            }
            nextUrl = findNextPage(doc)
            attempts++
        }
        return pages
    }

    private fun findNextPage(doc: Document): String? {
        val a = doc.selectFirst("a#next-chapter") ?: return null
        val text = a.text()
        if (text.contains("下一页") || text.contains("下一頁")) {
            return a.attr("href").toAbsoluteUrl("comick.art")
        }
        return null
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private fun resolveCover(item: JSONObject, slug: String? = null): String? {
        val direct = item.optString("default_thumbnail")
            .ifEmpty { item.optString("full_image_path") }
            .ifEmpty { item.optString("cover_url") }
            .ifEmpty { item.optString("md_cover_url") }
            .ifEmpty { item.optString("md_covers_url") }
        if (direct.isNotEmpty()) return normalizeCover(direct)

        val arr = item.optJSONArray("md_covers")
        if (arr != null && arr.length() > 0) {
            val first = arr.optJSONObject(0)
            val b2 = first?.optString("b2key").orEmpty()
            if (b2.isNotEmpty()) {
                return if (!slug.isNullOrEmpty()) {
                    "https://cdn1.comicknew.pictures/$slug/covers/$b2"
                } else {
                    buildMeoCover(b2)
                }
            }
        }
        return null
    }

    private fun normalizeCover(path: String): String {
        if (path.startsWith("http", true)) return path
        if (path.startsWith("//")) return "https:$path"
        if (path.startsWith("/")) return "https://meo.comick.pictures$path"
        return buildMeoCover(path)
    }

    private fun buildMeoCover(key: String): String {
        val cleaned = key.removePrefix("/")
        val hasExt = cleaned.endsWith(".jpg", true) || cleaned.endsWith(".jpeg", true) ||
            cleaned.endsWith(".png", true) || cleaned.endsWith(".webp", true)
        return if (hasExt) {
            "https://meo.comick.pictures/$cleaned"
        } else {
            "https://meo.comick.pictures/$cleaned.jpg"
        }
    }
}
