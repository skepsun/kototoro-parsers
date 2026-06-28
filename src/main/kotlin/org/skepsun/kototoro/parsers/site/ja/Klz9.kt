@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.security.MessageDigest
import java.util.EnumSet

@ContentSourceParser("KLZ9", "Klz9", "ja")
internal class Klz9(context: ContentLoaderContext) : 
    PagedContentParser(context, ContentParserSource.KLZ9, pageSize = 36) {

    override val configKeyDomain = ConfigKey.Domain("klz9.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities get() = ContentListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
    )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        // 从 API 获取实时 genres 列表
        val tags = try {
            val url = "https://$domain/api/genres"
            val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
            val genresArray = JSONArray(response.parseRaw())
            val tagSet = mutableSetOf<ContentTag>()
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.getJSONObject(i)
                val name = genreObj.optString("name", "")
                if (name.isNotEmpty()) {
                    tagSet.add(ContentTag(name, name.lowercase().replace(" ", "-"), source))
                }
            }
            tagSet
        } catch (e: Exception) {
            emptySet()
        }
        return ContentListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
        )
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

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val query = filter.query
        val hasStateFilter = filter.states.isNotEmpty()
        if (!query.isNullOrEmpty() || hasStateFilter) {
            val allContentArray = fetchAllContent()
            val filteredContent = mutableListOf<JSONObject>()
            val queryLower = query?.lowercase().orEmpty()

            for (i in 0 until allContentArray.length()) {
                val item = allContentArray.getJSONObject(i)
                if (!matchesQuery(item, queryLower)) continue
                if (!matchesState(item, filter.states)) continue
                if (!matchesTag(item, filter.tags)) continue
                filteredContent.add(item)
            }

            val sorted = filteredContent.sortedWith(jsonComparator(order))

            val start = (page - 1) * pageSize
            if (start >= sorted.size) return emptyList()

            val end = (start + pageSize).coerceAtMost(sorted.size)
            return sorted.subList(start, end).map { parseContentFromJson(it) }
        }

        val sortParam = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.ALPHABETICAL -> "name"
            else -> "last_update"
        }
        val sortOrder = if (order == SortOrder.ALPHABETICAL) "asc" else "desc"
        // 支持按标签过滤
        val genreParam = filter.tags.firstOrNull()?.key?.let { "&genre=$it" } ?: ""
        val url = "https://$domain/api/manga/list?page=$page&limit=$pageSize&sort=$sortParam&order=$sortOrder$genreParam"

        val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
        val json = JSONObject(response.parseRaw())
        
        val items = json.optJSONArray("items") ?: return emptyList()
        return parseContentList(items, order)
    }

    private fun matchesQuery(item: JSONObject, queryLower: String): Boolean {
        if (queryLower.isEmpty()) return true
        val name = item.optString("name", "").lowercase()
        val otherName = item.optString("other_name", "").lowercase()
        return name.contains(queryLower) || otherName.contains(queryLower)
    }

    private fun matchesState(item: JSONObject, states: Set<ContentState>): Boolean {
        if (states.isEmpty()) return true
        val status = item.optInt("m_status", 0)
        return when {
            status == 2 -> states.contains(ContentState.ONGOING)
            status == 1 -> states.contains(ContentState.FINISHED)
            else -> false
        }
    }

    private fun matchesTag(item: JSONObject, tags: Set<ContentTag>): Boolean {
        if (tags.isEmpty()) return true
        val genresStr = item.optString("genres", "")
        if (genresStr.isEmpty()) return false
        val available = genresStr.split(",").map { it.trim().lowercase().replace(" ", "-") }.toSet()
        return tags.any { available.contains(it.key) }
    }

    private fun jsonComparator(order: SortOrder): Comparator<JSONObject> = when (order) {
        SortOrder.POPULARITY -> compareByDescending { parseViews(it.opt("views")) }
        SortOrder.ALPHABETICAL -> compareBy { it.optString("name", "").lowercase() }
        else -> compareByDescending { parseDateSafe(it.optString("last_update", "")) }
    }

    private fun parseDateSafe(value: String): Long = try {
        if (value.isNotEmpty()) java.time.Instant.parse(value).toEpochMilli() else 0L
    } catch (e: Exception) {
        0L
    }

    private suspend fun fetchAllContent(): JSONArray {
        val url = "https://$domain/api/manga/all"
        val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
        val items = JSONArray(response.parseRaw())
        val seen = mutableMapOf<String, JSONObject>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val slug = item.optString("slug", "")
            val key = slug.lowercase()
            val existing = seen[key]
            if (existing == null || slug.any { it.isUpperCase() }) {
                seen[key] = item
            }
        }
        val deduped = JSONArray()
        seen.values.forEach { deduped.put(it) }
        return deduped
    }

    private fun parseContentList(items: JSONArray, order: SortOrder): List<Content> {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            list.add(items.getJSONObject(i))
        }
        list.sortWith(jsonComparator(order))
        return list.map { parseContentFromJson(it) }
    }

    private fun parseContentFromJson(json: JSONObject): Content {
        val slug = json.optString("slug", "").lowercase()
        val href = "/manga/$slug"
        
        val genresStr = json.optString("genres", "")
        val tags = if (genresStr.isNotEmpty()) {
            genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { 
                ContentTag(it, it.lowercase().replace(" ", "-"), source)
            }.toSet()
        } else emptySet()

        val authorsStr = json.optString("authors", "")
        val authors = if (authorsStr.isNotEmpty()) {
            authorsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else emptySet()

        val state = when (json.optInt("m_status", 0)) {
            1 -> ContentState.FINISHED
            2 -> ContentState.ONGOING
            else -> null
        }

        return Content(
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

    override suspend fun getDetails(manga: Content): Content {
        // 从 URL 中获取 slug
        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val url = "https://$domain/api/manga/slug/$slug"
        
        val response = webClient.httpGet(url.toHttpUrl(), generateSignatureHeaders())
        val json = JSONObject(response.parseRaw())
        
        val genresStr = json.optString("genres", "")
        val tags = if (genresStr.isNotEmpty()) {
            genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { 
                ContentTag(it, it.lowercase().replace(" ", "-"), source)
            }.toSet()
        } else manga.tags

        val authorsStr = json.optString("authors", "")
        val authors = if (authorsStr.isNotEmpty()) {
            authorsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else manga.authors

        val state = when (json.optInt("m_status", 0)) {
            1 -> ContentState.FINISHED
            2 -> ContentState.ONGOING
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

    private fun parseChapters(chaptersArray: JSONArray?): List<ContentChapter> {
        if (chaptersArray == null || chaptersArray.length() == 0) return emptyList()
        
        val chapters = mutableListOf<ContentChapter>()
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
                ContentChapter(
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

    private fun parseViews(value: Any?): Long {
        val asString = when (value) {
            is Number -> value.toLong()
            is String -> value
            else -> return 0L
        }.toString()
        // 与前端一致：去除非数字字符再取整数
        return asString.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
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
            ContentPage(
                id = generateUid("$chapterId-$index"),
                url = pageUrl.trim(),
                preview = null,
                source = source,
            )
        }
    }
}
