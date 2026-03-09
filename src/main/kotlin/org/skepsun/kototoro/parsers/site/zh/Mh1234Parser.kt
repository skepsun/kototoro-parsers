@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 漫画1234（b.amh1234.com）
 * 参考 venera-configs/mh1234.js
 */
@MangaSourceParser("MH1234", "漫画1234", "zh")
internal class Mh1234Parser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MH1234, pageSize = 20) {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("m.wmh1234.com")
    private val imageCdnDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("wmh1234.wszwhg.net")
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY)

    private val categoryTags: List<MangaTag> = TAGS.zip(CATEGORY_PARAMS).map { (title, param) ->
        MangaTag(title, CATEGORY_PREFIX + param, source)
    }
    private val typeTags: List<MangaTag> = TYPE_OPTIONS.map { MangaTag(it.second, TYPE_PREFIX + it.first, source) }
    private val statusTags: List<MangaTag> = STATUS_OPTIONS.map { MangaTag(it.second, STATUS_PREFIX + it.first, source) }
    private val regionTags: List<MangaTag> = REGION_OPTIONS.map { MangaTag(it.second, REGION_PREFIX + it.first, source) }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = (categoryTags + typeTags + statusTags + regionTags).toSet(),
            tagGroups = listOf(
                MangaTagGroup("题材", categoryTags.toSet()),
                MangaTagGroup("类型", typeTags.toSet()),
                MangaTagGroup("状态", statusTags.toSet()),
                MangaTagGroup("地区", regionTags.toSet()),
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Referer", "https://${domain}/")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val selection = filter.toSelection()
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page, order)
        }
        val path = if (page <= 1) {
            if (selection.category.isNotEmpty()) "/category/${selection.category}/" else "/category/"
        } else {
            if (selection.category.isNotEmpty()) "/category/${selection.category}/page/$page" else "/category/page/$page"
        }
        val url = "https://${domain}$path"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseList(resp.parseHtml())
    }

    private suspend fun search(keyword: String, page: Int, order: SortOrder): List<Manga> {
        val path = if (page <= 1) "/search" else "/search/page/$page"
        val url = "https://${domain}$path?key=${keyword.urlEncoded()}"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        return parseList(resp.parseHtml())
    }

    private fun parseList(doc: Document): List<Manga> {
        val cards = doc.select("article.comic-card")
        return cards.mapNotNull { card ->
            val anchor = card.selectFirst("a.comic-card__link") ?: return@mapNotNull null
            val id = anchor.attr("href").substringAfterLast("/").substringBefore(".html")
            val title = card.selectFirst(".comic-card__title")?.text()?.trim().orEmpty()
            val cover = card.selectFirst(".comic-card__image")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            Manga(
                id = generateUid(id),
                url = id,
                publicUrl = "https://${domain}/comic/$id.html",
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

    override suspend fun getDetails(manga: Manga): Manga {
        val detailUrl = "https://${domain}/comic/${manga.url}.html"
        val resp = webClient.httpGet(detailUrl, getRequestHeaders())
        if (!resp.isSuccessful) return manga
        val doc = resp.parseHtml()
        val title = doc.selectFirst(".comic-hero__title")?.text()?.trim().orEmpty()
            .ifEmpty { manga.title }
        val cover = doc.selectFirst(".comic-hero__image")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?: manga.coverUrl
        val desc = doc.selectFirst(".comic-desc")?.text()?.trim().orEmpty()
        
        val metaItems = doc.select(".comic-hero__meta .meta-item")
        val authors = metaItems.select(":contains(作者)").text().substringAfter("作者：").trim()
        val tagsFromPage = metaItems.select(":contains(题材)").text().substringAfter("题材：").split(" ").map { it.trim() }.filter { it.isNotEmpty() }

        val chapters = parseChapters(doc, manga)
        val tagSet = buildSet {
            if (authors.isNotEmpty()) add(MangaTag(authors, authors, source))
            tagsFromPage.forEach { add(MangaTag(it, it, source)) }
        }
        return manga.copy(
            title = title,
            coverUrl = cover,
            description = desc.ifEmpty { manga.description },
            tags = if (tagSet.isNotEmpty()) tagSet else manga.tags,
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://${domain}/comic/${chapter.url}.html"
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        val doc = resp.parseHtml()
        val images = doc.select("img.reader-image")
        return images.mapIndexedNotNull { index, img ->
            val full = img.attr("data-src").ifEmpty { img.attr("src") }
            if (full.isEmpty()) return@mapIndexedNotNull null
            MangaPage(
                id = generateUid("$full-$index"),
                url = full,
                preview = full,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private fun parseChapters(doc: Document, manga: Manga): List<MangaChapter> {
        val items = doc.select(".chapter-list .chapter-item")
        if (items.isEmpty()) return emptyList()
        return items.mapIndexed { index, a ->
            val href = a.attr("href")
            val chapName = a.selectFirst(".chapter-title")?.text()?.trim() ?: a.text().trim()
            val chapterUrl = href.substringAfter("/comic/").substringBefore(".html")
            MangaChapter(
                id = generateUid("$chapterUrl-${manga.id}"),
                url = chapterUrl,
                title = chapName.ifEmpty { "Ch ${index + 1}" },
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }
    }

    private fun MangaListFilter.toSelection(): FilterSelection {
        var category = ""
        var type = DEFAULT_TYPE
        var status = DEFAULT_STATUS
        var region = DEFAULT_REGION
        tags.forEach { tag ->
            when {
                tag.key.startsWith(CATEGORY_PREFIX) -> category = tag.key.removePrefix(CATEGORY_PREFIX)
                tag.key.startsWith(TYPE_PREFIX) -> type = tag.key.removePrefix(TYPE_PREFIX)
                tag.key.startsWith(STATUS_PREFIX) -> status = tag.key.removePrefix(STATUS_PREFIX)
                tag.key.startsWith(REGION_PREFIX) -> region = tag.key.removePrefix(REGION_PREFIX)
            }
        }
        return FilterSelection(category, type, status, region)
    }

    private fun SortOrder.toSortParam(): String = when (this) {
        SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "click"
        SortOrder.NEWEST, SortOrder.NEWEST_ASC, SortOrder.ADDED, SortOrder.ADDED_ASC -> "post"
        else -> "update"
    }


    private data class FilterSelection(
        val category: String,
        val type: String,
        val status: String,
        val region: String,
    )

    private companion object {
        private const val CATEGORY_PREFIX = "cat:"
        private const val TYPE_PREFIX = "type:"
        private const val STATUS_PREFIX = "status:"
        private const val REGION_PREFIX = "region:"
        private const val DEFAULT_TYPE = "-全部"
        private const val DEFAULT_STATUS = "-全部"
        private const val DEFAULT_REGION = "-全部"

        val TYPE_OPTIONS = listOf(
            "-全部" to "-全部",
            "ertong-儿童漫画" to "ertong-儿童漫画",
            "shaonian-少年漫画" to "shaonian-少年漫画",
            "shaonv-少女漫画" to "shaonv-少女漫画",
            "qingnian-青年漫画" to "qingnian-青年漫画",
            "bailingmanhua-白领漫画" to "bailingmanhua-白领漫画",
            "tongrenmanhua-同人漫画" to "tongrenmanhua-同人漫画",
        )

        val STATUS_OPTIONS = listOf(
            "-全部" to "-全部",
            "wanjie-已完结" to "wanjie-已完结",
            "lianzai-连载中" to "lianzai-连载中",
        )

        val REGION_OPTIONS = listOf(
            "-全部" to "-全部",
            "rhmh-日韩" to "rhmh-日韩",
            "dlmh-大陆" to "dlmh-大陆",
            "gtmh-港台" to "gtmh-港台",
            "taiwan-台湾" to "taiwan-台湾",
            "ommh-欧美" to "ommh-欧美",
            "hanguo-韩国" to "hanguo-韩国",
            "qtmg-其他" to "qtmg-其他",
        )

        val TAGS = listOf(
            "全部", "少年热血", "武侠格斗", "科幻魔幻", "竞技体育", "爆笑喜剧", "侦探推理", "恐怖灵异", "耽美人生",
            "少女爱情", "恋爱生活", "生活漫画", "战争漫画", "故事漫画", "其他漫画", "爱情", "唯美", "武侠", "玄幻",
            "后宫", "治愈", "励志", "古风", "校园", "虐心", "魔幻", "冒险", "欢乐向", "节操", "悬疑", "历史", "职场",
            "神鬼", "明星", "穿越", "百合", "西方魔幻", "纯爱", "音乐舞蹈", "轻小说", "侦探", "伪娘", "仙侠", "四格",
            "剧情", "萌系", "东方", "性转换", "宅系", "美食", "脑洞", "惊险", "爆笑", "都市", "蔷薇", "恋爱", "格斗",
            "科幻", "魔法", "奇幻", "热血", "其他", "搞笑", "生活", "恐怖", "架空", "竞技", "战争", "搞笑喜剧", "青春",
            "浪漫", "爽流", "神话", "轻松", "日常", "家庭", "婚姻", "动作", "战斗", "异能", "内涵", "同人", "惊奇",
            "正剧", "推理", "宠物", "温馨", "异世界", "颜艺", "惊悚", "舰娘","机战", "彩虹", "耽美", "轻松搞笑",
            "修真恋爱架空", "复仇", "霸总", "段子", "逆袭", "烧脑", "娱乐圈", "纠结", "感动", "豪门", "体育", "机甲",
            "末世", "灵异", "僵尸", "宫廷", "权谋", "未来", "科技", "商战", "乡村", "震撼", "游戏", "重口味", "血腥",
            "逗比", "丧尸", "神魔", "修真", "社会", "召唤兽", "装逼", "新作", "漫改", "真人", "运动", "高智商", "悬疑推理",
            "机智", "史诗", "萝莉", "宫斗", "御姐", "恶搞", "精品", "日更", "小说改编", "防疫", "吸血", "暗黑", "总裁",
            "重生", "大女主", "系统", "神仙", "末日", "怪物", "妖怪", "修仙", "宅斗", "神豪", "高甜", "电竞", "豪快",
            "猎奇", "多世界", "性转", "少女", "改编", "女生", "乙女", "男生", "兄弟情", "智斗", "少男", "连载", "奇幻冒险",
            "古风穿越", "浪漫爱情", "古装", "幽默搞笑", "偶像", "小僵尸", "BL", "少年", "橘味", "情感", "经典",
            "腹黑", "都市大女主", "致郁", "美少女", "少儿", "暖萌", "长条", "限制级", "知音漫客", "氪金", "独家",
            "亲情", "现代", "武侠仙侠", "西幻", "超级英雄", "女神", "幻想", "欧风", "养成", "动作冒险", "GL", "橘调",
            "悬疑灵异", "古代宫廷", "欧式宫廷", "游戏竞技", "橘系", "奇幻爱情", "架空世界", "ゆり", "福瑞", "秀吉", "现代言情",
            "古代言情", "豪门总裁", "现言萌宝", "玄幻言情", "虐渣", "团宠", "古言萌宝", "现言甜宠", "古言脑洞", "AA", "金手指",
            "玄幻脑洞", "都市脑洞", "甜宠", "伦理", "生存", "TL", "悬疑脑洞", "黑暗", "独特", "成长", "幻想言情", "直播",
            "游戏体育", "现言脑洞", "音乐", "双男主", "迪化", "LGBTQ+", "正能量", "军事", "ABO", "悬疑恐怖",
            "玄幻科幻", "投稿", "种田", "经营", "反套路", "无节操", "强强", "克苏鲁", "无敌流", "冒险热血", "畅销",
            "大人系", "宅向", "萌娃", "宠兽", "异形", "撒糖", "诡异", "言情", "西方", "滑稽搞笑", "同居", "人外",
            "白切黑", "并肩作战", "救赎", "戏精", "美强惨", "非人类", "原创", "黑白漫", "无限流",
            "升级", "爽", "轻橘", "女帝", "偏执", "自由", "星际", "可盐可甜", "反差萌", "聪颖", "智商在线",
            "倔强", "狼人", "欢喜冤家", "吸血鬼", "萌宠", "学校", "台湾作品", "彩色", "武术", "短篇", "契约", "魔王",
            "无敌", "美女", "暧昧", "网游", "宅男", "追逐梦想", "冒险奇幻", "疯批", "中二", "召唤", "法宝", "钓系", "鬼怪",
            "占有欲", "阳光", "元气", "强制爱", "黑道", "马甲", "阴郁", "忧郁", "哲理", "病娇", "喜剧", "江湖恩怨",
            "相爱相杀", "萌", "SM", "精选", "生子", "年下", "18+限制", "日久生情", "梦想", "多攻", "竹马", "骨科", "gnbq"
        )

        val CATEGORY_PARAMS = listOf(
            "", "shaonianrexue", "wuxiagedou", "kehuanmohuan", "jingjitiyu", "baoxiaoxiju", "zhentantuili", "kongbulingyi",
            "danmeirensheng", "shaonvaiqing", "lianaishenghuo", "shenghuomanhua", "zhanzhengmanhua", "gushimanhua",
            "qitamanhua", "aiqing", "weimei", "wuxia", "xuanhuan", "hougong", "zhiyu", "lizhi", "gufeng", "xiaoyuan", "nuexin",
            "mohuan", "maoxian", "huanlexiang", "jiecao", "xuanyi", "lishi", "zhichang", "shengui", "mingxing", "chuanyue",
            "baihe", "xifangmohuan", "chunai", "yinyuewudao", "qingxiaoshuo", "zhentan", "weiniang", "xianxia", "sige", "juqing",
            "mengxi", "dongfang", "xingzhuanhuan", "zhaixi", "meishi", "naodong", "jingxian", "baoxiao", "dushi", "qiangwei",
            "lianai", "gedou", "kehuan", "mofa", "qihuan", "rexue", "qita", "gaoxiao", "shenghuo", "kongbu", "jiakong", "jingji",
            "zhanzheng", "gaoxiaoxiju", "qingchun", "langman", "shuangliu", "shenhua", "qingsong", "richang", "jiating", "hunyin",
            "dongzuo", "zhandou", "yineng", "neihan", "tongren", "jingqi", "zhengju", "tuili", "chongwu", "wenxin", "yishijie",
            "yanyi", "jingsong", "jianniang", "jizhan", "caihong", "danmei", "qingsonggaoxiao", "xiuzhenlianaijiakong", "fuchou",
            "bazong", "duanzi", "nixi", "shaonao", "yulequan", "jiujie", "gandong", "haomen", "tiyu", "jijia", "moshi", "lingyi",
            "jiangshi", "gongting", "quanmou", "weilai", "keji", "shangzhan", "xiangcun", "zhenhan", "youxi",
            "zhongkouwei", "xuexing", "doubi", "sangshi", "shenmo", "xiuzhen", "shehui", "zhaohuanshou", "zhuangbi",
            "xinzuo", "mangai", "zhenren", "yundong", "gaozhishang", "xuanyituili", "jizhi", "shishi", "luoli","gongdou",
            "yujie", "egao", "jingpin", "rigeng", "xiaoshuogaibian", "fangyi", "xixie", "anhei", "zongcai", "zhongsheng",
            "danvzhu", "xitong", "shenxian", "mori", "guaiwu", "yaoguai", "xiuxian", "zhaidou", "shenhao", "gaotian",
            "dianjing", "haokuai", "lieqi", "duoshijie", "xingzhuan", "shaonv", "gaibian", "nvsheng", "yinv", "nansheng",
            "xiongdiqing", "zhidou", "shaonan", "lianzai", "qihuanmaoxian", "gufengchuanyue", "langmanaiqing", "guzhuang",
            "youmogaoxiao", "ouxiang", "xiaojiangshi", "BL", "shaonian", "juwei", "qinggan", "jingdian",
            "fuhei", "dushidanvzhu", "zhiyu2", "meishaonv", "shaoer", "nuanmeng", "changtiao", "xianzhiji", "zhiyinmanke",
            "kejin", "dujia", "qinqing", "xiandai", "wuxiaxianxia", "xihuan", "chaojiyingxiong", "nvshen", "huanxiang",
            "oufeng", "yangcheng", "dongzuomaoxian", "GL", "judiao", "xuanyilingyi", "gudaigongting", "oushigongting",
            "youxijingji", "juxi", "qihuanaiqing", "jiakongshijie", "unknown", "furui", "xiuji", "xiandaiyanqing", "gudaiyanqing",
            "haomenzongcai", "xianyanmengbao", "xuanhuanyanqing", "nuezha", "tuanchong", "guyanmengbao", "xianyantianchong",
            "guyannaodong", "AA", "jinshouzhi", "xuanhuannaodong", "dushinaodong", "tianchong", "lunli", "shengcun", "TL",
            "xuanyinaodong", "heian", "dute", "chengzhang", "huanxiangyanqing", "zhibo", "youxitiyu", "xianyannaodong",
            "yinyue", "shuangnanzhu", "dihua", "LGBTQ", "zhengnengliang", "junshi", "ABO", "xuanyikongbu", "xuanhuankehuan", "tougao",
            "zhongtian", "jingying", "fantaolu", "wujiecao", "qiangqiang", "kesulu", "wudiliu", "maoxianrexue", "changxiao",
            "darenxi", "zhaixiang", "mengwa", "chongshou", "yixing", "satang", "guiyi", "yanqing", "xifang", "huajigaoxiao", "tongju",
            "renwai", "baiqiehei", "bingjianzuozhan", "jiushu", "xijing", "meiqiangcan", "feirenlei", "yuanchuang", "heibaiman",
            "wuxianliu", "shengji", "shuang", "qingju", "nvdi", "pianzhi", "ziyou", "xingji", "keyanketian", "fanchameng", "congying",
            "zhishangzaixian", "juejiang", "langren", "huanxiyuanjia", "xixiegui", "mengchong", "xuexiao", "taiwanzuopin", "caise",
            "wushu", "duanpian", "qiyue", "mowang", "wudi", "meinv", "aimei", "wangyou", "zhainan", "zhuizhumengxiang", "maoxianqihuan",
            "fengpi", "zhonger", "zhaohuan", "fabao", "diaoxi", "guiguai", "zhanyouyu", "yangguang", "yuanqi", "qiangzhiai", "heidao",
            "majia", "yinyu", "youyu", "zheli", "bingjiao", "xiju", "jianghuenyuan", "xiangaixiangsha", "meng", "SM", "jingxuan", "shengzi",
            "nianxia", "18xianzhi", "rijiushengqing", "mengxiang", "duogong", "zhuma", "guke", "gnbq"
        )
    }
}
