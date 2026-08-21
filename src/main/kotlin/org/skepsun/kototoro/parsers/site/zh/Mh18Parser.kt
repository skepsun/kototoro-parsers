@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
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
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 18漫画（18mh.org）
 */
@ContentSourceParser("MH18", "18漫画", "zh", type=ContentType.HENTAI_MANGA)
internal class Mh18Parser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.MH18, pageSize = 20) {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain(
        "18mh.org",
    )
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    private val typeTags = listOf(
        "全部" to "/manga",
        "韓漫" to "/manga-genre/hanman",
        "真人寫真" to "/manga-genre/zhenrenxiezhen",
        "日漫" to "/manga-genre/riman",
        "AI寫真" to "/manga-genre/aixiezhen",
        "熱門漫畫" to "/manga-genre/hots",
    ).map { ContentTag(it.first, it.second, source) }

    // 标签列表（固定，key 为站点实际的拼音路径，参考 mihon/Venera 可用的 mh18 源）
    private val tagTags: List<ContentTag> = listOf(
        "多人" to "/manga-tag/duoren",
        "慾望" to "/manga-tag/yuwang",
        "正妹" to "/manga-tag/zhengmei",
        "同居" to "/manga-tag/tongju",
        "女學生" to "/manga-tag/nxuesheng",
        "劇情" to "/manga-tag/juqing",
        "偷情" to "/manga-tag/touqing",
        "校园" to "/manga-tag/xiaoyuan",
        "逆襲" to "/manga-tag/nixi",
        "办公室" to "/manga-tag/bangongshi",
        "誘惑" to "/manga-tag/youhuo",
        "反转" to "/manga-tag/fanzhuan",
        "熟女" to "/manga-tag/shun",
        "人妻" to "/manga-tag/renqi",
        "初戀" to "/manga-tag/chulian",
        "少妇" to "/manga-tag/shaofu",
        "刺激" to "/manga-tag/ciji",
        "女大学生" to "/manga-tag/ndaxuesheng",
        "治疗" to "/manga-tag/zhiliao",
        "超能力" to "/manga-tag/chaonengli",
        "浪漫校园" to "/manga-tag/langmanxiaoyuan",
        "戏剧" to "/manga-tag/xiju",
        "学姐" to "/manga-tag/xuejie",
        "大学生" to "/manga-tag/daxuesheng",
        "泳衣" to "/manga-tag/yongyi",
        "暧昧" to "/manga-tag/aimei",
        "写真" to "/manga-tag/xiezhen",
        "女神" to "/manga-tag/nshen",
        "大尺度" to "/manga-tag/dachidu",
        "纯情警察" to "/manga-tag/chunqingjingcha",
    ).map { (title, path) -> ContentTag(title, path, source) }

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(
            availableTags = (typeTags + tagTags).toSet(),
            tagGroups = listOf(
                ContentTagGroup("类型", typeTags.toSet()),
                ContentTagGroup("标签", tagTags.toSet()),
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Referer", "https://${domain}/")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        val url = buildListUrl(page, filter) ?: return emptyList()
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseComicCards(resp.parseHtml())
    }

    internal fun buildListUrl(page: Int, filter: ContentListFilter): String? {
        val selectedTag = filter.tags.firstOrNull()
        val path = when {
            selectedTag == null || selectedTag.key == "/manga" || selectedTag.title == "全部" -> "/manga"
            else -> selectedTag.key
        }
        if (!path.startsWith("/")) return null
        // 站点使用路径分页：{分类}/page/{n}
        return "https://${domain}$path/page/$page"
    }

    private suspend fun search(keyword: String, page: Int): List<Content> {
        // 站点搜索路径为 /s/{关键词}?page={n}
        val url = "https://${domain}/s/${keyword.urlEncoded()}?page=$page"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseComicCards(resp.parseHtml())
    }

    internal fun parseComicCards(doc: Document): List<Content> {
        val result = mutableListOf<Content>()
        doc.select("div.pb-2").forEach { item ->
            val href = item.selectFirst("a")?.attr("href") ?: return@forEach
            val title = item.selectFirst("h3")?.text()?.trim().orEmpty()
            val img = item.selectFirst("img")
            val cover = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            val relativeUrl = href
                .replace(Regex("^https?://[^/]+"), "")
                .let { if (it.startsWith("/")) it else "/$it" }
                .trim()
            val absoluteUrl = "https://${domain}$relativeUrl"
            if (href.isNotEmpty() && title.isNotEmpty()) {
                result.add(
                    Content(
                        id = generateUid(relativeUrl),
                        url = relativeUrl,
                        publicUrl = absoluteUrl,
                        coverUrl = cover,
                        title = title,
                        altTitles = emptySet(),
                        rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                        tags = emptySet(),
                        authors = emptySet(),
                        state = null,
                        source = source,
                        contentRating = ContentRating.ADULT,
                    )
                )
            }
        }
        return result
    }

    override suspend fun getDetails(manga: Content): Content {
        val detailsUrl = if (manga.url.startsWith("http")) manga.url else "https://${domain}${manga.url}"
        val resp = webClient.httpGet(detailsUrl, getRequestHeaders())
        if (!resp.isSuccessful) return manga
        val doc = resp.parseHtml()
        val title = doc.selectFirst(".text-xl")?.text()?.trim()?.split("   ")?.firstOrNull().orEmpty().ifEmpty { manga.title }
        val cover = doc.selectFirst(".object-cover")?.attr("src") ?: manga.coverUrl
        val desc = sequenceOf(
            doc.selectFirst("p.text-medium")?.text(),
            doc.selectFirst("div.prose")?.text(),
            doc.selectFirst("p.text-base")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        val infos = doc.select("div.py-1")
        val tagsMap = linkedMapOf<String, MutableList<String>>(
            "作者" to mutableListOf(),
            "类型" to mutableListOf(),
            "标签" to mutableListOf(),
        )
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
        val mangaId = doc.selectFirst("#mangachapters")?.attr("data-mid")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("\"mid\"\\s*:\\s*\"?(\\d+)\"?").find(doc.html())?.groupValues?.getOrNull(1).orEmpty()
        val chapters = when {
            mangaId.isNotEmpty() -> fetchChapters(mangaId).ifEmpty { parseChaptersFromDoc(doc) }
            else -> parseChaptersFromDoc(doc)
        }
        val tagSet = tagsMap.values.flatten().map { ContentTag(it, it, source) }.toSet()

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = desc.ifEmpty { manga.description },
            tags = if (tagSet.isNotEmpty()) tagSet else manga.tags,
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    private suspend fun fetchChapters(mid: String): List<ContentChapter> {
        val url = "https://${domain}/manga/get?mid=$mid&mode=all&t=${System.currentTimeMillis()}"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        val doc = resp.parseHtml()
        return parseChaptersFromDoc(doc)
    }

    private fun parseChaptersFromDoc(doc: Document): List<ContentChapter> {
        val items = doc.select(".chapteritem")
        if (items.isEmpty()) return emptyList()
        val chapters = mutableListOf<ContentChapter>()
        items.forEachIndexed { index, ch ->
            val a = ch.selectFirst("a") ?: return@forEachIndexed
            val ms = a.attr("data-ms")
            val cs = a.attr("data-cs")
            val name = ch.selectFirst(".chaptertitle")?.text()?.trim().orEmpty()
            val urlId = "$ms@$cs"
            if (ms.isNotEmpty() && cs.isNotEmpty()) {
                chapters.add(
                    ContentChapter(
                        id = generateUid(urlId),
                        url = urlId,
                        title = name.ifEmpty { "Ch ${index + 1}" },
                        number = (index + 1).toFloat(),
                        volume = 0,
                        scanlator = null,
                        uploadDate = 0,
                        branch = null,
                        source = source,
                    )
                )
            }
        }
        return chapters
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val ids = chapter.url.split("@")
        if (ids.size < 2) return emptyList()
        val url = "https://${domain}/chapter/getcontent?m=${ids[0]}&c=${ids[1]}"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        val doc = resp.parseHtml()
        val imgs = doc.select("#chapcontent img")
        return imgs.mapIndexedNotNull { index, img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.isEmpty()) null else ContentPage(
                id = generateUid("$src-$index"),
                url = src,
                preview = src,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
}
