package org.skepsun.kototoro.parsers.site.all

import androidx.collection.ArraySet
import androidx.collection.MutableIntLongMap
import androidx.collection.MutableIntObjectMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.jsoup.internal.StringUtil
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaParserAuthProvider
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.bitmap.Rect
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections.emptyList
import java.util.concurrent.TimeUnit

private const val DOMAIN_UNAUTHORIZED = "e-hentai.org"
private const val DOMAIN_AUTHORIZED = "exhentai.org"
private val TAG_PREFIXES = arrayOf("male:", "female:", "other:")
private const val BANNED_RESPONSE_LENGTH = 256L

@MangaSourceParser("EXHENTAI", "ExHentai", type = ContentType.HENTAI_MANGA)
internal class ExHentaiParser(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.EXHENTAI, pageSize = 25), MangaParserAuthProvider, Interceptor {

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.NEWEST)

    override val configKeyDomain: ConfigKey.Domain
        get() {
            val isAuthorized = checkAuth()
            return ConfigKey.Domain(
                if (isAuthorized) DOMAIN_AUTHORIZED else DOMAIN_UNAUTHORIZED,
                if (isAuthorized) DOMAIN_UNAUTHORIZED else DOMAIN_AUTHORIZED,
            )
        }

    override val authUrl: String
        get() = "https://${domain}/bounce_login.php"

    private val ratingPattern = Regex("-?[0-9]+px")
    private val titleCleanupPattern = Regex("(\\[.*?]|\\([C0-9]*\\))")
    private val spacesCleanupPattern = Regex("(^\\s+|\\s+\$|\\s+(?=\\s))")
    private val authCookies = arrayOf("ipb_member_id", "ipb_pass_hash")
    private val suspiciousContentKey = ConfigKey.ShowSuspiciousContent(false)
    private val nextPages = MutableIntObjectMap<MutableIntLongMap>()

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isAuthorSearchSupported = true,
        )

    override suspend fun isAuthorized(): Boolean = checkAuth()

    init {
        context.cookieJar.insertCookies(DOMAIN_AUTHORIZED, "nw=1", "sl=dm_2")
        context.cookieJar.insertCookies(DOMAIN_UNAUTHORIZED, "nw=1", "sl=dm_2")
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = mapTags(),
        tagGroups = cachedTagGroups,
        availableContentTypes = EnumSet.of(
            ContentType.DOUJINSHI,
            ContentType.MANGA,
            ContentType.ARTIST_CG,
            ContentType.GAME_CG,
            ContentType.COMICS,
            ContentType.IMAGE_SET,
            ContentType.OTHER,
        ),
        availableLocales = setOf(
            Locale.JAPANESE,
            Locale.ENGLISH,
            Locale.CHINESE,
            Locale("nl"),
            Locale.FRENCH,
            Locale.GERMAN,
            Locale("hu"),
            Locale.ITALIAN,
            Locale("kr"),
            Locale("pl"),
            Locale("pt"),
            Locale("ru"),
            Locale("es"),
            Locale("th"),
            Locale("vi"),
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return getListPage(page, order, filter, updateDm = false)
    }

    private suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
        updateDm: Boolean,
    ): List<Manga> {
        val next = synchronized(nextPages) {
            nextPages[filter.hashCode()]?.getOrDefault(page, 0L) ?: 0L
        }

        if (page > 0 && next == 0L) {
            assert(false) { "Page timestamp not found" }
            return emptyList()
        }

        val url = urlBuilder()
        url.addEncodedQueryParameter("next", next.toString())
        url.addQueryParameter("f_search", filter.toSearchQuery())

        val fCats = filter.types.toFCats()
        if (fCats != 0) {
            url.addEncodedQueryParameter("f_cats", (1023 - fCats).toString())
        }
        if (updateDm) {
            // by unknown reason cookie "sl=dm_2" is ignored, so, we should request it again
            url.addQueryParameter("inline_set", "dm_e")
        }
        url.addQueryParameter("advsearch", "1")
        if (config[suspiciousContentKey]) {
            url.addQueryParameter("f_sh", "on")
        }
        val body = webClient.httpGet(url.build()).parseHtml().body()
        val root = body.selectFirst("table.itg")?.selectFirst("tbody")
        if (root == null) {
            if (updateDm) {
                if (body.getElementsContainingText("No hits found").isNotEmpty()) {
                    return emptyList()
                } else {
                    body.parseFailed("Cannot find root")
                }
            } else {
                return getListPage(page, order, filter, updateDm = true)
            }
        }
        val nextTimestamp = getNextTimestamp(body)
        synchronized(nextPages) {
            nextPages.getOrPut(filter.hashCode()) {
                MutableIntLongMap()
            }.put(page + 1, nextTimestamp)
        }

        return root.children().mapNotNull { tr ->
            if (tr.childrenSize() != 2) return@mapNotNull null
            val (td1, td2) = tr.children()
            val gLink = td2.selectFirstOrThrow("div.glink")
            val a = gLink.parents().select("a").first() ?: gLink.parseFailed("link not found")
            val href = a.attrAsRelativeUrl("href")
            val tagsDiv = gLink.nextElementSibling() ?: gLink.parseFailed("tags div not found")
            val rawTitle = gLink.text()
            val author = tagsDiv.getElementsContainingOwnText("artist:").first()
                ?.nextElementSibling()?.textOrNull()
            Manga(
                id = generateUid(href),
                title = rawTitle.cleanupTitle(),
                altTitles = emptySet(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = td2.selectFirst("div.ir")?.parseRating() ?: RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = td1.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
                tags = tagsDiv.parseTags(),
                state = when {
                    rawTitle.contains("(ongoing)", ignoreCase = true) -> MangaState.ONGOING
                    else -> null
                },
                authors = setOfNotNull(author),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.body().selectFirstOrThrow("div.gm")
        val cover = root.getElementById("gd1")?.children()?.first()
        val title = root.getElementById("gd2")
        val tagList = root.getElementById("taglist")
        val tabs = doc.body().selectFirst("table.ptt")?.selectFirst("tr")
        val gd3 = root.getElementById("gd3")
        val lang = gd3
            ?.selectFirst("tr:contains(Language)")
            ?.selectFirst(".gdt2")?.ownTextOrNull()
        val uploadDate = gd3
            ?.selectFirst("tr:contains(Posted)")
            ?.selectFirst(".gdt2")?.ownTextOrNull()
            .let { SimpleDateFormat("yyyy-MM-dd HH:mm", sourceLocale).parseSafe(it) }
        val uploader = gd3
            ?.getElementsByAttributeValueContaining("href", "/uploader/")
            ?.firstOrNull()
            ?.ownTextOrNull()
        val tags = tagList?.parseTags().orEmpty()

        return manga.copy(
            title = title?.getElementById("gn")?.text()?.cleanupTitle() ?: manga.title,
            altTitles = setOfNotNull(title?.getElementById("gj")?.text()?.cleanupTitle()?.nullIfEmpty()),
            publicUrl = doc.baseUri().ifEmpty { manga.publicUrl },
            rating = root.getElementById("rating_label")?.text()
                ?.substringAfterLast(' ')
                ?.toFloatOrNull()
                ?.div(5f) ?: manga.rating,
            largeCoverUrl = cover?.styleValueOrNull("background")?.cssUrl(),
            tags = manga.tags + tags,
            description = tagList?.select("tr")?.joinToString("<br>") { tr ->
                val (tc, td) = tr.children()
                val subTags = td.select("a").joinToString { it.html() }
                "<b>${tc.html()}</b> $subTags"
            },
            chapters = tabs?.select("a")?.findLast { a ->
                a.text().toIntOrNull() != null
            }?.let { a ->
                val count = a.text().toInt()
                val chapters = ChaptersListBuilder(count)
                for (i in 1..count) {
                    val url = "${manga.url}?p=${i - 1}"
                    chapters += MangaChapter(
                        id = generateUid(url),
                        title = null,
                        number = i.toFloat(),
                        volume = 0,
                        url = url,
                        uploadDate = uploadDate,
                        source = source,
                        scanlator = uploader,
                        branch = lang,
                    )
                }
                chapters.toList()
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.body().requireElementById("gdt")
        return root.select("a").map { a ->
            val url = a.attrAsRelativeUrl("href")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = a.children().firstOrNull()?.extractPreview(),
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.body().requireElementById("img").attrAsAbsoluteUrl("src")
    }

    @Suppress("SpellCheckingInspection")
    private val tags: String
        get() = "ahegao,anal,angel,apron,bandages,bbw,bdsm,beauty mark,big areolae,big ass,big breasts,big clit,big lips," +
            "big nipples,bikini,blackmail,bloomers,blowjob,bodysuit,bondage,breast expansion,bukkake,bunny girl,business suit," +
            "catgirl,centaur,cheating,chinese dress,christmas,collar,corset,cosplaying,cowgirl,crossdressing,cunnilingus," +
            "dark skin,daughter,deepthroat,defloration,demon girl,double penetration,double vaginal,dougi,dragon,drunk,elf,exhibitionism,farting," +
            "females only,femdom,filming,fingering,fishnets,footjob,fox girl,furry,futanari,garter belt,ghost,giantess," +
            "glasses,gloves,goblin,gothic lolita,growth,guro,gyaru,hair buns,hairy,hairy armpits,handjob,harem,hidden sex," +
            "horns,huge breasts,humiliation,impregnation,incest,inverted nipples,kemonomimi,kimono,kissing,lactation," +
            "latex,leg lock,leotard,lingerie,lizard girl,maid,masked face,masturbation,midget,miko,milf,mind break," +
            "mind control,monster girl,mother,muscle,nakadashi,netorare,nose hook,nun,nurse,oil,paizuri,panda girl," +
            "pantyhose,piercing,pixie cut,policewoman,ponytail,pregnant,rape,rimjob,robot,scat,lolicon,schoolgirl uniform," +
            "sex toys,shemale,sister,small breasts,smell,sole dickgirl,sole female,squirting,stockings,sundress,sweating," +
            "swimsuit,swinging,tail,tall girl,teacher,tentacles,thigh high boots,tomboy,transformation,twins,twintails," +
            "unusual pupils,urination,vore,vtuber,widow,wings,witch,wolf girl,x-ray,yuri,zombie,sole male,males only,yaoi," +
            "tomgirl,tall man,oni,shotacon,prostate massage,policeman,males only,huge penis,fox boy,feminization,dog boy,dickgirl on male,big penis," +
            "triple vaginal,fff threesome,fft threesome,ffm threesome,mmf threesome,mmt threesome,mtf threesome,ttf threesome,ttt threesome,ttm threesome," +
            "real doll,strap-on,speculum,tail plug,tube,vacbed,wooden horse,wormhole,apparel bukkake,cum bath,giant sperm," +
            "internal urination,omorashi,public use,scat insertion,chikan,confinement,food on body,forniphilia,human cattle,petplay,slave,smalldom," +
            "tickling,fanny packing,harness,shibari,stuck in wall,abortion,cannibalism,catfight,cbt,cuntbusting,dismantling,electric shocks,ryona," +
            "snuff,torture,trampling,wrestling,autofellatio,autopaizuri,clone,phone sex,selfcest,solo action,table masturbation,blind,handicapped,mute," +
            "gender change,gender morph,dickgirl on dickgirl,dickgirl on female,male on dickgirl,first person perspective,coach,mesugaki,prostitution,tutor," +
            "dickgirls only,netorase,aunt,cousin,daughter,granddaughter,grandmother,inseki,niece,oyakodon,shimaidon,forced exposure,voyeurism,low bestiality," +
            "low guro,low incest,low lolicon,low scat,low smegma,focus anal,focus blowjob,focus paizuri"

    private val tagTranslations = mapOf(
        "ahegao" to "阿黑颜",
        "anal" to "爆肛",
        "angel" to "天使",
        "apron" to "围裙",
        "bandages" to "绷带",
        "bbw" to "胖女人",
        "bdsm" to "调教",
        "beauty mark" to "美人痣",
        "big areolae" to "大乳晕",
        "big ass" to "大屁股",
        "big breasts" to "巨乳",
        "big clit" to "大阴蒂",
        "big lips" to "大嘴唇💋",
        "big nipples" to "大乳头",
        "big penis" to "大根",
        "bikini" to "比基尼👙",
        "blackmail" to "要挟",
        "bloomers" to "布鲁马",
        "blowjob" to "口交",
        "bodysuit" to "紧身衣裤",
        "bondage" to "束缚",
        "breast expansion" to "乳房膨胀",
        "bukkake" to "颜射",
        "bunny girl" to "兔女郎",
        "business suit" to "西装",
        "catgirl" to "猫女",
        "centaur" to "半人马",
        "cheating" to "出轨",
        "chinese dress" to "旗袍",
        "christmas" to "圣诞装🤶",
        "collar" to "项圈",
        "corset" to "紧身胸衣",
        "cosplaying" to "Cosplay",
        "cowgirl" to "牛女孩",
        "crossdressing" to "异性装",
        "cunnilingus" to "舔阴",
        "dark skin" to "黑皮",
        "daughter" to "女儿",
        "deepthroat" to "深喉",
        "defloration" to "破处",
        "demon girl" to "恶魔女孩",
        "dickgirl on male" to "扶上男",
        "dog boy" to "狗男孩",
        "double penetration" to "双重插入",
        "double vaginal" to "双插阴道",
        "dougi" to "练功服🥋",
        "dragon" to "龙🐉",
        "drunk" to "醉酒",
        "elf" to "精灵🧝‍♀️",
        "exhibitionism" to "露阴癖",
        "farting" to "放屁",
        "females only" to "纯女性⚢",
        "femdom" to "女性主导",
        "feminization" to "女性化",
        "filming" to "摄像",
        "fingering" to "指法",
        "fishnets" to "渔网",
        "footjob" to "足交",
        "fox boy" to "狐男",
        "fox girl" to "狐女",
        "furry" to "毛茸茸",
        "futanari" to "扶她",
        "garter belt" to "吊袜带",
        "ghost" to "幽灵👻",
        "giantess" to "女巨人",
        "glasses" to "眼镜👓",
        "gloves" to "手套",
        "goblin" to "哥布林",
        "gothic lolita" to "哥特萝莉装",
        "growth" to "巨大化",
        "guro" to "猎奇",
        "gyaru" to "辣妹",
        "hair buns" to "丸子头",
        "hairy" to "多毛",
        "hairy armpits" to "腋毛",
        "handjob" to "打手枪",
        "harem" to "后宫",
        "hidden sex" to "隐蔽性交",
        "horns" to "角",
        "huge breasts" to "超乳",
        "huge penis" to "巨根",
        "humiliation" to "屈辱",
        "impregnation" to "受孕",
        "incest" to "乱伦",
        "inverted nipples" to "乳头内陷",
        "kemonomimi" to "兽耳",
        "kimono" to "和服👘",
        "kissing" to "接吻💏",
        "lactation" to "母乳",
        "latex" to "乳胶紧身衣",
        "leg lock" to "勾腿",
        "leotard" to "紧身衣",
        "lingerie" to "情趣内衣",
        "lizard girl" to "蜥蜴女孩",
        "lolicon" to "萝莉",
        "maid" to "女仆装",
        "males only" to "纯男性⚣",
        "masked face" to "假面",
        "masturbation" to "自慰",
        "midget" to "侏儒",
        "miko" to "巫女装",
        "milf" to "熟女",
        "mind break" to "洗脑",
        "mind control" to "催眠",
        "monster girl" to "魔物娘",
        "mother" to "母亲",
        "muscle" to "肌肉",
        "nakadashi" to "中出",
        "netorare" to "NTR",
        "nose hook" to "鼻吊钩",
        "nun" to "修女服",
        "nurse" to "护士装",
        "oil" to "油",
        "oni" to "鬼",
        "paizuri" to "乳交",
        "panda girl" to "熊猫娘",
        "pantyhose" to "连裤袜",
        "piercing" to "穿孔",
        "pixie cut" to "精灵头",
        "policeman" to "警服",
        "policewoman" to "警服",
        "ponytail" to "马尾辫",
        "pregnant" to "怀孕",
        "prostate massage" to "前列腺按摩",
        "rape" to "强奸",
        "rimjob" to "舔肛",
        "robot" to "机器人🤖",
        "scat" to "粪便💩",
        "scat insertion" to "粪便插入",
        "schoolgirl uniform" to "女生制服",
        "sex toys" to "性玩具",
        "shemale" to "人妖♂",
        "shotacon" to "正太",
        "sister" to "姐妹",
        "small breasts" to "贫乳",
        "smell" to "气味",
        "sole dickgirl" to "单扶她",
        "sole female" to "单女主",
        "sole male" to "单男主",
        "squirting" to "潮吹",
        "stockings" to "长筒袜",
        "sundress" to "夏装",
        "sweating" to "出汗",
        "swimsuit" to "泳装",
        "swinging" to "换妻",
        "tail" to "尾巴",
        "tall girl" to "高个女",
        "tall man" to "高个男",
        "teacher" to "教师",
        "tentacles" to "触手",
        "thigh high boots" to "高筒靴",
        "tomboy" to "假小子",
        "ttf threesome" to "扶女扶3P",
        "ttm threesome" to "扶扶男3P",
        "ttt threesome" to "扶3P",
        "tomgirl" to "伪娘",
        "triple vaginal" to "三插阴道",
        "transformation" to "变身",
        "twins" to "双胞胎",
        "twintails" to "双马尾",
        "unusual pupils" to "异瞳",
        "urination" to "排尿",
        "vore" to "吞食",
        "vtuber" to "虚拟主播",
        "widow" to "寡妇",
        "wings" to "翅膀",
        "witch" to "女巫装",
        "wolf girl" to "狼女孩",
        "x-ray" to "透视",
        "yaoi" to "男同",
        "yuri" to "百合",
        "aunt" to "阿姨",
        "cousin" to "表姐妹",
        "daughter" to "女儿",
        "granddaughter" to "孙女",
        "grandmother" to "祖母",
        "inseki" to "姻亲",
        "niece" to "侄女",
        "oyakodon" to "亲子丼",
        "shimaidon" to "手足丼",
        "ffm threesome" to "女男女3P",
        "mmf threesome" to "男女男3P",
        "mmt threesome" to "男扶男3P",
        "mtf threesome" to "男扶女3P",
        "fff threesome" to "女3P",
        "fft threesome" to "女扶女3P",
        "real doll" to "充气娃娃",
        "strap-on" to "穿戴式阳具",
        "speculum" to "扩张器",
        "tail plug" to "尾塞",
        "tube" to "插管",
        "vacbed" to "真空床",
        "wooden horse" to "木马",
        "wormhole" to "虫洞",
        "apparel bukkake" to "穿衣颜射",
        "cum bath" to "精液浴",
        "giant sperm" to "巨大精子",
        "internal urination" to "内部排尿",
        "omorashi" to "漏尿",
        "public use" to "肉便器",
        "chikan" to "痴汉",
        "confinement" to "监禁",
        "food on body" to "女体盛",
        "forniphilia" to "人体家具",
        "human cattle" to "人类饲养",
        "petplay" to "人宠",
        "slave" to "奴隶",
        "smalldom" to "逆体格差",
        "tickling" to "挠痒",
        "fanny packing" to "人肉腰包",
        "harness" to "挽具",
        "shibari" to "捆绑",
        "stuck in wall" to "卡在墙上",
        "abortion" to "堕胎",
        "cannibalism" to "食人",
        "catfight" to "猫斗",
        "cbt" to "虐屌",
        "cuntbusting" to "阴道破坏",
        "dismantling" to "拆解",
        "electric shocks" to "电击",
        "ryona" to "凌虐",
        "snuff" to "杀害",
        "torture" to "拷打",
        "trampling" to "践踏",
        "wrestling" to "摔角",
        "autofellatio" to "自吹",
        "autopaizuri" to "自乳交",
        "clone" to "克隆",
        "phone sex" to "电话性爱",
        "selfcest" to "自交",
        "solo action" to "自摸",
        "table masturbation" to "桌角自慰",
        "blind" to "失明",
        "handicapped" to "残疾",
        "mute" to "哑巴",
        "gender change" to "性转换",
        "gender morph" to "男体化",
        "dickgirl on dickgirl" to "扶上扶",
        "dickgirl on female" to "扶上女",
        "male on dickgirl" to "男上扶",
        "first person perspective" to "第一人称视角",
        "coach" to "教练",
        "mesugaki" to "雌小鬼",
        "prostitution" to "卖淫",
        "tutor" to "家庭教师",
        "dickgirls only" to "纯扶她",
        "netorase" to "绿帽癖",
        "forced exposure" to "强制暴露",
        "voyeurism" to "偷窥",
        "low bestiality" to "低存在兽交",
        "low guro" to "低存在猎奇",
        "low incest" to "低存在乱伦",
        "low lolicon" to "低存在萝莉",
        "low scat" to "低存在排便",
        "low smegma" to "低存在阴垢",
        "focus anal" to "高存在肛交",
        "focus blowjob" to "高存在口交",
        "focus paizuri" to "高存在乳交",
        "zombie" to "丧尸🧟‍♀️",
    )

    private val isChineseLocale: Boolean
        get() = context.getPreferredLocales().firstOrNull()?.language == "zh"

    private val groupedTagKeys = mapOf(
        "行为玩法" to listOf(
            "anal", "double penetration", "double vaginal", "triple vaginal", "paizuri", "cunnilingus", "footjob",
            "handjob", "blowjob", "rimjob", "sex toys", "strap-on", "speculum", "tail plug", "tube", "vacbed",
            "wooden horse", "wormhole", "apparel bukkake", "cum bath", "bukkake", "nakadashi", "fingering",
            "squirting", "urination", "omorashi", "public use", "scat", "scat insertion", "chikan", "confinement",
            "bondage", "shibari", "bdsm", "femdom", "petplay", "slave", "smalldom", "tickling", "humiliation",
            "rape", "netorare", "cheating", "voyeurism", "exhibitionism", "hidden sex", "forced exposure", "filming",
            "guro", "cannibalism", "cbt", "cuntbusting", "dismantling", "ryona", "snuff", "torture", "trampling",
            "wrestling", "electric shocks", "stuck in wall", "fanny packing", "frottage"
        ),
        "关系/多P" to listOf(
            "ffm threesome", "mmf threesome", "mmt threesome", "mtf threesome", "ttf threesome", "ttt threesome",
            "ttm threesome", "fff threesome", "fft threesome", "harem", "group", "solo action", "autofellatio",
            "autopaizuri", "selfcest", "dickgirls only", "females only", "males only", "sole male", "sole female",
            "sole dickgirl", "incest", "inseki", "sister", "mother", "father", "aunt", "cousin", "daughter",
            "granddaughter", "grandmother", "niece", "oyakodon", "shimaidon", "prostitution", "netorase", "swinging",
            "mesugaki"
        ),
        "身体/外观" to listOf(
            "big breasts", "huge breasts", "gigantic breasts", "big nipples", "inverted nipples", "small breasts",
            "big ass", "tall girl", "tall man", "midget", "giantess", "muscle", "growth", "pregnant", "lactation",
            "dark skin", "gyaru", "hairy", "hairy armpits", "beauty mark", "ahegao", "big clit", "big lips",
            "big penis", "huge penis", "prostate massage", "feminization", "futanari", "shemale", "gender change",
            "gender morph"
        ),
        "服饰/角色扮演" to listOf(
            "maid", "nurse", "miko", "kimono", "chinese dress", "schoolgirl uniform", "bikini", "swimsuit",
            "lingerie", "stockings", "pantyhose", "fishnets", "garter belt", "thigh high boots", "leotard",
            "bloomers", "corset", "business suit", "bunny girl", "catgirl", "policewoman", "policeman", "nun",
            "cheerleader", "latex", "sundress", "apron", "bandages", "gothic lolita", "cosplaying", "crossdressing",
            "masked face", "gloves", "collar", "harness", "tail", "panda girl"
        ),
        "物种/种族" to listOf(
            "angel", "demon girl", "oni", "monster girl", "elf", "goblin", "fox girl", "fox boy", "wolf girl",
            "catgirl", "dog boy", "mermaid", "centaur", "slime", "ghost", "vampire", "zombie", "robot", "dragon",
            "lizard girl", "panda girl"
        ),
        "精神/变身" to listOf(
            "mind break", "mind control", "transformation", "gender morph", "gender change", "sleeping", "drunk"
        ),
    )

    private fun groupTitle(raw: String): String {
        return if (isChineseLocale) raw else when (raw) {
            "行为玩法" -> "Actions"
            "关系/多P" -> "Relations"
            "身体/外观" -> "Body"
            "服饰/角色扮演" -> "Outfits/Cosplay"
            "物种/种族" -> "Species"
            "精神/变身" -> "Mind/Transform"
            else -> raw
        }
    }

    private fun displayTagTitle(key: String): String {
        return if (isChineseLocale) {
            tagTranslations[key] ?: key
        } else {
            key.toTitleCase(Locale.ENGLISH)
        }
    }

    private fun buildTagMap(): Map<String, MangaTag> {
        val tagElements = tags.split(",")
        val result = LinkedHashMap<String, MangaTag>(tagElements.size)
        for (tag in tagElements) {
            val el = tag.trim()
            if (el.isEmpty()) continue
            result[el] = MangaTag(
                title = displayTagTitle(el),
                key = el,
                source = source,
            )
        }
        return result
    }

    private val cachedTagMap: Map<String, MangaTag> by lazy(LazyThreadSafetyMode.PUBLICATION) { buildTagMap() }
    private val cachedTagsSet: Set<MangaTag> by lazy(LazyThreadSafetyMode.PUBLICATION) { cachedTagMap.values.toSet() }
    private val tagKeyToGroup: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildMap {
            groupedTagKeys.forEach { (group, keys) ->
                keys.forEach { put(it, group) }
            }
        }
    }

    private fun mapTags(): Set<MangaTag> = cachedTagsSet

    private fun mapTagGroups(): List<MangaTagGroup> {
        val tagMap = cachedTagMap
        val used = HashSet<String>(tagMap.size)
        val groups = mutableListOf<MangaTagGroup>()
        groupedTagKeys.forEach { (name, keys) ->
            val list = keys.mapNotNull { key ->
                tagMap[key]?.also { used += key }
            }
            if (list.isNotEmpty()) {
                groups += MangaTagGroup(groupTitle(name), list.toSet())
            }
        }
        val remaining = tagMap.filterKeys { it !in used }.values.toSet()
        if (remaining.isNotEmpty()) {
            groups += MangaTagGroup(groupTitle(if (isChineseLocale) "其他" else "Others"), remaining)
        }
        return groups
    }

    private val cachedTagGroups: List<MangaTagGroup> by lazy(LazyThreadSafetyMode.PUBLICATION) { mapTagGroups() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.headersContentLength(BANNED_RESPONSE_LENGTH) <= BANNED_RESPONSE_LENGTH) {
            val text = response.peekBody(BANNED_RESPONSE_LENGTH).use { it.string() }
            if (text.contains("IP address has been temporarily banned", ignoreCase = true)) {
                val hours = Regex("([0-9]+) hours?").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
                val minutes = Regex("([0-9]+) minutes?").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
                val seconds = Regex("([0-9]+) seconds?").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
                response.closeQuietly()
                throw TooManyRequestExceptions(
                    url = response.request.url.toString(),
                    retryAfter = TimeUnit.HOURS.toMillis(hours)
                        + TimeUnit.MINUTES.toMillis(minutes)
                        + TimeUnit.SECONDS.toMillis(seconds),
                )
            }
        }
        val imageRect = response.request.url.fragment?.split(',')
        if (imageRect != null && imageRect.size == 4) {
            // rect: top,left,right,bottom
            return context.redrawImageResponse(response) { bitmap ->
                val srcRect = Rect(
                    left = imageRect[0].toInt(),
                    top = imageRect[1].toInt(),
                    right = imageRect[2].toInt(),
                    bottom = imageRect[3].toInt(),
                )
                val dstRect = Rect(0, 0, srcRect.width, srcRect.height)
                val result = context.createBitmap(dstRect.width, dstRect.height)
                result.drawBitmap(bitmap, srcRect, dstRect)
                result
            }
        }
        return response
    }

    private fun Locale.toLanguagePath() = when (language) {
        else -> getDisplayLanguage(Locale.ENGLISH).lowercase()
    }

    override suspend fun getUsername(): String {
        val doc = webClient.httpGet("https://forums.$DOMAIN_UNAUTHORIZED/").parseHtml().body()
        val username = doc.getElementById("userlinks")
            ?.getElementsByAttributeValueContaining("href", "showuser=")
            ?.firstOrNull()
            ?.ownText()
            ?: if (doc.getElementById("userlinksguest") != null) {
                throw AuthRequiredException(source)
            } else {
                doc.parseFailed()
            }
        return username
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(suspiciousContentKey)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val query = seed.title
        return getListPage(
            page = 0,
            order = defaultSortOrder,
            filter = MangaListFilter(query = query),
        )
    }

    private fun isAuthorized(domain: String): Boolean {
        val cookies = context.cookieJar.getCookies(domain).mapToSet { x -> x.name }
        return authCookies.all { it in cookies }
    }

    private fun Element.parseRating(): Float {
        return runCatching {
            val style = requireNotNull(attr("style"))
            val (v1, v2) = ratingPattern.findAll(style).toList()
            var p1 = v1.groupValues.first().dropLast(2).toInt()
            val p2 = v2.groupValues.first().dropLast(2).toInt()
            if (p2 != -1) {
                p1 += 8
            }
            (80 - p1) / 80f
        }.getOrDefault(RATING_UNKNOWN)
    }

    private fun String.cleanupTitle(): String {
        return replace(titleCleanupPattern, "")
            .replace(spacesCleanupPattern, "")
    }

    private fun Element.parseTags(): Set<MangaTag> {

        fun Element.parseTag() = textOrNull()?.let {
            // 优先复用已缓存的 Tag，避免重复创建与翻译
            cachedTagMap[it] ?: MangaTag(title = displayTagTitle(it), key = it, source = source)
        }

        val result = ArraySet<MangaTag>()
        for (prefix in TAG_PREFIXES) {
            getElementsByAttributeValueStarting("id", "ta_$prefix").mapNotNullTo(result, Element::parseTag)
            getElementsByAttributeValueStarting("title", prefix).mapNotNullTo(result, Element::parseTag)
        }
        return result
    }

    private fun Element.extractPreview(): String? {
        val bg = backgroundOrNull() ?: return null
        return buildString {
            append(bg.url)
            append('#')
            // rect: left,top,right,bottom
            append(bg.left)
            append(',')
            append(bg.top)
            append(',')
            append(bg.right)
            append(',')
            append(bg.bottom)
        }
    }

    private fun getNextTimestamp(root: Element): Long {
        return root.getElementById("unext")
            ?.attrAsAbsoluteUrlOrNull("href")
            ?.toHttpUrlOrNull()
            ?.queryParameter("next")
            ?.toLongOrNull() ?: 1
    }

    private fun MangaListFilter.toSearchQuery(): String? {
        if (isEmpty()) {
            return null
        }
        val joiner = StringUtil.StringJoiner(" ")
        if (!query.isNullOrEmpty()) {
            joiner.add(query)
        }
        for (tag in tags) {
            if (tag.key.isNumeric()) {
                continue
            }
            joiner.add("tag:\"")
            joiner.append(tag.key)
            joiner.append("\"$")
        }
        for (tag in tagsExclude) {
            if (tag.key.isNumeric()) {
                continue
            }
            joiner.add("-tag:\"")
            joiner.append(tag.key)
            joiner.append("\"$")
        }
        locale?.let { lc ->
            joiner.add("language:\"")
            joiner.append(lc.toLanguagePath())
            joiner.append("\"$")
        }
        if (!author.isNullOrEmpty()) {
            joiner.add("artist:\"")
            joiner.append(author)
            joiner.append("\"$")
        }
        return joiner.complete().nullIfEmpty()
    }

    private fun Collection<ContentType>.toFCats(): Int = fold(0) { acc, ct ->
        val cat: Int = when (ct) {
            ContentType.DOUJINSHI -> 2
            ContentType.MANGA -> 4
            ContentType.ARTIST_CG -> 8
            ContentType.GAME_CG -> 16
            ContentType.COMICS -> 512
            ContentType.IMAGE_SET -> 32
            else -> 449 // 1 or 64 or 128 or 256
        }
        acc or cat
    }

    private fun checkAuth(): Boolean {
        val authorized = isAuthorized(DOMAIN_UNAUTHORIZED)
        if (authorized) {
            if (!isAuthorized(DOMAIN_AUTHORIZED)) {
                context.cookieJar.copyCookies(
                    DOMAIN_UNAUTHORIZED,
                    DOMAIN_AUTHORIZED,
                    authCookies,
                )
                context.cookieJar.insertCookies(DOMAIN_AUTHORIZED, "yay=louder")
            }
            return true
        }
        return false
    }
}
