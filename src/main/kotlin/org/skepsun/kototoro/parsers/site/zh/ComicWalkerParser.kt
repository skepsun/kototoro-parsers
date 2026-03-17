@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * ComicWalker / カドコミ（API）
 * 参考 venera-configs/comic_walker.js
 */
@Broken
@ContentSourceParser("COMIC_WALKER", "カドコミ", "ja")
internal class ComicWalkerParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.COMIC_WALKER, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("mobileapp.comic-walker.com")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    private val apiBase = "https://${config[configKeyDomain]}"
    private val apiKey = "ytBrdQ2ZYdRQguqEusVLxQVUgakNnVht"
    private val appVersion = "1.4.13"

    @Volatile
    private var token: String? = null

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        ContentListFilterOptions(availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE))

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "BookWalkerApp/$appVersion (Android 13)")
        .add("X-API-Environment-Key", apiKey)
        .add("Host", config[configKeyDomain])
        .add("Content-Type", "application/json")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        if (page > 1) return emptyList()

        val res = request("$apiBase/v2/screens/home")
        val resources = res.optJSONObject("resources") ?: return emptyList()
        val sections = mutableListOf<JSONArray>()
        resources.optJSONArray("new_arrival_comics")?.let { sections.add(it) }
        resources.optJSONArray("attention_comics")?.let { sections.add(it) }
        resources.optJSONArray("new_serialization_comics")?.let { sections.add(it) }
        resources.optJSONArray("pickup_comics")?.let { pickups ->
            for (i in 0 until pickups.length()) {
                val pickupObj = pickups.optJSONObject(i) ?: continue
                pickupObj.optJSONArray("comics")?.let { sections.add(it) }
            }
        }

        val seen = HashSet<String>()
        val comics = mutableListOf<Content>()
        sections.forEach { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifEmpty { obj.optString("comic_id") }
                val title = obj.optString("title")
                if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id)) {
                    val cover = obj.optString("thumbnail_1x1").ifEmpty { obj.optString("image_url") }
                    comics.add(
                        Content(
                            id = generateUid(id),
                            url = id,
                            publicUrl = "",
                            coverUrl = cover,
                            title = title,
                            altTitles = emptySet(),
                            rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                            tags = emptySet(),
                            authors = emptySet(),
                            state = null,
                            source = source,
                            contentRating = ContentRating.SAFE,
                        )
                    )
                }
            }
        }
        return comics
    }

    private suspend fun search(keyword: String, page: Int): List<Content> {
        val offset = (page - 1) * pageSize
        val res = request("$apiBase/v1/search/comics?keyword=${keyword.urlEncoded()}&limit=$pageSize&offset=$offset")
        val arr = res.optJSONArray("resources") ?: return emptyList()
        val result = mutableListOf<Content>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val title = obj.optString("title")
            if (id.isEmpty() || title.isEmpty()) continue
            val cover = obj.optString("thumbnail_1x1")
            result.add(
                Content(
                    id = generateUid(id),
                    url = id,
                    publicUrl = "",
                    coverUrl = cover,
                    title = title,
                    altTitles = emptySet(),
                    rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE,
                )
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Content): Content {
        val detailRes = request("$apiBase/v2/screens/comics/${manga.url}")
        val detail = detailRes.optJSONObject("resources")?.optJSONObject("detail") ?: return manga
        val title = detail.optString("title").ifEmpty { manga.title }
        val cover = detail.optString("thumbnail_1x1").ifEmpty { manga.coverUrl }
        val description = detail.optString("story").replace("<br>", "\n")

        val tagSet = mutableSetOf<String>()
        detail.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotEmpty()) tagSet.add(name)
            }
        }
        detail.optJSONArray("comic_labels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotEmpty()) tagSet.add(name)
            }
        }
        detail.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotEmpty()) tagSet.add(name)
            }
        }

        val chapters = fetchChapters(manga.url)
        val tags = tagSet.map { org.skepsun.kototoro.parsers.model.ContentTag(it, it, source) }.toSet()

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = if (description.isNotEmpty()) description else manga.description,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    private suspend fun fetchChapters(comicId: String): List<ContentChapter> {
        val all = mutableListOf<ContentChapter>()
        var offset = 0
        while (true) {
            val res = request("$apiBase/v1/comics/$comicId/episodes?offset=$offset&limit=100&sort=asc")
            val arr = res.optJSONArray("resources") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val title = obj.optString("title")
                val plans = obj.optJSONArray("plans") ?: JSONArray()
                var hasFree = false
                for (p in 0 until plans.length()) {
                    val type = plans.optJSONObject(p)?.optString("type")
                    if (type != "paid") {
                        hasFree = true
                        break
                    }
                }
                val displayTitle = if (hasFree) title else "❌ $title"
                if (id.isNotEmpty()) {
                    val number = (i + 1 + offset).toFloat()
                    all.add(
                        ContentChapter(
                            id = generateUid("$id-$comicId"),
                            url = "$comicId@$id",
                            title = displayTitle.ifEmpty { "Ch $number" },
                            number = number,
                            volume = 0,
                            scanlator = null,
                            uploadDate = 0,
                            branch = null,
                            source = source,
                        )
                    )
                }
            }
            if (arr.length() < 100) break
            offset += 100
        }
        return all
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val parts = chapter.url.split("@")
        if (parts.size < 2) return emptyList()
        val comicId = parts[0]
        val epId = parts[1]
        val res = request("$apiBase/v1/screens/comics/$comicId/episodes/$epId/viewer")
        val manuscripts = res.optJSONObject("resources")?.optJSONArray("manuscripts") ?: return emptyList()
        val pages = mutableListOf<ContentPage>()
        for (i in 0 until manuscripts.length()) {
            val obj = manuscripts.optJSONObject(i) ?: continue
            val url = obj.optString("drm_image_url")
            val hash = obj.optString("drm_hash")
            val finalUrl = if (url.isNotEmpty() && hash.isNotEmpty()) "$url&drm_hash=$hash" else url
            if (finalUrl.isNotEmpty()) {
                pages.add(
                    ContentPage(
                        id = generateUid("$finalUrl-$i"),
                        url = finalUrl,
                        preview = finalUrl,
                        source = source,
                    )
                )
            }
        }
        return pages
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private suspend fun request(url: String): JSONObject {
        ensureToken()
        val headers = authHeaders()
        val resp = webClient.httpGet(url, headers)
        if (resp.code == 204) return JSONObject()
        if (resp.code == 401) {
            refreshToken()
            val retry = webClient.httpGet(url, authHeaders())
            if (retry.code == 204) return JSONObject()
            return retry.parseJsonObject()
        }
        val obj = resp.parseJsonObject()
        val code = obj.optString("code")
        if (code == "invalid_request_parameter" || code == "unauthorized") {
            refreshToken()
            val retry = webClient.httpGet(url, authHeaders())
            return retry.parseJsonObject()
        }
        return obj
    }

    private fun authHeaders(): Headers {
        return getRequestHeaders().newBuilder().apply {
            token?.let { add("Authorization", "Bearer $it") }
        }.build()
    }

    private suspend fun ensureToken() {
        if (token.isNullOrEmpty()) {
            refreshToken()
        }
    }

    private suspend fun refreshToken() {
        val resp = webClient.httpPost("$apiBase/v1/users".toHttpUrl(), JSONObject(), getRequestHeaders())
        val obj = resp.parseJsonObject()
        val res = obj.optJSONObject("resources")
        token = res?.optString("access_token")
    }
}
