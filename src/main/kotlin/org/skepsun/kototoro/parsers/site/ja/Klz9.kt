@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.security.MessageDigest
import java.util.EnumSet

@MangaSourceParser("KLZ9", "Klz9", "ja")
internal class Klz9(context: MangaLoaderContext) : 
    PagedMangaParser(context, MangaParserSource.KLZ9, pageSize = 36) {

    override val configKeyDomain = ConfigKey.Domain("klz9.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
    )

    override val filterCapabilities get() = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        // 从 API 获取实时 genres 列表
        val tags = try {
            val url = "https://$domain/api/genres"
            val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
            val genresArray = JSONArray(response.parseRaw())
            val tagSet = mutableSetOf<MangaTag>()
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.getJSONObject(i)
                val name = genreObj.optString("name", "")
                if (name.isNotEmpty()) {
                    tagSet.add(MangaTag(name, name.lowercase().replace(" ", "-"), source))
                }
            }
            tagSet
        } catch (e: Exception) {
            emptySet()
        }
        return MangaListFilterOptions(availableTags = tags)
    }

    // 签名密钥
    private val signatureKey = "KL9K40zaSyC9K40vOMLLbEcepIFBhUKXwELqxlwTEF"

    // 生成签名头
    private fun generateSignatureHeaders(): Headers {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val data = "$timestamp.$signatureKey"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data.toByteArray(Charsets.UTF_8))
        val signature = digest.joinToString("") { "%02x".format(it) }
        
        return Headers.Builder()
            .add("User-Agent", UserAgents.CHROME_DESKTOP)
            .add("Content-Type", "application/json")
            .add("x-client-ts", timestamp)
            .add("x-client-sig", signature)
            .add("Referer", "https://$domain/")
            .build()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/api/manga/search?q=${filter.query!!.urlEncoded()}&page=$page&limit=$pageSize"
        } else {
            val sortParam = when (order) {
                SortOrder.POPULARITY -> "views"
                SortOrder.NEWEST -> "released"
                else -> "last_update"
            }
            // 支持按标签过滤
            val genreParam = filter.tags.firstOrNull()?.key?.let { "&genre=$it" } ?: ""
            "https://$domain/api/manga/list?page=$page&limit=$pageSize&sort=$sortParam&order=desc$genreParam"
        }

        val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
        val json = JSONObject(response.parseRaw())
        
        val items = json.optJSONArray("items") ?: return emptyList()
        return parseMangaList(items)
    }

    private fun parseMangaList(items: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            result.add(parseMangaFromJson(item))
        }
        return result
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val slug = json.optString("slug", "")
        val href = "/manga/$slug"
        
        val genresStr = json.optString("genres", "")
        val tags = if (genresStr.isNotEmpty()) {
            genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { 
                MangaTag(it, it.lowercase().replace(" ", "-"), source)
            }.toSet()
        } else emptySet()

        val authorsStr = json.optString("authors", "")
        val authors = if (authorsStr.isNotEmpty()) {
            authorsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else emptySet()

        val state = when (json.optInt("m_status", 0)) {
            1 -> MangaState.FINISHED
            2 -> MangaState.ONGOING
            else -> null
        }

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = "https://$domain$href",
            coverUrl = json.optString("cover", ""),
            title = json.optString("name", ""),
            altTitles = setOfNotNull(json.optString("other_name", "").takeIf { it.isNotEmpty() }),
            rating = RATING_UNKNOWN,
            tags = tags,
            authors = authors,
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // 从 URL 中获取 slug
        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val url = "https://$domain/api/manga/slug/$slug"
        
        val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
        val json = JSONObject(response.parseRaw())
        
        val genresStr = json.optString("genres", "")
        val tags = if (genresStr.isNotEmpty()) {
            genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { 
                MangaTag(it, it.lowercase().replace(" ", "-"), source)
            }.toSet()
        } else manga.tags

        val authorsStr = json.optString("authors", "")
        val authors = if (authorsStr.isNotEmpty()) {
            authorsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else manga.authors

        val state = when (json.optInt("m_status", 0)) {
            1 -> MangaState.FINISHED
            2 -> MangaState.ONGOING
            else -> manga.state
        }

        val chapters = parseChapters(json.optJSONArray("chapters"))

        return manga.copy(
            description = json.optString("description", "").let { 
                it.replace(Regex("<[^>]+>"), "").trim()
            }.takeIf { it.isNotEmpty() },
            coverUrl = json.optString("cover", manga.coverUrl),
            altTitles = setOfNotNull(json.optString("other_name", "").takeIf { it.isNotEmpty() }),
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters,
        )
    }

    private fun parseChapters(chaptersArray: JSONArray?): List<MangaChapter> {
        if (chaptersArray == null || chaptersArray.length() == 0) return emptyList()
        
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val chapterJson = chaptersArray.getJSONObject(i)
            val chapterId = chapterJson.optInt("id", 0)
            // API 返回的是 "chapter" 字段，不是 "number"
            val chapterNumber = chapterJson.optDouble("chapter", 0.0).toFloat()
            // API 返回的是 "name" 字段，可能是 null
            val chapterName = chapterJson.optString("name", null)
            // 章节标题格式："Chapter X.X" 或 "Chapter X.X name"
            val chapterTitle = if (!chapterName.isNullOrEmpty() && chapterName != "null") {
                "Chapter $chapterNumber $chapterName"
            } else {
                "Chapter $chapterNumber"
            }
            val href = "/chapter/$chapterId"
            
            // 解析时间戳
            val dateStr = chapterJson.optString("last_update", "")
            val uploadDate = try {
                if (dateStr.isNotEmpty()) {
                    java.time.Instant.parse(dateStr).toEpochMilli()
                } else 0L
            } catch (e: Exception) { 0L }
            
            chapters.add(
                MangaChapter(
                    id = generateUid(href),
                    title = chapterTitle,
                    number = chapterNumber,
                    volume = 0,
                    url = href,
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        // API 返回的是倒序（最新在前），反转为正序（最老在前）
        return chapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // 从 URL 中获取 chapter ID
        val chapterId = chapter.url.removePrefix("/chapter/").removeSuffix("/")
        val url = "https://$domain/api/chapter/$chapterId"
        
        val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
        val json = JSONObject(response.parseRaw())
        
        // API 返回的是 "content" 字段，包含换行符分隔的图片 URL
        val contentStr = json.optString("content", "")
        if (contentStr.isEmpty()) return emptyList()
        
        val imageUrls = contentStr.split("\n").filter { it.isNotEmpty() }
        
        return imageUrls.mapIndexed { index, pageUrl ->
            MangaPage(
                id = generateUid("$chapterId-$index"),
                url = pageUrl.trim(),
                preview = null,
                source = source,
            )
        }
    }
}
