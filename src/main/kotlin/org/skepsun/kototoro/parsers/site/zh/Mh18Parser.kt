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

    // 标签列表（固定）
    private val tagTags: List<ContentTag> = TAGS.map { ContentTag(it, it, source) }

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
        val selectedTag = filter.tags.firstOrNull()
        val url = when {
            selectedTag == null || selectedTag.key == "/manga" || selectedTag.title == "全部" -> "https://${domain}/?page=$page"
            typeTags.any { it.key == selectedTag.key } -> "https://${domain}${selectedTag.key}?page=$page"
            else -> {
                // 标签筛选（genre）
                val tag = selectedTag.title.urlEncoded()
                "https://${domain}/search/genre/$tag?page=$page"
            }
        }
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseComicCards(resp.parseHtml())
    }

    private suspend fun search(keyword: String, page: Int): List<Content> {
        val url = "https://${domain}/search?keyword=${keyword.urlEncoded()}&page=$page"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseComicCards(resp.parseHtml())
    }

    private fun parseComicCards(doc: Document): List<Content> {
        val result = mutableListOf<Content>()
        doc.select("div.pb-2").forEach { item ->
            val href = item.selectFirst("a")?.attr("href") ?: return@forEach
            val title = item.selectFirst("h3")?.text()?.trim().orEmpty()
            val cover = item.selectFirst("img")?.attr("src")
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

    companion object {
        private val TAGS = listOf(
            "萝莉控", "强奸", "堕落", "人妻", "校园", "女优", "性转", "冒险", "乱伦",
            "职场", "萝莉", "古装", "未来", "动画改编", "欧美", "韩漫", "大叔", "自拍",
            "熟女", "SM", "女仆", "新婚", "御姐", "恋足", "道具", "可爱", "恋母", "人妻控",
            "cosplay", "女装子", "唯美", "魔幻", "换妻", "早熟萝莉", "欧美人妻", "刺激",
            "老师", "姐姐", "世界", "一见钟情", "身体", "刺激性爱", "母亲", "兄妹",
            "大叔萝莉", "虐", "惊悚", "同人", "双飞", "处女座", "浴室", "护士",
            "办公室", "后宫", "女友", "萝莉塔", "女上位", "婊子", "偷窥", "男同",
            "正太", "秘密关系", "全彩", "修复", "熟女控", "小姐姐", "催眠", "系列",
            "美少女", "恋兄", "恋父", "恋童", "热辣", "矮子", "老师诱惑", "强暴", "迷药",
            "青春", "恋爱", "口交", "御姐萝莉", "视觉", "制服", "性虐", "爆笑",
            "三上悠亚", "爱丽丝", "催眠调教", "肉番", "自慰", "姐弟", "可爱萝莉",
            "偷情", "女友妈妈", "童颜巨乳", "恐怖", "人妻熟女", "男友", "性奴", "多人",
            "女同", "强制", "打搅", "业界", "表妹", "乱交", "暴力", "性奴隶",
            "强制性爱", "外遇", "恶女", "口活", "人妻合集", "裸体", "御姐控", "人妻调教",
            "少女", "女儿", "虐待", "母子", "变态", "妻子", "战斗", "户外", "捆绑",
            "强迫", "出租屋", "母女", "帅气", "乱伦人妻", "萝莉合集", "男娘", "恋姐",
            "媚药", "性转男娘", "阴毛", "伦理", "帝王", "外甥", "叔叔", "精油按摩",
            "阴部", "邻居", "车震", "父女", "老师学生", "人妻骚货", "诱惑", "乱伦",
            "爆乳", "强制口交", "明星", "和服", "手淫", "后入", "乱伦合集", "调教",
            "兄弟", "刺激性爱调教", "前妻", "母亲合集", "媚药调教", "接吻", "恋子",
            "王爷", "颜射", "秘书", "妊娠", "小鲜肉", "绑缚", "妹妹", "被迫", "妹控",
            "邻家女孩", "女优调教", "乳牛", "厨师", "人妻合集调教", "隐居", "母控",
            "异族", "野外", "卫兵", "感情", "肌肉男", "大叔控", "肛交", "禁忌", "一夜情",
            "金发", "人妻寝取", "性感", "奴隶", "深夜", "结婚", "熟睡", "全裸", "艺术家",
            "强制性交", "巨乳", "搞笑", "魔物娘", "中文", "恋母合集", "校园调教", "学院",
            "狼少女", "女王", "旅馆", "后辈", "人妻强暴", "情节", "母女三人行", "恋子合集",
            "战车", "孤独", "轻松", "婴儿", "牛郎", "童年", "母亲调教", "命令", "医师",
            "教师", "运动", "人外娘", "催眠强奸", "少女强奸", "养父", "家庭乱伦", "兽耳娘",
            "戏弄", "自嗨", "贵族", "警察", "父亲", "狗娘", "动漫改编", "姐弟乱伦", "逆推",
            "主动", "少女合集", "酒店", "偷窥强奸", "妹妹合集", "绿帽", "按摩", "面具",
            "扒衣服", "性技", "动漫", "少女性交", "摸胸", "骑乘位", "少女强暴", "母乳",
            "扮演", "质子皇子", "控制", "绝顶", "肉体", "盗贼", "后宫向", "诱惑性",
            "动画", "侍女", "博美", "美女", "失身", "露出", "樱花", "口射", "姐姐合集",
            "特殊能力", "温泉", "大奶", "爸爸", "偷窥内裤", "同居", "兽耳", "男主", "女主",
            "夫妇", "老师合集", "搭讪", "女上司", "肉感", "百合", "青梅竹马", "人妻强奸",
            "处男", "虎纹鲨", "忍者", "处女", "社长", "性奴合集", "激情", "猛男", "群P",
            "酒吧", "姐妹", "校园生活", "激情性爱", "邻居关系", "看着做", "流奶", "弟弟",
            "婚纱", "保姆", "人体", "超能力", "人妻寝取られ", "爱爱", "换妻调教", "看着",
            "美人鱼", "牧场", "舔阴", "欢喜", "女神", "少数派", "全家乱伦", "野兽", "春药",
            "口口", "按摩师", "字母圈", "名器", "魔族", "大屁股", "戴绿帽", "内射", "痴女",
            "侠客", "性瘾", "弃妇", "寝取", "温馨", "放尿", "性爱", "家庭教师", "超巨乳",
            "变装", "镜子", "人妻乱伦", "住院", "双胞胎", "娃娃脸", "小姨子", "强制性交",
            "甜蜜", "睡眠", "萝莉强奸", "少女调教", "主人", "双性", "足控", "卫生间", "护士制服",
            "寝取调教", "姨妈", "亲妈", "迷奸", "强制榨精", "脚臭", "群交", "木下凛子", "萌妹",
            "黑妹", "露出调教", "女巫", "强迫性爱", "孕妇", "日本", "魔法少女", "兔女郎"
        )
    }
}
