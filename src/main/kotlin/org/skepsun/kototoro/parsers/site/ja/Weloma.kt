@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.Base64
import java.util.EnumSet
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * WeLoMa - 日文生肉漫画站
 * 网站: https://weloma.net/（原 weloma.ru 已失联，仅在配置里保留为备选域名）
 *
 * 站点结构（2026-08 重迁后）:
 * - 全站列表/A-Z:  /l/{token}?page=N      （token 固定为站内导航的 0OYCn）
 * - 排序:          ?sort=last_update|most_viewed|most_viewed_today
 * - 搜索:          /l/{token}?name=<query>
 * - 分类/标签:     /l/{genreToken}?page=N
 * - 连载/完结:     /manga-on-going.html / manga-completed.html
 * - 详情页:        /m/{token}             （HTML 渲染，标题在面包屑、封面 img.thumbnail）
 * - 章节页:        /c/{token}             （图片地址 base64 编码在 img.chapter-img[data-img]）
 */
@ContentSourceParser("WELOMA", "Weloma", "ja")
internal class Weloma(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.WELOMA, pageSize = 20) {

    // weloma.ru 已失联（TLS 握手失败），站点迁移到 weloma.net，旧域名保留为备选
    override val configKeyDomain = ConfigKey.Domain("weloma.net", "weloma.ru")

