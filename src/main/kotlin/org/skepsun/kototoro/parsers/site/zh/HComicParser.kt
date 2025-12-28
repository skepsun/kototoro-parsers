package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.*
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import java.util.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.util.generateUid

@MangaSourceParser("HCOMIC", "H-Comic", "zh", type = ContentType.HENTAI_MANGA)
internal class HComicParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HCOMIC, pageSize = 20),
    MangaParserAuthProvider,
    FavoritesProvider,
    FavoritesSyncProvider {

    override val configKeyDomain = ConfigKey.Domain("h-comic.com")
    // Auth0 login - the site will redirect to Auth0 via client-side JavaScript
    override val authUrl = "https://h-comic.com"
    private val apiUrl = "https://api.h-comic.com/api"
    private val imageServerUrl = "https://h-comic.link/api"

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false
        )

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    private fun getApiHeaders(): Headers = getRequestHeaders().newBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .build()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = listOf(
            "全部", "全彩", "無修正", "蘿莉", "制服", "巨乳", "黑絲 / 白襪", "NTR", "足交 / 腳交",
            "女學生", "眼鏡控", "口交", "正太控", "年上", "亂倫", "熟女 / 人妻", "同志 BL",
            "黑肉", "泳裝", "手淫", "肌肉", "姐姐 / 妹妹", "捆綁", "調教", "催眠", "露出",
            "群交", "肛交", "獸交"
        ).map { MangaTag(it, it, source) }

        return MangaListFilterOptions(
            availableTags = tags.toSet(),
            tagGroups = listOf(MangaTagGroup("熱門TAG", tags.toSet()))
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query ?: ""
        val tag = filter.tags.firstOrNull()?.key

        val url = when {
            query.isNotEmpty() -> {
                "https://$domain/?q=${query.urlEncoded()}&tag=&page=$page"
            }
            tag != null && tag != "全部" -> {
                val param = when (tag) {
                    "NTR" -> "netorare"
                    "足交 / 腳交" -> "footjob"
                    else -> tag
                }
                "https://$domain/?q=&tag=${param.urlEncoded()}&page=$page"
            }
            else -> {
                // If order is random, some sites use a specific path, but let's stick to home for now or path matching JS
                val path = if (order == SortOrder.UPDATED) "/" else "/random"
                "https://$domain$path?page=$page&q=&tag="
            }
        }

        val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
        val data = extractDataFromHtml(html) ?: return emptyList()
        val comics = data.optJSONArray("comics") ?: JSONArray()
        return comics.mapJSON { parseManga(it) }
    }

    private fun parseManga(jo: JSONObject): Manga {
        val id = jo.optString("id")
        val titleObj = jo.optJSONObject("title")
        val title = titleObj?.optString("display") 
            ?: titleObj?.optString("pretty")
            ?: titleObj?.optString("japanese")
            ?: "Unknown"
        
        val comicSource = jo.optString("comic_source")
        val mediaId = jo.optString("media_id")
        val cover = jo.optString("thumbnail").ifEmpty {
            "$imageServerUrl/$comicSource/$mediaId" // Based on JS parseComic
        }

        val tags = jo.optJSONArray("tags")?.mapJSONNotNull { t ->
            val tagName = t.optString("name_zh").ifEmpty { t.optString("name") }
            if (tagName.isNotEmpty()) MangaTag(tagName, tagName, source) else null
        }?.toSet() ?: emptySet()

        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = emptySet(),
            coverUrl = cover,
            largeCoverUrl = cover,
            authors = emptySet(),
            contentRating = ContentRating.ADULT,
            rating = 0f,
            url = "$id|${title.urlEncoded()}", // Store title to reconstruct URL
            publicUrl = "https://$domain/comics/${title.urlEncoded()}/1?id=$id",
            tags = tags,
            state = null,
            source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val parts = manga.url.split("|")
        val id = parts[0]
        val titleParam = parts.getOrNull(1) ?: "view"

        val url = "https://$domain/comics/$titleParam/1?id=$id"
        val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
        val data = extractDataFromHtml(html) ?: throw ParseException("Failed to extract data", source.name)
        
        // In the HTML data structure, listing pages have "comics", but detail pages have "comic"
        val comicJo = data.optJSONObject("comic") ?: throw ParseException("Comic data not found in extracted data", source.name)
        val titleObj = comicJo.optJSONObject("title")
        val title = titleObj?.optString("display") ?: titleObj?.optString("pretty") ?: titleObj?.optString("japanese") ?: manga.title
        val description = titleObj?.optString("japanese") ?: ""
        
        val comicSource = comicJo.optString("comic_source")
        val mediaId = comicJo.optString("media_id")
        val numPages = comicJo.optInt("num_pages")
        
        val chapterId = "$comicSource|$mediaId|$numPages"
        val chapter = MangaChapter(
            id = generateUid(chapterId),
            title = "全一話",
            number = 1f,
            volume = 0,
            url = chapterId,
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = source
        )

        return manga.copy(
            title = title,
            description = description,
            chapters = listOf(chapter)
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("|")
        if (parts.size < 3) return emptyList()
        val sourceStr = parts[0]
        val mediaId = parts[1]
        val numPages = parts[2].toIntOrNull() ?: 0

        val pages = mutableListOf<MangaPage>()
        for (i in 1..numPages) {
            val url = "$imageServerUrl/$sourceStr/$mediaId/pages/$i"
            pages.add(MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            ))
        }
        return pages
    }

    private fun extractDataFromHtml(html: String): JSONObject? {
        // Updated regex for SvelteKit data structure in HTML, matching JS config more closely
        val regex = Regex("data:\\s*\\[null,\\s*(\\{.*?\\})\\s*\\]\\s*,\\s*form:", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        return try {
            val dataStr = match.groupValues[1]
            // SvelteKit data in scripts is often valid JSON but let's be careful
            val json = JSONObject(dataStr)
            json.optJSONObject("data") ?: json
        } catch (e: Exception) {
            // Some SvelteKit data might have unquoted keys or trailing commas
            // Let's try to fix unquoted keys loosely if initial parse fails
            try {
                val cleaned = match.groupValues[1]
                    .replace(Regex("([{,])\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:"), "$1\"$2\":")
                val json = JSONObject(cleaned)
                json.optJSONObject("data") ?: json
            } catch (e2: Exception) {
                null
            }
        }
    }

    // Auth implementation
    override suspend fun isAuthorized(): Boolean {
        val cookies = context.cookieJar.getCookies(domain)
        // Check for Auth0-related session cookies
        return cookies.any { 
            it.name == "auth0_token" || 
            it.name == "auth0.is.authenticated" ||
            it.name.contains("auth0", ignoreCase = true) || 
            it.name == "app_token" 
        }
    }


    override suspend fun getUsername(): String {
        return if (isAuthorized()) "User" else throw AuthRequiredException(source)
    }


    // WebView login is used - no programmatic login method needed
    // User will be directed to authUrl in a WebView to complete Auth0 login


    override suspend fun fetchFavorites(): List<Manga> {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val url = "https://$domain/favourites"
        return try {
            val response = webClient.httpGet(url, getRequestHeaders())
            if (!response.isSuccessful) return emptyList()
            val html = response.parseRaw()
            val data = extractDataFromHtml(html) ?: return emptyList()
            
            val favouritesJo = data.optJSONObject("favourites")
            val docs = favouritesJo?.optJSONArray("docs") ?: JSONArray()
            
            val mangas = mutableListOf<Manga>()
            for (i in 0 until docs.length()) {
                val item = docs.optJSONObject(i)
                val comicJo = item?.optJSONObject("comic")
                if (comicJo != null) {
                    mangas.add(parseManga(comicJo))
                }
            }
            mangas
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun addFavorite(manga: Manga): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        val id = manga.url.split("|")[0]
        val url = "$apiUrl/collections"
        val json = JSONObject().put("comic_id", id)
        return try {
            webClient.httpPost(url.toHttpUrl(), json, getApiHeaders()).use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun removeFavorite(manga: Manga): Boolean {
        if (!isAuthorized()) throw AuthRequiredException(source)
        // Provisional: H-Comic might use DELETE but WebClient only supports POST/GET
        // For now return false as it's not and we can't easily do it without httpDelete in interface
        return false
    }
}
