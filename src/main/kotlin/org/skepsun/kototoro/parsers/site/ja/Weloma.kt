@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("WELOMA", "Weloma", "ja")
internal class Weloma(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WELOMA, pageSize = 40) {

    override val configKeyDomain = ConfigKey.Domain("weloma.ru")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_TODAY
    )

    override val filterCapabilities get() = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
    )

    init {
        setFirstPage(1)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = when {
            filter.query != null -> {
                "https://$domain/spa/search?query=${filter.query.urlEncoded()}&page=$page"
            }
            filter.tags.isNotEmpty() || filter.states.isNotEmpty() || order != SortOrder.UPDATED -> {
                // Genre page uses HTML, not SPA API
                val genrePath = filter.tags.firstOrNull()?.key ?: "all"
                val sort = when (order) {
                    SortOrder.POPULARITY -> "most_viewed"
                    SortOrder.POPULARITY_TODAY -> "most_viewed_today"
                    else -> ""
                }
                val status = when {
                    filter.states.contains(MangaState.ONGOING) -> "ongoing"
                    filter.states.contains(MangaState.FINISHED) -> "completed"
                    else -> ""
                }
                buildString {
                    append("https://$domain/genre/$genrePath")
                    val params = mutableListOf<String>()
                    if (page > 1) params.add("page=$page")
                    if (sort.isNotEmpty()) params.add("sort=$sort")
                    if (status.isNotEmpty()) params.add("status=$status")
                    if (params.isNotEmpty()) {
                        append("?")
                        append(params.joinToString("&"))
                    }
                }
            }
            else -> {
                "https://$domain/spa/latest-manga?page=$page"
            }
        }

        // Check if it's an SPA API endpoint or HTML page
        return if (url.contains("/spa/")) {
            val response = webClient.httpGet(url.toHttpUrl())
            val json = JSONObject(response.parseRaw())
            val mangaArray = json.optJSONArray("manga_list") ?: JSONArray()
            
            val mangaList = mutableListOf<Manga>()
            for (i in 0 until mangaArray.length()) {
                val item = mangaArray.getJSONObject(i)
                mangaList.add(parseMangaFromJson(item))
            }
            mangaList
        } else {
            // Parse HTML genre page
            val doc = webClient.httpGet(url.toHttpUrl()).parseHtml()
            val mangaItems = doc.select("div.genre-manga-list a.manga-item, div.manga-list a[href*=/title/], .manga-card a[href*=/title/], a.manga-poster[href*=/title/]")
            
            mangaItems.mapNotNull { element ->
                val href = element.attr("href")
                val match = Regex("/title/ru(\\d+)").find(href) ?: return@mapNotNull null
                val id = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val title = element.selectFirst("h3, .title, .manga-title, img[alt]")?.let {
                    it.text().ifEmpty { it.attr("alt") }
                } ?: element.attr("title").ifEmpty { "Unknown" }
                val cover = element.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }
                
                Manga(
                    id = id,
                    title = title,
                    altTitles = emptySet(),
                    url = "https://$domain/title/ru$id",
                    publicUrl = "https://$domain/title/ru$id",
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            }
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getLong("manga_id")
        val title = json.getString("manga_name")
        val altTitles = setOfNotNull(json.optString("manga_others_name").ifEmpty { null })
        val cover = json.getString("manga_cover_img")
        
        return Manga(
            id = id,
            title = title,
            altTitles = altTitles,
            url = "https://$domain/title/ru$id",
            publicUrl = "https://$domain/title/ru$id",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain/spa/manga/${manga.id}"
        val response = webClient.httpGet(url.toHttpUrl())
        val json = JSONObject(response.parseRaw())
        
        val detail = json.getJSONObject("detail")
        val title = detail.getString("manga_name")
        val description = detail.optString("manga_description").ifEmpty { null }
        val cover = detail.getString("manga_cover_img")
        val state = if (detail.optInt("manga_status") == 0) MangaState.ONGOING else MangaState.FINISHED
        
        val tagsArray = json.optJSONArray("tags") ?: JSONArray()
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until tagsArray.length()) {
            val tagItem = tagsArray.getJSONObject(i)
            val tagName = tagItem.getString("tag_name")
            tags.add(MangaTag(translateTag(tagName), tagItem.opt("tag_id").toString(), source))
        }

        val authorsArray = json.optJSONArray("authors") ?: JSONArray()
        val authors = mutableSetOf<String>()
        for (i in 0 until authorsArray.length()) {
            authors.add(authorsArray.getJSONObject(i).getString("author_name"))
        }

        val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
        val chaptersList = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val chapterItem = chaptersArray.getJSONObject(i)
            val number = chapterItem.optDouble("chapter_number", 0.0).toFloat()
            val chapterId = chapterItem.optLong("chapter_id", 0L)
            chaptersList.add(
                MangaChapter(
                    id = chapterId,
                    title = null, // Strip .0 by letting the app format it
                    number = number,
                    volume = 0,
                    url = "/reader/ru${manga.id}/chapter-${chapterItem.optString("chapter_number")}",
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            )
        }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            state = state,
            tags = tags,
            authors = authors,
            chapters = chaptersList.sortedBy { it.number } // Ascending order
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val match = Regex("/reader/ru(\\d+)/chapter-([\\d.]+)").find(chapter.url) ?: return emptyList()
        val mangaId = match.groupValues[1]
        val chapterNum = match.groupValues[2]
        
        val url = "https://$domain/spa/manga/$mangaId/$chapterNum"
        val response = webClient.httpGet(url.toHttpUrl())
        val json = JSONObject(response.parseRaw())
        
        val chapterDetail = json.getJSONObject("chapter_detail")
        val contentHtml = chapterDetail.getString("chapter_content")
        val server = chapterDetail.getString("server")
        
        val doc = Jsoup.parseBodyFragment(contentHtml)
        val canvases = doc.select("canvas.lazy")
        
        return canvases.mapIndexed { index, canvas ->
            val dataSrcset = canvas.attr("data-srcset")
            MangaPage(
                id = generateUid("$mangaId-$chapterNum-$index"),
                url = server + dataSrcset,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            tagGroups = listOf(
                MangaTagGroup(
                    title = translateText("ジャンル", "标签"),
                    tags = setOf(
                        // English tags
                        createTag("Adaptions", "t163/Adaptions"),
                        createTag("Animals", "t168/Animals"),
                        createTag("Crime", "t165/Crime"),
                        createTag("Girls' Love", "t167/Girls'%20Love"),
                        createTag("Hentai", "t169/Hentai"),
                        createTag("n/a", "t156/n%2Fa"),
                        createTag("Police", "t166/Police"),
                        createTag("Thriller", "t164/Thriller"),
                        // Japanese tags with URL-encoded names
                        createTag("アクション", "t85/%E3%82%A2%E3%82%AF%E3%82%B7%E3%83%A7%E3%83%B3"),
                        createTag("アダルト", "t139/%E3%82%A2%E3%83%80%E3%83%AB%E3%83%88"),
                        createTag("アニメ化された", "t140/%E3%82%A2%E3%83%8B%E3%83%A1%E5%8C%96%E3%81%95%E3%82%8C%E3%81%9F"),
                        createTag("ウェブトゥーン", "t116/%E3%82%A6%E3%82%A7%E3%83%96%E3%83%88%E3%82%A5%E3%83%BC%E3%83%B3"),
                        createTag("エッチ", "t88/%E3%82%A8%E3%83%83%E3%83%81"),
                        createTag("エルフ", "t150/%E3%82%A8%E3%83%AB%E3%83%95"),
                        createTag("ゲーム", "t155/%E3%82%B2%E3%83%BC%E3%83%A0"),
                        createTag("コメディ", "t87/%E3%82%B3%E3%83%A1%E3%83%87%E3%82%A3"),
                        createTag("サイエンスフィクション", "t122/%E3%82%B5%E3%82%A4%E3%82%A8%E3%83%B3%E3%82%B9%E3%83%95%E3%82%A3%E3%82%AF%E3%82%B7%E3%83%A7%E3%83%B3"),
                        createTag("ショウジョアイ", "t131/%E3%82%B7%E3%83%A7%E3%82%A6%E3%82%B8%E3%83%A7%E3%82%A2%E3%82%A4"),
                        createTag("ショタコン", "t154/%E3%82%B7%E3%83%A7%E3%82%BF%E3%82%B3%E3%83%B3"),
                        createTag("スクールライフ", "t108/%E3%82%B9%E3%82%AF%E3%83%BC%E3%83%AB%E3%83%A9%E3%82%A4%E3%83%95"),
                        createTag("スポーツ", "t124/%E3%82%B9%E3%83%9D%E3%83%BC%E3%83%84"),
                        createTag("スムット", "t123/%E3%82%B9%E3%83%A0%E3%83%83%E3%83%88"),
                        createTag("スライス・オブ・ライフ", "t92/%E3%82%B9%E3%83%A9%E3%82%A4%E3%82%B9%E3%83%BB%E3%82%AA%E3%83%96%E3%83%BB%E3%83%A9%E3%82%A4%E3%83%95"),
                        createTag("トラジディ", "t135/%E3%83%88%E3%83%A9%E3%82%B8%E3%83%87%E3%82%A3"),
                        createTag("トラップ（クロスドレッシング）", "t138/%E3%83%88%E3%83%A9%E3%83%83%E3%83%97%EF%BC%88%E3%82%AF%E3%83%AD%E3%82%B9%E3%83%89%E3%83%AC%E3%83%83%E3%82%B7%E3%83%B3%E3%82%B0%EF%BC%89"),
                        createTag("ドラマ", "t114/%E3%83%89%E3%83%A9%E3%83%9E"),
                        createTag("ハーレム", "t90/%E3%83%8F%E3%83%BC%E3%83%AC%E3%83%A0"),
                        createTag("ファンタジー", "t89/%E3%83%95%E3%82%A1%E3%83%B3%E3%82%BF%E3%82%B8%E3%83%BC"),
                        createTag("ホラー", "t127/%E3%83%9B%E3%83%A9%E3%83%BC"),
                        createTag("マンhua", "t128/%E3%83%9E%E3%83%B3hua"),
                        createTag("マンファ", "t125/%E3%83%9E%E3%83%B3%E3%83%95%E3%82%A1"),
                        createTag("ミステリー", "t121/%E3%83%9F%E3%82%B9%E3%83%86%E3%83%AA%E3%83%BC"),
                        createTag("メカ", "t143/%E3%83%A1%E3%82%AB"),
                        createTag("やおい", "t161/%E3%82%84%E3%81%8A%E3%81%84"),
                        createTag("ロマンス", "t106/%E3%83%AD%E3%83%9E%E3%83%B3%E3%82%B9"),
                        createTag("ロリ", "t91/%E3%83%AD%E3%83%AA"),
                        createTag("ロリコン", "t148/%E3%83%AD%E3%83%AA%E3%82%B3%E3%83%B3"),
                        createTag("ワンショット", "t142/%E3%83%AF%E3%83%B3%E3%82%B7%E3%83%A7%E3%83%83%E3%83%88"),
                        createTag("冒険", "t86/%E5%86%92%E9%99%BA"),
                        createTag("医療", "t132/%E5%8C%BB%E7%99%82"),
                        createTag("女性漫画", "t130/%E5%A5%B3%E6%80%A7%E6%BC%AB%E7%94%BB"),
                        createTag("少女", "t120/%E5%B0%91%E5%A5%B3"),
                        createTag("少年", "t118/%E5%B0%91%E5%B9%B4"),
                        createTag("少年愛", "t109/%E5%B0%91%E5%B9%B4%E6%84%9B"),
                        createTag("心理的", "t119/%E5%BF%83%E7%90%86%E7%9A%84"),
                        createTag("性別転換", "t111/%E6%80%A7%E5%88%A5%E8%BB%A2%E6%8F%9B"),
                        createTag("成熟 (せいじゅく)", "t112/%E6%88%90%E7%86%9F%20(%E3%81%9B%E3%81%84%E3%81%98%E3%82%85%E3%81%8F)"),
                        createTag("戦争", "t153/%E6%88%A6%E4%BA%89"),
                        createTag("料理", "t134/%E6%96%99%E7%90%86"),
                        createTag("更新中", "t147/%E6%9B%B4%E6%96%B0%E4%B8%AD"),
                        createTag("武道", "t126/%E6%AD%A6%E9%81%93"),
                        createTag("歴史", "t115/%E6%AD%B4%E5%8F%B2"),
                        createTag("異世界", "t144/%E7%95%B0%E4%B8%96%E7%95%8C"),
                        createTag("百合", "t110/%E7%99%BE%E5%90%88"),
                        createTag("萌え", "t141/%E8%90%8C%E3%81%88"),
                        createTag("超自然", "t93/%E8%B6%85%E8%87%AA%E7%84%B6"),
                        createTag("青年", "t107/%E9%9D%92%E5%B9%B4"),
                        createTag("食べ物", "t152/%E9%A3%9F%E3%81%B9%E7%89%A9"),
                        createTag("魔法", "t151/%E9%AD%94%E6%B3%95")
                    )
                )
            ),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
        )
    }

    private fun createTag(name: String, key: String): MangaTag {
        return MangaTag(translateTag(name), key, source)
    }

    private fun translateTag(name: String): String {
        if (context.getPreferredLocales().firstOrNull()?.language == "zh") {
            return tagTranslations[name] ?: name
        }
        return name
    }

    private fun translateText(ja: String, zh: String): String {
        if (context.getPreferredLocales().firstOrNull()?.language == "zh") {
            return zh
        }
        return ja
    }

    private val tagTranslations = mapOf(
        "アクション" to "动作",
        "アダルト" to "成人",
        "アニメ化された" to "动画化",
        "ウェブトゥーン" to "韩漫",
        "エッチ" to "微H",
        "エルフ" to "精灵",
        "ゲーム" to "游戏",
        "コメディ" to "喜剧",
        "サイエンスフィクション" to "科幻",
        "ショウジョアイ" to "少女爱",
        "ショタコン" to "正太控",
        "スクールライフ" to "校园",
        "スポーツ" to "运动",
        "スムット" to "肉番",
        "スライス・オブ・ライフ" to "日常",
        "トラジディ" to "悲剧",
        "トラップ（クロスドレッシング）" to "伪娘",
        "ドラマ" to "剧情",
        "ハーレム" to "后宫",
        "ファンタジー" to "奇幻",
        "ホラー" to "恐怖",
        "マンhua" to "国漫",
        "マンファ" to "韩漫",
        "ミステリー" to "悬疑",
        "メカ" to "机甲",
        "やおい" to "耽美",
        "ロマンス" to "罗曼史",
        "ロリ" to "萝莉",
        "ロリコン" to "萝莉控",
        "ワンショット" to "短篇",
        "冒険" to "冒险",
        "医療" to "医疗",
        "女性漫画" to "女性向",
        "少女" to "少女",
        "少年" to "少年",
        "少年愛" to "少年爱",
        "心理的" to "心理",
        "性別転換" to "性转换",
        "成熟 (せいじゅく)" to "成人",
        "戦争" to "战争",
        "料理" to "料理",
        "更新中" to "更新中",
        "武道" to "武道",
        "歴史" to "历史",
        "異世界" to "异世界",
        "百合" to "百合",
        "萌え" to "萌",
        "超自然" to "超自然",
        "青年" to "青年",
        "食べ物" to "食物",
        "魔法" to "魔法"
    )
}
