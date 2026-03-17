@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.json.mapJSONIndexed
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * GoDa 漫画（godamh.com）
 * 参考 venera-configs/goda.js
 */
@ContentSourceParser("GODA", "GoDa漫画", "zh")
internal class GodaParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.GODA, pageSize = 20) {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("godamh.com")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    private val apiDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("api-get-v3.mgsearcher.com")
    private val imgDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("t40-1-4.g-mh.online")

    private val typeTags: List<ContentTag> = listOf(
        "全部" to "/manga",
        "韩漫" to "/manga-genre/kr",
        "热门漫画" to "/manga-genre/hots",
        "国漫" to "/manga-genre/cn",
        "其他" to "/manga-genre/qita",
        "日漫" to "/manga-genre/jp",
        "欧美" to "/manga-genre/ou-mei",
    ).map { (t, path) -> ContentTag(t, path, source) }

    private val tagTags: List<ContentTag> = listOf(
        "复仇" to "/manga-tag/fuchou",
        "古风" to "/manga-tag/gufeng",
        "奇幻" to "/manga-tag/qihuan",
        "逆袭" to "/manga-tag/nixi",
        "异能" to "/manga-tag/yineng",
        "宅向" to "/manga-tag/zhaixiang",
        "穿越" to "/manga-tag/chuanyue",
        "热血" to "/manga-tag/rexue",
        "纯爱" to "/manga-tag/chunai",
        "系统" to "/manga-tag/xitong",
        "重生" to "/manga-tag/zhongsheng",
        "冒险" to "/manga-tag/maoxian",
        "灵异" to "/manga-tag/lingyi",
        "大女主" to "/manga-tag/danvzhu",
        "剧情" to "/manga-tag/juqing",
        "恋爱" to "/manga-tag/lianai",
        "玄幻" to "/manga-tag/xuanhuan",
        "女神" to "/manga-tag/nvshen",
        "科幻" to "/manga-tag/kehuan",
        "魔幻" to "/manga-tag/mohuan",
        "推理" to "/manga-tag/tuili",
        "猎奇" to "/manga-tag/lieqi",
        "治愈" to "/manga-tag/zhiyu",
        "都市" to "/manga-tag/doushi",
        "异形" to "/manga-tag/yixing",
        "青春" to "/manga-tag/qingchun",
        "末日" to "/manga-tag/mori",
        "悬疑" to "/manga-tag/xuanyi",
        "修仙" to "/manga-tag/xiuxian",
        "战斗" to "/manga-tag/zhandou",
    ).map { (t, path) -> ContentTag(t, path, source) }

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        ContentListFilterOptions(
            availableTags = (typeTags + tagTags).toSet(),
            tagGroups = listOf(
                ContentTagGroup("类型", typeTags.toSet()),
                ContentTagGroup("标签", tagTags.toSet()),
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )

    private fun headers(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Referer", "https://${domain}/")
        .build()

    private fun apiBase(): String = "https://${config[apiDomain]}/api"
    private fun imageBase(): String = "https://${config[imgDomain]}"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        // Prefer search if query present
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        val selectedPath = filter.tags.firstOrNull()?.key ?: "/manga"
        val path = if (selectedPath.startsWith("/")) selectedPath else "/$selectedPath"
        val url = "https://${domain}${path}/page/$page"
        val resp = webClient.httpGet(url, headers())
        if (!resp.isSuccessful) return emptyList()
        val doc = resp.parseHtml()
        return parseComicCards(doc)
    }

    private suspend fun search(keyword: String, page: Int): List<Content> {
        val url = "https://${domain}/s/${keyword.urlEncoded()}?page=$page"
        val resp = webClient.httpGet(url, headers())
        if (!resp.isSuccessful) return emptyList()
        return parseComicCards(resp.parseHtml())
    }

    private fun parseComicCards(doc: Document): List<Content> {
        val cards = doc.select("div.pb-2")
        return cards.mapNotNull { card ->
            val anchor = card.selectFirst("a") ?: return@mapNotNull null
            val href = anchor.attr("href")
            val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            val cover = card.selectFirst("img")?.attr("src")
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
            Content(
                id = generateUid(href),
                url = href,
                publicUrl = "https://${domain}$href",
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
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val resp = webClient.httpGet("https://${domain}${manga.url}", headers())
        if (!resp.isSuccessful) return manga
        val doc = resp.parseHtml()
        val title = doc.selectFirst(".text-xl")?.text()?.trim()?.split("   ")?.firstOrNull().orEmpty()
        val cover = doc.selectFirst(".object-cover")?.attr("src") ?: manga.coverUrl
        val description = doc.selectFirst("p.text-medium")?.text()?.trim().orEmpty()
        val infos = doc.select("div.py-1")
        val tagsMap = LinkedHashMap<String, MutableList<String>>()
        tagsMap["作者"] = mutableListOf()
        tagsMap["类型"] = mutableListOf()
        tagsMap["标签"] = mutableListOf()

        infos.getOrNull(0)?.select("a > span")?.forEach {
            val name = it.text().trim().trimEnd(',')
            if (name.isNotEmpty()) tagsMap["作者"]?.add(name)
        }
        infos.getOrNull(1)?.select("a > span")?.forEach {
            val name = it.text().trim().trimEnd(',')
            if (name.isNotEmpty()) tagsMap["类型"]?.add(name)
        }
        infos.getOrNull(2)?.select("a")?.forEach {
            val name = it.text().replace("\n", "").replace(" ", "").replace("#", "")
            if (name.isNotEmpty()) tagsMap["标签"]?.add(name)
        }

        val mangaId = doc.selectFirst("#mangachapters")?.attr("data-mid") ?: ""
        val chapters = if (mangaId.isNotEmpty()) {
            fetchChapters(mangaId)
        } else emptyList()

        val tagsCombined = tagsMap.values.flatten().map { ContentTag(it, it, source) }.toSet()

        return manga.copy(
            title = if (title.isNotEmpty()) title else manga.title,
            coverUrl = cover,
            description = description.ifEmpty { manga.description },
            tags = if (tagsCombined.isNotEmpty()) tagsCombined else manga.tags,
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    private suspend fun fetchChapters(mid: String): List<ContentChapter> {
        val url = "${apiBase()}/manga/get?mid=$mid&mode=all&t=${System.currentTimeMillis()}"
        val res = webClient.httpGet(url, headers())
        if (!res.isSuccessful) return emptyList()
        val data = res.parseJsonObject().optJSONObject("data") ?: return emptyList()
        val chaptersArr = data.optJSONArray("chapters") ?: return emptyList()
        return chaptersArr.mapJSONIndexed { index, obj ->
            val ch = obj as? JSONObject ?: return@mapJSONIndexed null
            val id = ch.optString("id")
            val title = ch.optJSONObject("attributes")?.optString("title").orEmpty()
            val urlId = "$mid@$id"
            ContentChapter(
                id = generateUid(urlId),
                url = urlId,
                title = title.ifEmpty { "Ch ${index + 1}" },
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.filterNotNull()
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val ids = chapter.url.split("@")
        if (ids.size < 2) return emptyList()
        val mid = ids[0]
        val cid = ids[1]
        val url = "${apiBase()}/chapter/getinfo?m=$mid&c=$cid"
        val res = webClient.httpGet(url, headers())
        if (!res.isSuccessful) return emptyList()
        val data = res.parseJsonObject()
        val imagesArr = data.optJSONObject("data")
            ?.optJSONObject("info")
            ?.optJSONObject("images")
            ?.optJSONArray("images") ?: return emptyList()
        return imagesArr.mapJSONIndexed { index, any ->
            val obj = any as? JSONObject ?: return@mapJSONIndexed null
            val path = obj.optString("url")
            if (path.isEmpty()) return@mapJSONIndexed null
            val full = imageBase() + path
            ContentPage(
                id = generateUid("$full-$index"),
                url = full,
                preview = full,
                source = source,
            )
        }.filterNotNull()
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
}