    // 全站"A-Z 列表"的定位 token（站内导航统一使用，作为默认/搜索列表入口）
    private val allListToken = "0OYCn"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_TODAY,
    )

    override val filterCapabilities get() = ContentListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
    )

    init {
        setFirstPage(1)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query
        val url = when {
            query != null -> "/l/$allListToken?name=${query.urlEncoded()}&page=$page"
            filter.tags.isNotEmpty() -> {
                // 分类页：tag key 即 /l/{genreToken}
                val token = filter.tags.firstOrNull()?.key
                "/l/$token?page=$page"
            }
            filter.states.isNotEmpty() -> {
                // 连载/完结有独立列表页
                when {
                    filter.states.contains(ContentState.ONGOING) && !filter.states.contains(ContentState.FINISHED) ->
                        "/manga-on-going.html?page=$page"
                    filter.states.contains(ContentState.FINISHED) && !filter.states.contains(ContentState.ONGOING) ->
                        "/manga-completed.html?page=$page"
                    else -> "/l/$allListToken?page=$page"
                }
            }
            else -> {
                // 默认列表；站点排序参数映射
                val sort = when (order) {
                    SortOrder.POPULARITY -> "most_viewed"
                    SortOrder.POPULARITY_TODAY -> "most_viewed_today"
                    else -> "" // UPDATED
                }
                val sortParam = if (sort.isEmpty()) "last_update" else sort
                "/l/$allListToken?sort=$sortParam&page=$page"
            }
        }
        val doc = webClient.httpGet(("https://$domain$url").toHttpUrl()).parseHtml()
        return parseList(doc)
    }

    // 站点标题常带 "- RAW"/"- Raw" 生肉标记，列表与详情大小写不一致，统一去掉以便匹配
    private fun normalizeTitle(raw: String): String =
        raw.trim().replace(Regex("""\s*[-–—]\s*raw\s*$""", RegexOption.IGNORE_CASE), "")

    // 详情面包屑是列表标题的全大写变体时视为同一个标题
    private fun isSameTitle(listTitle: String, detailTitle: String): Boolean =
        listTitle.isNotEmpty() && listTitle.equals(detailTitle, ignoreCase = true)

    private fun parseList(doc: Document): List<Content> {
        val result = ArrayList<Content>()
        // 列表卡片容器是 .thumb-item-flow；标题 .series-title 在 .thumb-wrapper 之外（兄弟节点）
        for (item in doc.select("div.thumb-item-flow")) {
            val link = item.selectFirst(".series-title a[href*=/m/]")
                ?: item.selectFirst("a[href*=/m/]") ?: continue
            val href = link.attr("href")
            val token = href.substringAfter("/m/").trim('/')
            if (token.isEmpty()) continue
            val title = normalizeTitle(link.attr("title").ifEmpty { link.text().trim() })
            if (title.isEmpty()) continue

            val coverUrl = item.selectFirst(".img-in-ratio")?.attr("style")
                ?.let { parseBackgroundImage(it) }

            result += Content(
                id = generateUid("/m/$token"),
                title = title,
                altTitles = emptySet(),
                url = "/m/$token",
                publicUrl = "https://$domain/m/$token",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
        return result
    }

    private fun parseBackgroundImage(style: String): String? {
        return Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""").find(style)?.groupValues?.get(1)
    }

    override suspend fun getDetails(manga: Content): Content {
        val url = "https://$domain${manga.url}"
        val doc = webClient.httpGet(url.toHttpUrl()).parseHtml()

        // 标题在面包屑: <li class="breadcrumb-item active">SATANOPHANY - RAW</li>
        // 详情页标题是全大写变体，若与列表标题仅大小写/生肉后缀不同，保留列表的正常大小写
        val breadcrumb = doc.selectFirst("li.breadcrumb-item.active")?.text()?.trim()
            ?.let { normalizeTitle(it) }?.ifEmpty { null }
        val title = when {
            breadcrumb.isNullOrEmpty() -> manga.title
            isSameTitle(manga.title, breadcrumb) -> manga.title
            else -> breadcrumb
        }

        // 简介 HTML 被转义成可见文本（形如 "<p>Updating</p>"），重新解析去掉包裹标签
        val description = doc.selectFirst(".summary-content p")?.let { p ->
            Jsoup.parseBodyFragment(p.html()).text().trim().ifEmpty { null }
        }

        val coverUrl = doc.selectFirst("img.thumbnail")?.attr("src")?.ifEmpty { null }
            ?: manga.coverUrl

        // 作者: <b> Author(s)</b> 所在 li 内的链接
        val authors = doc.select("b:containsOwn(Author)").firstOrNull()?.closest("li")
            ?.select("a")?.mapNotNull { it.text().trim().ifEmpty { null } }?.toSet()
            ?: emptySet()

        // 分类: <b> Genre(s)</b> 所在 li 内的 /l/{token} 链接
        val tags = doc.select("b:containsOwn(Genre)").firstOrNull()?.closest("li")
            ?.select("a")?.mapNotNull { link ->
                val href = link.attr("href")
                val name = link.text().trim()
                if (href.startsWith("/l/") && name.isNotEmpty()) {
                    ContentTag(name, href.substringAfter("/l/").trim('/'), source)
                } else {
                    null
                }
            }?.toSet() ?: emptySet()

        // 状态: <b> Status</b> 所在 li 的链接文本（"On going" / "Completed"）
        val state = doc.select("b:containsOwn(Status)").firstOrNull()?.closest("li")
            ?.selectFirst("a")?.text()?.lowercase()?.let {
                when {
                    "going" in it -> ContentState.ONGOING
                    "finish" in it || "complete" in it -> ContentState.FINISHED
                    else -> null
                }
            }

        // 章节: ul.list-chapters 内 /c/{token}，标题形如 "Chapter 338" / "Chap 73.1"
        val chapters = doc.select("ul.list-chapters.at-series a[href^=/c/]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val token = href.substringAfter("/c/").trim('/')
                if (token.isEmpty()) return@mapNotNull null
                val number = Regex("""(\d+(?:\.\d+)?)""")
                    .find(a.attr("title"))?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                ContentChapter(
                    id = generateUid("/c/$token"),
                    title = null, // 交给应用按 number 格式化
                    number = number,
                    volume = 0,
                    url = "/c/$token",
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }
            .sortedBy { it.number } // 页面按最新在前，阅读时按正序

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            state = state,
            tags = tags,
            authors = authors,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val url = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(url.toHttpUrl()).parseHtml()

        // 每页图片的真实地址 base64 编码在 img.chapter-img[data-img]（JS 用 atob 解码后加载）
        val images = doc.select("img.chapter-img[data-img]")
        if (images.isEmpty()) {
            throw ParseException("章节页未找到图片", url)
        }
        return images.mapIndexed { index, img ->
            val encoded = img.attr("data-img")
            val realUrl = try {
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw ParseException("章节图片 data-img 解码失败", chapter.url)
            }
            ContentPage(
                id = generateUid("${chapter.url}-$index"),
                url = realUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableTags = BUILTIN_TAGS,
            tagGroups = listOf(
                ContentTagGroup(
                    title = translateText("ジャンル", "标签"),
                    tags = BUILTIN_TAGS,
                ),
            ),
            availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
            availableContentRating = emptySet(),
        )
    }

    // 站点声明了 /favicon.ico 但实际 404，覆盖为无图标，避免无效探测
    public override suspend fun getFavicons(): Favicons = Favicons(emptyList(), null)

    private fun translateTag(name: String): String {
        if (context.getPreferredLocales().firstOrNull()?.language == "zh") {
            return tagTranslations[name] ?: name
        }
        return name
    }

    private fun translateText(ja: String, zh: String): String {
        return if (context.getPreferredLocales().firstOrNull()?.language == "zh") zh else ja
    }

    // 常用分类（token 取自站内 /l/{token} 分类链接）；站点还提供更多细分分类，可按需补充
    private val BUILTIN_TAGS: Set<ContentTag> = linkedSetOf(
        "Action" to "0zkHB",
        "Adult" to "0zkRl",
        "Adventure" to "0zkWS",
        "Comedy" to "0zkPw",
        "Drama" to "0zkUH",
        "Ecchi" to "0zkbr",
        "Fantasy" to "0zkxQ",
        "Gender Bender" to "0zk3Y",
        "Harem" to "0zk47",
        "Historical" to "0zkpP",
        "Horror" to "0zko4",
        "Martial Arts" to "0zgfZ",
        "Mature" to "0zkC2",
        "Mecha" to "0zkDa",
        "Medical" to "0zkdy",
        "Mystery" to "0zkAZ",
        "Psychological" to "0zjfN",
        "Romance" to "0zjas",
        "School Life" to "0zjlD",
        "Sci-fi" to "0zjhh",
        "Seinen" to "0zk9J",
        "Shoujo" to "0zk05",
        "Shounen" to "0zkr9",
        "Slice of Life" to "0zjJE",
        "Smut" to "0zjcb",
        "Sports" to "0zjYu",
        "Supernatural" to "0zjEU",
        "Thriller" to "0zlX0",
        "Tragedy" to "0zjzo",
        "Yuri" to "0zjIz",
        "Yaoi" to "0zzqA",
        "Shoujo Ai" to "0zlwp",
        "Shounen Ai" to "0zjQt",
        "Josei" to "0zjnX",
        "Lolicon" to "0zhor",
        "Shotacon" to "0zlnM",
        "Magic" to "0zlzC",
        "Cooking" to "0zkmT",
        "Isekai" to "0zlfF",
        "Reincarnation" to "0zlQ3",
        "Regression" to "0zh4w",
        "Revenge" to "0zlIn",
        "Dungeon" to "0zglB",
        "Game Lit" to "0zh9S",
        "VRMMO" to "0zxiN",
        "MMORPG" to "0zbWw",
        "Time Travel" to "0zsKU",
        "Time Loop" to "0zjGe",
        "Vampire" to "8YvPR",
        "Ninja" to "VDCkp",
        "Yakuza" to "rEzd",
        "Police" to "0zsk5",
        "Detective" to "0zzvK",
        "Crime" to "0zgn2",
        "Mafia" to "0znV6",
        "Military" to "0zdZv",
        "War" to "0zd12",
        "Super Power" to "0zdw9",
        "Magic School" to "0zfgK",
        "Academy" to "0za1y",
        "Otaku Culture" to "0zgG5",
        "Idol" to "0zfRn",
        "Music" to "0zgJJ",
        "Arts" to "0zg7u",
        "Gourmet" to "0x50e",
        "Business" to "0zlG6",
        "Workplace" to "0zjid",
        "Fashion" to "0zhDc",
        "Crossdressing" to "0zdeW",
        "Tsundere" to "0zx6X",
        "Yandere" to "0zl5c",
        "One Shot" to "0zlaj",
        "Anthology" to "0zlSR",
        "Cyberpunk" to "0zhOk",
        "Post-Apocalyptic" to "0zgSD",
        "Steampunk" to "0zfH3",
        "Monster Girl" to "0znJ1",
        "Beastman" to "0zgav",
        "Animals" to "0zh8l",
        "Baseball" to "0zlik",
        "Basketball" to "0zkki",
    ).map { (name, token) -> ContentTag(translateTag(name), token, source) }.toSet()
}

private val tagTranslations = mapOf(
        "Action" to "动作",
        "Adult" to "成人",
        "Adventure" to "冒险",
        "Comedy" to "喜剧",
        "Drama" to "剧情",
        "Ecchi" to "微H",
        "Fantasy" to "奇幻",
        "Harem" to "后宫",
        "Historical" to "历史",
        "Horror" to "恐怖",
        "Martial Arts" to "武术",
        "Mature" to "成人向",
        "Mecha" to "机甲",
        "Medical" to "医疗",
        "Mystery" to "悬疑",
        "Psychological" to "心理",
        "Romance" to "恋爱",
        "School Life" to "校园",
        "Sci-fi" to "科幻",
        "Seinen" to "青年",
        "Shoujo" to "少女",
        "Shounen" to "少年",
        "Slice of Life" to "日常",
        "Smut" to "肉番",
        "Sports" to "运动",
        "Supernatural" to "超自然",
        "Thriller" to "惊悚",
        "Tragedy" to "悲剧",
        "Yuri" to "百合",
        "Yaoi" to "耽美",
        "Magic" to "魔法",
        "Cooking" to "料理",
        "Isekai" to "异世界",
        "Reincarnation" to "转生",
        "Revenge" to "复仇",
        "Vampire" to "吸血鬼",
        "Ninja" to "忍者",
        "Police" to "警察",
        "Detective" to "侦探",
        "Mafia" to "黑帮",
        "Military" to "军事",
        "War" to "战争",
        "Idol" to "偶像",
        "Music" to "音乐",
        "Crossdressing" to "伪娘",
        "Tsundere" to "傲娇",
        "Yandere" to "病娇",
        "One Shot" to "短篇",
    )
