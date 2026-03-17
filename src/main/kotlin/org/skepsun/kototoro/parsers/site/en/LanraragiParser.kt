@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.InternalParsersApi
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
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.parseJsonObject
import java.util.EnumSet

/**
 * Lanraragi (self-hosted)
 * 基于公开 API，需用户提供 API 地址与 APIKEY。
 */
@ContentSourceParser("LANRARAGI", "Lanraragi", "en")
internal class LanraragiParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.LANRARAGI, pageSize = 50) {

    private val apiKeyConfig = ConfigKey.Text(
        key = "lanraragi_api_key",
        title = "API Key",
    )
    override val configKeyDomain = ConfigKey.Domain(
        "lrr.tvc-16.science",
        "lrr.tv",
        "lrr",
    )
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(apiKeyConfig)
    }

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        // 拉取 categories 作为可选标签
        val categories = runCatching { fetchCategories() }.getOrDefault(emptyList())
        val tags = categories.map { ContentTag(it.second, it.first, source) }
        return ContentListFilterOptions(
            availableTags = tags.toSet(),
            tagGroups = if (tags.isNotEmpty()) listOf(org.skepsun.kototoro.parsers.model.ContentTagGroup("分类", tags.toSet())) else emptyList(),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )
    }

    private fun headers(): Headers {
        val builder = Headers.Builder()
        val apiKey = config[apiKeyConfig]
        if (!apiKey.isNullOrEmpty()) {
            val encoded = java.util.Base64.getEncoder().encodeToString(apiKey.toByteArray())
            builder.add("Authorization", "Bearer $encoded")
        }
        return builder.build()
    }

    private fun baseUrl(): String {
        val raw = config[configKeyDomain].removeSuffix("/")
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    }

    private suspend fun fetchCategories(): List<Pair<String, String>> {
        val res = webClient.httpGet("${baseUrl()}/api/categories", headers())
        if (!res.isSuccessful) return emptyList()
        val arr = runCatching { JSONArray(res.body.string()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").ifEmpty { obj.optString("_id").ifEmpty { obj.optString("name") } }
            val name = obj.optString("name").ifEmpty { id }
            id to name
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // All archives, client-side slice
        val res = webClient.httpGet("${baseUrl()}/api/archives", headers())
        if (!res.isSuccessful) return emptyList()
        val arr = runCatching { JSONArray(res.body.string()) }.getOrNull() ?: return emptyList()
        val list = (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            toContent(item)
        }

        val filtered = if (filter.tags.isNotEmpty()) {
            val keys = filter.tags.mapToSet { it.key.lowercase() }
            list.filter { m ->
                m.tags.any { keys.contains(it.key.lowercase()) || keys.contains(it.title.lowercase()) }
            }
        } else list

        val from = (page - 1) * pageSize
        return if (from >= filtered.size) emptyList() else filtered.subList(from, minOf(from + pageSize, filtered.size))
    }

    private fun toContent(item: JSONObject): Content {
        val arcid = item.optString("arcid").ifEmpty { item.optString("id") }
        val title = item.optString("title").ifEmpty { item.optString("filename").ifEmpty { arcid } }
        val base = baseUrl().removeSuffix("/")
        val cover = "$base/api/archives/$arcid/thumbnail"
        val tags = item.optString("tags")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ContentTag(it, it, source) }
            .toSet()
        return Content(
            id = generateUid(arcid),
            url = arcid,
            publicUrl = "$base/api/archives/$arcid",
            coverUrl = cover,
            title = title,
            altTitles = emptySet(),
            rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
            tags = tags,
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = ContentRating.SAFE,
            description = "页数: ${item.optInt("pagecount")} 扩展: ${item.optString("extension")}",
        )
    }

    override suspend fun getDetails(manga: Content): Content {
        val base = baseUrl().removeSuffix("/")
        val res = webClient.httpGet("$base/api/archives/${manga.url}/metadata", headers())
        if (!res.isSuccessful) return manga
        val data = res.parseJsonObject()
        val cover = "$base/api/archives/${manga.url}/thumbnail"
        var tagsList = data.optString("tags").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val rating = tagsList.firstOrNull { it.startsWith("rating:") }
        tagsList = tagsList.filterNot { it.startsWith("rating:") }
        val chapters = listOf(
            ContentChapter(
                id = generateUid("${manga.url}-chapter"),
                url = manga.url,
                title = data.optString("title", manga.title),
                number = 1f,
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        )
        val tags = tagsList.map { ContentTag(it, it, source) }.toMutableSet()
        if (rating != null) {
            tags.add(ContentTag(rating.removePrefix("rating:"), rating, source))
        }
        return manga.copy(
            title = data.optString("title", manga.title).ifEmpty { manga.title },
            coverUrl = cover,
            description = data.optString("summary", manga.description ?: ""),
            tags = tags,
            chapters = chapters,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val base = baseUrl().removeSuffix("/")
        val res = webClient.httpGet("$base/api/archives/${chapter.url}/files?force=false", headers())
        if (!res.isSuccessful) return emptyList()
        val data = res.parseJsonObject()
        val pages = data.optJSONArray("pages") ?: return emptyList()
        val result = ArrayList<ContentPage>(pages.length())
        for (i in 0 until pages.length()) {
            try {
                val raw = pages.opt(i)
                val path = when (raw) {
                    is JSONObject -> raw.optString("name").ifEmpty { raw.optString("url") }
                    else -> raw?.toString().orEmpty()
                }.trim()
                if (path.isEmpty()) continue
                val full = if (path.startsWith("http", true)) {
                    path
                } else {
                    "$base${if (path.startsWith("/")) path else "/$path"}"
                }
                result.add(
                    ContentPage(
                        id = generateUid("$full-$i"),
                        url = full,
                        preview = full,
                        source = source,
                    )
                )
            } catch (_: Exception) {
                // 忽略坏数据以避免崩溃
            }
        }
        return result
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
}
