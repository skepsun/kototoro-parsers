package org.skepsun.kototoro.parsers.site.yealico

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.*

/**
 * Generic parser that executes Yealico/H-Viewer style JSON site rules.
 *
 * Loads a JSON rule from bundled resources and dynamically extracts content
 * using CSS selectors (HTML) or JSONPath (API), plus regex transforms.
 *
 * Filter support: converts Yealico `categories` to ContentTag options,
 * `flags` to filterCapabilities, `searchUrl` to search support.
 */
internal open class YealicoRuleParser(
    context: ContentLoaderContext,
    source: ContentSource,
    private val ruleJson: JSONObject,
) : PagedContentParser(context, source, pageSize = 48) {

    override val configKeyDomain = ConfigKey.Domain(extractDomain())

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = hasSearch,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isYearSupported = false,
        )

    // ---- NSFW handling ----


    private fun contentRating(): ContentRating? = when (source.contentType) {
        ContentType.HENTAI_MANGA, ContentType.HENTAI_VIDEO -> ContentRating.ADULT
        else -> null
    }

    // ---- Rule introspection ----

    private val flags: String = ruleJson.optString("flag", "")

    private val isJsonApi: Boolean by lazy {
        val itemObj = ruleJson.optJSONObject("indexRule")?.optJSONObject("item")
        itemObj != null && itemObj.has("path") && !itemObj.has("selector")
    }

    private val hasSearch: Boolean by lazy {
        ruleJson.optString("searchUrl", "").isNotEmpty()
    }

    private val hasRating: Boolean by lazy {
        !flags.contains("noRating")
    }

    private val parsedCategories: List<CategoryPage> by lazy { parseCategories() }

    private data class CategoryPage(
        val cid: Int,
        val title: String,
        val url: String,
    )

    private fun extractDomain(): String {
        val url = ruleJson.optString("indexUrl", "")
        return Regex("https?://([^/:]+)").find(url)?.groupValues?.get(1) ?: "example.com"
    }

    // ---- Filter options ----

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = if (parsedCategories.isNotEmpty()) {
            parsedCategories.mapTo(mutableSetOf()) { cat ->
                ContentTag(
                    key = cat.cid.toString(),
                    title = cat.title,
                    source = source,
                )
            }
        } else {
            emptySet<ContentTag>()
        }

        return ContentListFilterOptions(
            availableTags = tags,
            availableStates = emptySet(),
            availableContentTypes = emptySet(),
        )
    }

    // ---- Listing ----

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // If filter has a tag matching a category cid, use that category's URL
        val catUrl = resolveCategoryUrl(filter)
        val url = buildListUrl(page, baseUrl = catUrl)
        val response = webClient.httpGet(url)
        val body = response.body?.string() ?: throw ParseException("Empty response", url)
        return if (isJsonApi) parseJsonList(body) else parseHtmlList(Jsoup.parse(body, url))
    }

    /**
     * If the filter contains a tag whose key matches a category cid,
     * use that category's URL instead of the default indexUrl.
     */
    private fun resolveCategoryUrl(filter: ContentListFilter): String? {
        if (parsedCategories.isEmpty() || filter.tags.isEmpty()) return null
        for (tag in filter.tags) {
            val cid = tag.key.toIntOrNull() ?: continue
            val cat = parsedCategories.find { it.cid == cid }
            if (cat != null) return cat.url
        }
        return null
    }

    private fun buildListUrl(page: Int, baseUrl: String? = null): String {
        var url = baseUrl ?: ruleJson.optString("indexUrl", "")
        url = url.replace(Regex("\\{page:\\d+\\}"), page.toString())
        url = url.replace(Regex("\\{pageStr:page/\\{page:\\d+\\}\\}"), page.toString())
        return if (url.startsWith("http")) url else "https://$domain$url"
    }

    private fun parseHtmlList(doc: Document): List<Content> {
        val ir = ruleJson.optJSONObject("indexRule")
            ?: throw ParseException("No indexRule in rule JSON", source.name)
        val itemSel = ir.optJSONObject("item")?.optString("selector")
            ?: parseCategories().firstOrNull()?.let { _ ->
                // Try to find a shared listRule first
                ruleJson.optJSONObject("listRule")?.optJSONObject("item")?.optString("selector")
            }
            ?: throw ParseException("No item selector in indexRule", source.name)

        val items = doc.select(itemSel)
        if (items.isEmpty()) return emptyList()
        return items.mapNotNull { el -> parseItemFromElement(el, ir) }
    }

    private fun parseJsonList(body: String): List<Content> {
        val ir = ruleJson.getJSONObject("indexRule")
        val root = JSONObject(body)
        val hits: JSONArray = root.optJSONArray("hits")
            ?: root.optJSONArray("results")
            ?: JSONArray().apply { put(root) }

        val result = mutableListOf<Content>()
        for (i in 0 until hits.length()) {
            result.add(parseItemFromJson(hits.getJSONObject(i), ir))
        }
        return result
    }

    private fun parseItemFromElement(el: Element, ir: JSONObject): Content? {
        val title = extractHtml(el, ir.optJSONObject("title")) ?: return null
        val idCode = extractHtml(el, ir.optJSONObject("idCode")) ?: title
        val cover = extractHtml(el, ir.optJSONObject("cover"))
        val detailUrl = buildDetailUrl(idCode)

        // Parse additional fields
        val rating = if (hasRating) {
            extractRating(el, ir.optJSONObject("rating"))
        } else {
            RATING_UNKNOWN
        }

        return Content(
            id = generateUid(idCode),
            url = detailUrl,
            publicUrl = detailUrl,
            coverUrl = cover?.let { it.toAbsoluteUrl(domain) },
            title = title,
            altTitles = emptySet(),
            rating = rating,
            tags = emptySet(),
            authors = extractHtml(el, ir.optJSONObject("uploader"))
                ?.let { setOf(it) } ?: emptySet(),
            state = null,
            source = source,
            contentRating = contentRating(),
        )
    }

    private fun parseItemFromJson(obj: JSONObject, ir: JSONObject): Content {
        val title = extractJson(obj, ir.optJSONObject("title"), "name") ?: "Untitled"
        val idCode = extractJson(obj, ir.optJSONObject("idCode"), "slug")
            ?: extractJson(obj, ir.optJSONObject("idCode"), "id") ?: title
        val cover = extractJson(obj, ir.optJSONObject("cover"), "cover_url")
        val detailUrl = buildDetailUrl(idCode)

        return Content(
            id = generateUid(idCode),
            url = detailUrl,
            publicUrl = detailUrl,
            coverUrl = cover?.let { if (it.startsWith("http")) it else "https:$it" },
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = extractJson(obj, ir.optJSONObject("uploader"), "brand")?.let { setOf(it) } ?: emptySet(),
            state = null,
            source = source,
            contentRating = contentRating(),
        )
    }

    // ---- Detail / Gallery / Content ----

    override suspend fun getDetails(manga: Content): Content = manga

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val gr = ruleJson.optJSONObject("galleryRule")
            ?: throw ParseException("No galleryRule in ${source.name}", source.name)
        val pr = gr.optJSONObject("pictureRule")
            ?: throw ParseException("No pictureRule in ${source.name}", source.name)

        val url = chapter.url.toAbsoluteUrl(domain)
        val response = webClient.httpGet(url)
        val doc = response.parseHtml()
        return parseContentPages(doc, pr, url)
    }

    private fun parseContentPages(doc: Document, pr: JSONObject, baseUrl: String): List<ContentPage> {
        val itemSel = pr.optJSONObject("item")?.optString("selector", "")
        val urlObj = pr.optJSONObject("url")

        val items = if (!itemSel.isNullOrEmpty()) doc.select(itemSel) else doc.select("img")
        return items.mapIndexed { i, el ->
            val imgUrl = (if (urlObj != null) extractHtml(el, urlObj) else null)
                ?: el.attr("src").ifEmpty { el.attr("data-src") }.takeIf { it.isNotEmpty() }
                ?: el.attr("href").takeIf { it.isNotEmpty() }
                ?: throw ParseException("No image URL found at index $i", baseUrl)
            ContentPage(
                id = generateUid("$baseUrl#$i"),
                url = imgUrl.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            )
        }
    }

    // ---- Selector extraction ----

    private fun extractHtml(el: Element, selObj: JSONObject?): String? {
        if (selObj == null) return null
        val sel = selObj.optString("selector", "").ifEmpty { "this" }
        val funStr = selObj.optString("fun", "text")
        val param = selObj.optString("param", "")
        val regex = selObj.optString("regex", "")
        val replacement = selObj.optString("replacement", "$1")

        val target = if (sel == "this") el else el.selectFirst(sel) ?: return null

        var result: String = when (funStr) {
            "html" -> target.html()
            "attr" -> target.attr(param.ifEmpty { "href" })
            else -> target.wholeText().ifEmpty { target.text() }
        }.trim()

        if (regex.isNotEmpty()) {
            result = Regex(regex, RegexOption.DOT_MATCHES_ALL).find(result)?.let { m ->
                var r = replacement
                m.groupValues.forEachIndexed { idx, gv -> r = r.replace("$$idx", gv) }
                r
            } ?: result
        }

        return result.takeIf { it.isNotEmpty() }
    }

    private fun extractJson(obj: JSONObject, selObj: JSONObject?, defaultKey: String): String? {
        if (selObj == null) return obj.optString(defaultKey, null)
        val path = selObj.optString("path", "")
        if (path.isEmpty()) return obj.optString(defaultKey, null)

        val keys = path.removePrefix("$.").split(".")
        var cur: Any? = obj
        for (k in keys) {
            cur = (cur as? JSONObject)?.opt(k) ?: return null
        }
        return cur?.toString()
    }

    private fun extractRating(el: Element, selObj: JSONObject?): Float {
        val text = extractHtml(el, selObj) ?: return RATING_UNKNOWN
        // Yealico rating replacement often contains math like "$1/2"
        return try {
            text.toFloat()
        } catch (_: NumberFormatException) {
            // Try parsing expressions like "4/2" or "8.5"
            val cleaned = text.replace(Regex("[^0-9./]"), "")
            val parts = cleaned.split("/")
            if (parts.size == 2) {
                parts[0].toFloatOrNull()?.div(parts[1].toFloatOrNull() ?: 1f) ?: RATING_UNKNOWN
            } else {
                cleaned.toFloatOrNull() ?: RATING_UNKNOWN
            }
        }
    }

    private fun buildDetailUrl(idCode: String): String {
        var url = ruleJson.optString("galleryUrl", ruleJson.optString("detailUrl", ""))
        url = url.replace("{idCode:}", idCode).replace("{page:1}", "1")
        if (url.isEmpty()) url = "https://$domain/$idCode"
        return url
    }

    // ---- Category parsing ----

    private fun parseCategories(): List<CategoryPage> {
        val cats = ruleJson.optJSONArray("categories") ?: return emptyList()
        val list = mutableListOf<CategoryPage>()
        for (i in 0 until cats.length()) {
            val cat = cats.getJSONObject(i)
            list.add(CategoryPage(
                cid = cat.optInt("cid", i + 1),
                title = cat.optString("title", "Page ${i + 1}"),
                url = cat.optString("url", ""),
            ))
        }
        return list
    }

    // ---- Resource loading ----

    companion object {
        private val DOMAIN_RE = Regex("https?://([^/:]+)")

        fun loadRuleJson(ruleFileName: String): JSONObject? {
            val resourcePath = "yealico_rules/$ruleFileName"
            val stream = YealicoRuleParser::class.java.classLoader
                .getResourceAsStream(resourcePath) ?: return null
            return stream.use { JSONObject(it.bufferedReader().readText()) }
        }

        fun listAvailableRules(): List<String> {
            val resourceDir = "yealico_rules"
            val classLoader = YealicoRuleParser::class.java.classLoader
            val names = mutableListOf<String>()
            try {
                val url = classLoader.getResource(resourceDir) ?: return names
                if (url.protocol == "file") {
                    java.io.File(url.toURI()).listFiles()?.forEach { f ->
                        if (f.name.endsWith(".json") && !f.name.startsWith("_")) {
                            names.add(f.name)
                        }
                    }
                }
            } catch (_: Exception) {}
            return names.sorted()
        }
    }
}
