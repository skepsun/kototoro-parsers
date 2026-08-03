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
 * 单一源，章节仅加载设置中选定的语言，并按翻译版本分 branch。
 *
 * API 端点:
 * - GET /api/titles        — 列表/搜索
 * - GET /api/titles/{hid}  — 详情
 * - GET /api/titles/{hid}/chapters — 章节列表（按 language 参数过滤）
 * - GET /api/chapters/{id} — 页面列表
 * - GET /api/tags          — 标签搜索（作者/画师）
 *
 * 参考: keiyoushi/extensions-source commit f91a65fb3
 */
@ContentSourceParser("MANGAFIRE", "MangaFire")
internal class MangaFireParser(context: ContentLoaderContext) : PagedContentParser(
    context = context,
    source = ContentParserSource.MANGAFIRE,
    pageSize = 50,
) {

    override val configKeyDomain = ConfigKey.Domain("mangafire.to")

    private val preferredLanguageKey = ConfigKey.PreferredLanguage(
        title = "Preferred Language",
        presetValues = linkedMapOf(
            "en" to "English",
            "es" to "Spanish",
            "es-la" to "Spanish (Latin America)",
            "fr" to "French",
            "ja" to "Japanese",
            "pt" to "Portuguese",
            "pt-br" to "Portuguese (Brazil)",
        ),
        defaultValue = DEFAULT_LANGUAGE,
    )

    private val preferredLanguage: String
        get() = config[preferredLanguageKey].takeIf(langCodeToName::containsKey) ?: DEFAULT_LANGUAGE

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(preferredLanguageKey)
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

            if (filter.states.isNotEmpty()) {
                filter.states.forEach { state ->
                    val apiState = state.toApiStatus()
                    if (apiState != null) {
                        addQueryParameter("statuses[]", apiState)
                    }
                }
            }

            if (filter.yearFrom != YEAR_UNKNOWN) {
                addQueryParameter("year_from", filter.yearFrom.toString())
            }
            if (filter.yearTo != YEAR_UNKNOWN) {
                addQueryParameter("year_to", filter.yearTo.toString())
            }

            if (authorId != null) {
                addQueryParameter("authors[]", authorId)
            }
        }.build()

        val json = webClient.httpGet(MangaFireVrfSigner.sign(url)).parseJson()
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
        val json = webClient.httpGet(apiUrl("titles/$hid")).parseJson()
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
        val chapters = loadChapters(manga.url, preferredLanguage)

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

    private suspend fun loadChapters(mangaUrl: String, langCode: String): List<ContentChapter> {
        val hid = getHid(mangaUrl)
        val chapters = mutableListOf<ContentChapter>()
        var page = 1
        var lastPage = 1

        do {
            val url = "https://$domain/api/titles/$hid/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("language", langCode)
                .addQueryParameter("sort", "number")
                .addQueryParameter("order", "asc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", "200")
                .build()

            val json = webClient.httpGet(MangaFireVrfSigner.sign(url)).parseJson()
            val items = json.optJSONArray("items") ?: break
            val meta = json.optJSONObject("meta")
            lastPage = meta?.optInt("lastPage", 1) ?: 1

            items.mapJSON { ch ->
                val id = ch.getInt("id")
                val number = ch.getDouble("number").toFloat()
                val name = ch.optString("name", "").takeIf { it.isNotEmpty() }
                val createdAt = ch.getLongOrDefault("createdAt", 0L)
                val translationType = ch.optString("type", "").trim().ifEmpty { UNKNOWN_TRANSLATION }

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
                        scanlator = translationType,
                        uploadDate = createdAt * 1000L,
                        branch = translationType,
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
        val json = webClient.httpGet(apiUrl("chapters/$chapterId")).parseJson()
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

    private fun apiUrl(path: String) = MangaFireVrfSigner.sign(
        "https://$domain/api/${path.removePrefix("/")}".toHttpUrl(),
    )

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
        "not_yet_released" -> ContentState.UPCOMING
        else -> null
    }

    private suspend fun resolveAuthorId(authorQuery: String): String? {
        val url = "https://$domain/api/tags".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", authorQuery)
            .build()
        val json = runCatching { webClient.httpGet(MangaFireVrfSigner.sign(url)).parseJson() }.getOrNull()
            ?: return null
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

    // ============================== Languages ==============================

    private val langCodeToName = mapOf(
        "en" to "English",
        "es" to "Spanish",
        "es-la" to "Spanish (Latin America)",
        "fr" to "French",
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "pt-br" to "Portuguese (Brazil)",
    )

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
        const val UNKNOWN_TRANSLATION = "Unknown"
    }

    // ============================== Genres ==============================

    private val genres: List<ContentTag> = listOf(
        "1" to "Action",
        "268929" to "Adult",
        "78" to "Adventure",
        "3" to "Avant Garde",
        "4" to "Boys Love",
        "5" to "Comedy",
        "268921" to "Crime",
        "77" to "Demons",
        "6" to "Drama",
        "7" to "Ecchi",
        "79" to "Fantasy",
        "9" to "Girls Love",
        "10" to "Gourmet",
        "11" to "Harem",
        "268930" to "Hentai",
        "268922" to "Historical",
        "530" to "Horror",
        "13" to "Isekai",
        "531" to "Iyashikei",
        "15" to "Josei",
        "532" to "Kids",
        "539" to "Magic",
        "268923" to "Magical Girls",
        "533" to "Mahou Shoujo",
        "534" to "Martial Arts",
        "268931" to "Mature",
        "19" to "Mecha",
        "268924" to "Medical",
        "535" to "Military",
        "21" to "Music",
        "22" to "Mystery",
        "23" to "Parody",
        "268925" to "Philosophical",
        "536" to "Psychological",
        "25" to "Reverse Harem",
        "26" to "Romance",
        "73" to "School",
        "28" to "Sci-Fi",
        "537" to "Seinen",
        "30" to "Shoujo",
        "31" to "Shounen",
        "538" to "Slice of Life",
        "268932" to "Smut",
        "33" to "Space",
        "34" to "Sports",
        "75" to "Super Power",
        "268926" to "Superhero",
        "76" to "Supernatural",
        "37" to "Suspense",
        "38" to "Thriller",
        "268927" to "Tragedy",
        "39" to "Vampire",
        "268928" to "Wuxia",
    ).map { (id, name) ->
        ContentTag(
            title = name,
            key = id,
            source = source,
        )
    }
}
