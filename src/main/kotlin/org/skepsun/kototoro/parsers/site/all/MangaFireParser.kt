@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.YEAR_UNKNOWN
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.util.json.*
import java.util.EnumSet
import kotlin.runCatching

/**
 * MangaFire (mangafire.to) — 基于新 REST API。
 *
 * API 端点:
 * - GET /api/titles        — 列表/搜索
 * - GET /api/titles/{hid}  — 详情
 * - GET /api/titles/{hid}/chapters — 章节列表
 * - GET /api/chapters/{id} — 页面列表
 * - GET /api/tags          — 标签搜索（作者/画师）
 *
 * 参考: keiyoushi/extensions-source commit f91a65fb3
 */
internal abstract class MangaFireParser(
    context: ContentLoaderContext,
    source: ContentSource,
    private val langCode: String,
) : PagedContentParser(
    context = context,
    source = source,
    pageSize = 50,
) {

    override val configKeyDomain = ConfigKey.Domain("mangafire.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Referer", "https://$domain/")
        .add("Accept", "application/json")
        .build()

    // ============================== Sort Orders ==============================

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RELEVANCE,
    )

    override val defaultSortOrder: SortOrder = SortOrder.RELEVANCE

    // ============================== Filter Capabilities ==============================

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearRangeSupported = true,
            isAuthorSearchSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableTags = genres.toSet(),
        availableStates = EnumSet.of(
            ContentState.ONGOING,
            ContentState.FINISHED,
            ContentState.PAUSED,
            ContentState.ABANDONED,
            ContentState.UPCOMING,
        ),
    )

    // ============================== List / Search ==============================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // 先解析作者ID（如果有的话）
        var authorId: String? = null
        if (!filter.author.isNullOrEmpty()) {
            authorId = resolveAuthorId(filter.author)
            if (authorId == null) {
                return emptyList()
            }
        }

        val url = "https://$domain/api/titles".toHttpUrl().newBuilder().apply {
            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("keyword", filter.query)
            }

            addQueryParameter("page", page.toString())
            addQueryParameter("limit", pageSize.toString())

            val (sortField, sortDir) = order.toApiSort()
            addQueryParameter("order[$sortField]", sortDir)

            // 类型过滤
            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { tag ->
                    addQueryParameter("genres_in[]", tag.key)
                }
            }
            if (filter.tagsExclude.isNotEmpty()) {
                filter.tagsExclude.forEach { tag ->
                    addQueryParameter("genres_ex[]", tag.key)
                }
            }
            addQueryParameter("genres_mode", "or")

            // 状态过滤
            if (filter.states.isNotEmpty()) {
                filter.states.forEach { state ->
                    val apiState = state.toApiStatus()
                    if (apiState != null) {
                        addQueryParameter("statuses[]", apiState)
                    }
                }
            }

            // 年份范围
            if (filter.yearFrom != YEAR_UNKNOWN) {
                addQueryParameter("year_from", filter.yearFrom.toString())
            }
            if (filter.yearTo != YEAR_UNKNOWN) {
                addQueryParameter("year_to", filter.yearTo.toString())
            }

            // 作者过滤
            if (authorId != null) {
                addQueryParameter("authors[]", authorId)
            }
        }.build()

        val json = webClient.httpGet(url).parseJson()
        val items = json.optJSONArray("items") ?: return emptyList()

        return items.mapJSON { item -> item.parseMangaListItem() }
    }

    private fun JSONObject.parseMangaListItem(): Content {
        val hid = getString("hid")
        val slug = optString("slug", "").takeIf { it.isNotEmpty() }
        val title = getString("title")
        val poster = optJSONObject("poster")
        val coverUrl = poster?.let {
            it.optString("large", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("medium", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("small", "").takeIf { s -> s.isNotEmpty() }
        }
        val mangaUrl = if (slug != null) "/title/$hid-$slug" else "/title/$hid"

        return Content(
            id = generateUid(mangaUrl),
            title = title,
            altTitles = emptySet(),
            url = mangaUrl,
            publicUrl = "https://$domain$mangaUrl",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = coverUrl,
            largeCoverUrl = null,
            description = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    // ============================== Details ==============================

    override suspend fun getDetails(manga: Content): Content {
        val hid = getHid(manga.url)
        val json = webClient.httpGet("https://$domain/api/titles/$hid").parseJson()
        val data = json.getJSONObject("data")

        val title = data.getString("title")
        val poster = data.optJSONObject("poster")
        val coverUrl = poster?.let {
            it.optString("large", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("medium", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("small", "").takeIf { s -> s.isNotEmpty() }
        }
        val synopsisHtml = data.optString("synopsisHtml", "").takeIf { it.isNotEmpty() }
        val status = data.optString("status", "").takeIf { it.isNotEmpty() }

        val authors = data.optJSONArray("authors")?.mapJSON { it.getString("title") }?.toSet().orEmpty()
        val artists = data.optJSONArray("artists")?.mapJSON { it.getString("title") }?.toSet().orEmpty()

        val genreTags = data.optJSONArray("genres")?.mapJSONNotNull { genre ->
            val genreTitle = genre.getString("title")
            ContentTag(
                title = genreTitle.replaceFirstChar { it.uppercase() },
                key = genreTitle,
                source = source,
            )
        }.orEmpty()

        val themeTags = data.optJSONArray("themes")?.mapJSONNotNull { theme ->
            val themeTitle = theme.getString("title")
            ContentTag(
                title = themeTitle.replaceFirstChar { it.uppercase() },
                key = themeTitle,
                source = source,
            )
        }.orEmpty()

        val allTags = (genreTags + themeTags).toSet()
        val state = status?.let { parseStatus(it) }
        val description = synopsisHtml?.let { Jsoup.parseBodyFragment(it).text() }
        val chapters = loadChapters(manga.url)

        return manga.copy(
            title = title,
            coverUrl = coverUrl ?: manga.coverUrl,
            largeCoverUrl = coverUrl,
            description = description ?: manga.description,
            tags = if (allTags.isNotEmpty()) allTags else manga.tags,
            state = state ?: manga.state,
            authors = (authors + artists).ifEmpty { manga.authors },
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    // ============================== Chapters ==============================

    private suspend fun loadChapters(mangaUrl: String): List<ContentChapter> {
        val hid = getHid(mangaUrl)
        val chapters = mutableListOf<ContentChapter>()
        var page = 1
        var lastPage = 1

        do {
            val url = "https://$domain/api/titles/$hid/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("language", langCode)
                .addQueryParameter("sort", "number")
                .addQueryParameter("order", "desc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", "200")
                .build()

            val json = webClient.httpGet(url).parseJson()
            val items = json.optJSONArray("items") ?: break
            val meta = json.optJSONObject("meta")
            lastPage = meta?.optInt("lastPage", 1) ?: 1

            items.mapJSON { ch ->
                val id = ch.getInt("id")
                val number = ch.getDouble("number").toFloat()
                val name = ch.optString("name", "").takeIf { it.isNotEmpty() }
                val createdAt = ch.getLongOrDefault("createdAt", 0L)

                val chapterUrl = "$mangaUrl/$id-chapter-${number.toDisplayString()}-$langCode"

                chapters.add(
                    ContentChapter(
                        id = generateUid(chapterUrl),
                        title = buildString {
                            append("Ch. ")
                            append(number.toDisplayString())
                            if (name != null) {
                                append(" - ")
                                append(name)
                            }
                        },
                        number = number,
                        volume = 0,
                        url = chapterUrl,
                        scanlator = null,
                        uploadDate = createdAt * 1000L,
                        branch = null,
                        source = source,
                    ),
                )
            }

            page++
        } while (page <= lastPage)

        return chapters
    }

    // ============================== Pages ==============================

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val json = webClient.httpGet("https://$domain/api/chapters/$chapterId").parseJson()
        val data = json.getJSONObject("data")
        val pages = data.getJSONArray("pages")

        return pages.mapJSONIndexed { index, page ->
            val url = page.getString("url")
            ContentPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    // ============================== Utilities ==============================

    private fun getHid(url: String): String {
        val lastPart = url.removeSuffix("/").substringAfterLast("/")
        return when {
            lastPart.contains(".") -> lastPart.substringAfterLast(".")
            lastPart.contains("-") -> lastPart.substringBefore("-")
            else -> lastPart
        }
    }

    private fun SortOrder.toApiSort(): Pair<String, String> = when (this) {
        SortOrder.UPDATED -> "chapter_updated_at" to "desc"
        SortOrder.POPULARITY -> "views_total" to "desc"
        SortOrder.POPULARITY_WEEK -> "views_7d" to "desc"
        SortOrder.POPULARITY_MONTH -> "views_30d" to "desc"
        SortOrder.RATING -> "score" to "desc"
        SortOrder.NEWEST -> "created_at" to "desc"
        SortOrder.ALPHABETICAL -> "title" to "asc"
        SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
        SortOrder.RELEVANCE -> "relevance" to "desc"
        else -> "chapter_updated_at" to "desc"
    }

    private fun ContentState.toApiStatus(): String? = when (this) {
        ContentState.ONGOING -> "releasing"
        ContentState.FINISHED -> "finished"
        ContentState.PAUSED -> "on_hiatus"
        ContentState.ABANDONED -> "discontinued"
        ContentState.UPCOMING -> "not_yet_released"
        else -> null
    }

    private fun parseStatus(status: String): ContentState? = when (status.lowercase()) {
        "releasing" -> ContentState.ONGOING
        "finished" -> ContentState.FINISHED
        "on_hiatus" -> ContentState.PAUSED
        "discontinued" -> ContentState.ABANDONED
        else -> null
    }

    private suspend fun resolveAuthorId(authorQuery: String): String? {
        val url = "https://$domain/api/tags?keyword=${authorQuery.urlEncoded()}"
        val json = runCatching { webClient.httpGet(url).parseJson() }.getOrNull() ?: return null
        val data = json.optJSONArray("data") ?: return null
        return data.mapJSONNotNull { tag ->
            val type = tag.optString("type", "")
            if (type == "author" || type == "artist") {
                tag.getIntOrDefault("id", 0).takeIf { it > 0 }?.toString()
            } else null
        }.firstOrNull()
    }

    private fun Float.toDisplayString(): String {
        val str = toString()
        return if (str.endsWith(".0")) str.removeSuffix(".0") else str
    }

    // ============================== Genres ==============================

    private val genres: List<ContentTag> = listOf(
        "1" to "Action",
        "78" to "Adventure",
        "90" to "Boys Love",
        "89" to "Girls Love",
        "77" to "Comedy",
        "83" to "Drama",
        "92" to "Fantasy",
        "91" to "Hentai",
        "10" to "Horror",
        "93" to "Ecchi",
        "11" to "Mecha",
        "82" to "Mystery",
        "9" to "Psychological",
        "79" to "Romance",
        "3" to "Sci-Fi",
        "81" to "Slice of Life",
        "8" to "Sports",
        "7" to "Supernatural",
        "17" to "Martial Arts",
        "85" to "Historical",
        "80" to "Isekai",
        "84" to "Medical",
        "88" to "Music",
        "86" to "Philosophical",
        "87" to "Tragedy",
        "19" to "Demons",
        "22" to "Magic",
        "21" to "Monsters",
        "18" to "Samurai",
        "20" to "Vampires",
        "14" to "Harem",
        "76" to "Reverse Harem",
        "6" to "School Life",
        "13" to "Shoujo Ai",
        "12" to "Shounen Ai",
        "15" to "Yuri",
        "16" to "Yaoi",
        "5" to "Gender Bender",
        "42" to "Josei",
        "41" to "Seinen",
        "40" to "Shoujo",
        "4" to "Shounen",
        "25" to "Cooking",
        "26" to "Crossdressing",
        "28" to "Delinquents",
        "27" to "Gyaru",
        "29" to "Loli",
        "30" to "Mafia",
        "31" to "Military",
        "32" to "Monster Girls",
        "33" to "Shota",
        "34" to "Survival",
        "35" to "Time Travel",
        "36" to "Video Games",
        "37" to "Villainess",
        "38" to "Zombies",
        "39" to "Animals",
        "43" to "Aliens",
        "44" to "Ghosts",
        "45" to "Ninja",
        "46" to "Office Workers",
        "47" to "Police",
        "48" to "Post-Apocalyptic",
        "49" to "Reincarnation",
        "50" to "Traditional Games",
        "51" to "Virtual Reality",
        "52" to "4-Koma",
        "53" to "Adaptation",
        "54" to "Anthology",
        "55" to "Award Winning",
        "56" to "Doujinshi",
        "57" to "Fan Colored",
        "58" to "Full Color",
        "59" to "Long Strip",
        "60" to "Oneshot",
        "61" to "Web Comic",
        "62" to "Magical Girls",
        "63" to "Superhero",
        "64" to "Wuxia",
        "65" to "Adult",
        "66" to "Sexual Violence",
        "67" to "Smut",
        "68" to "Gore",
        "69" to "Official Colored",
    ).map { (id, name) ->
        ContentTag(
            title = name,
            key = id,
            source = source,
        )
    }

    // ============================== Language Variants ==============================

    @ContentSourceParser("MANGAFIRE_EN", "MangaFire English", "en")
    internal class English(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_EN, "en")

    @ContentSourceParser("MANGAFIRE_ES", "MangaFire Spanish", "es")
    internal class Spanish(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_ES, "es")

    @ContentSourceParser("MANGAFIRE_ESLA", "MangaFire Spanish (Latin America)", "es")
    internal class SpanishLatin(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_ESLA, "es-la")

    @ContentSourceParser("MANGAFIRE_FR", "MangaFire French", "fr")
    internal class French(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_FR, "fr")

    @ContentSourceParser("MANGAFIRE_JA", "MangaFire Japanese", "ja")
    internal class Japanese(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_JA, "ja")

    @ContentSourceParser("MANGAFIRE_PT", "MangaFire Portuguese", "pt")
    internal class Portuguese(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_PT, "pt")

    @ContentSourceParser("MANGAFIRE_PTBR", "MangaFire Portuguese (Brazil)", "pt")
    internal class PortugueseBR(context: ContentLoaderContext) : MangaFireParser(context, ContentParserSource.MANGAFIRE_PTBR, "pt-br")
}