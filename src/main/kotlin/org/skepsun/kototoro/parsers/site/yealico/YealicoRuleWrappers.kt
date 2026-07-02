package org.skepsun.kototoro.parsers.site.yealico

import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.model.*

/**
 * Auto-generated thin @ContentSourceParser wrappers — one per Yealico rule (66 total).
 *
 * KSP discovers each annotation and generates ContentParserSource enum entries
 * plus ContentParserFactory dispatch code.
 *
 * Locale and ContentType are set to match existing parser conventions:
 *   HENTAI_MANGA  — NSFW image galleries / boorus
 *   HENTAI_VIDEO  — NSFW video sites
 *   IMAGE_SET     — SFW image/wallpaper/art galleries
 *   VIDEO         — SFW video sites
 *
 * Generated. Do not edit by hand.
 */

@ContentSourceParser("YEALICO_AVMEMO", "AVMEMO", "en", ContentType.HENTAI_MANGA)
internal class Yealico_AVMEMO(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_AVMEMO"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("AVMEMO.json")!!)

@ContentSourceParser("YEALICO_AVMOO", "AVMOO", "en", ContentType.HENTAI_MANGA)
internal class Yealico_AVMOO(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_AVMOO"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("AVMOO.json")!!)

@ContentSourceParser("YEALICO_ARTSTATION", "ArtStation", "en", ContentType.IMAGE_SET)
internal class Yealico_ArtStation(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_ARTSTATION"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("ArtStation.json")!!)

@ContentSourceParser("YEALICO_DANBOORU_POOL", "Danbooru Pool", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Danbooru_Pool(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_DANBOORU_POOL"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Danbooru_Pool.json")!!)

@ContentSourceParser("YEALICO_DANBOORU_POST", "Danbooru Post", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Danbooru_Post(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_DANBOORU_POST"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Danbooru_Post.json")!!)

@ContentSourceParser("YEALICO_DESIGNERSPICS", "DesignersPics", "en", ContentType.IMAGE_SET)
internal class Yealico_DesignersPics(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_DESIGNERSPICS"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("DesignersPics.json")!!)

@ContentSourceParser("YEALICO_DRIBBBLE", "Dribbble", "en", ContentType.IMAGE_SET)
internal class Yealico_Dribbble(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_DRIBBBLE"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("Dribbble.json")!!)

@ContentSourceParser("YEALICO_E_SHUUSHUU", "E-shuushuu", "ja", ContentType.IMAGE_SET)
internal class Yealico_E_shuushuu(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_E_SHUUSHUU"
        override val locale = "ja"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("E-shuushuu.json")!!)

@ContentSourceParser("YEALICO_FINDA_PHOTO", "FindA.Photo", "en", ContentType.IMAGE_SET)
internal class Yealico_FindA_Photo(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_FINDA_PHOTO"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("FindA.Photo.json")!!)

@ContentSourceParser("YEALICO_G_E_HENTAI", "G.E-hentai", "en", ContentType.HENTAI_MANGA)
internal class Yealico_G_E_hentai(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_G_E_HENTAI"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("G.E-hentai.json")!!)

@ContentSourceParser("YEALICO_GELBOORU_POOL", "Gelbooru Pool", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Gelbooru_Pool(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_GELBOORU_POOL"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Gelbooru_Pool.json")!!)

@ContentSourceParser("YEALICO_GELBOORU_POST", "Gelbooru Post", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Gelbooru_Post(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_GELBOORU_POST"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Gelbooru_Post.json")!!)

@ContentSourceParser("YEALICO_HPJAV", "HPJav", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_HPJav(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_HPJAV"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("HPJav.json")!!)

@ContentSourceParser("YEALICO_HENTAI_CAFE", "Hentai.Cafe", "en", ContentType.HENTAI_MANGA)
internal class Yealico_Hentai_Cafe(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_HENTAI_CAFE"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Hentai.Cafe.json")!!)

@ContentSourceParser("YEALICO_IWARA", "Iwara-動画", "ja", ContentType.HENTAI_VIDEO)
internal class Yealico_Iwara_動画(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_IWARA"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("Iwara-動画.json")!!)

@ContentSourceParser("YEALICO_IWARA_BD5F", "Iwara-画像", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Iwara_画像(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_IWARA_BD5F"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Iwara-画像.json")!!)

@ContentSourceParser("YEALICO_KONACHAN_POOL", "Konachan Pool", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Konachan_Pool(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_KONACHAN_POOL"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Konachan_Pool.json")!!)

@ContentSourceParser("YEALICO_KONACHAN_POST", "Konachan Post", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Konachan_Post(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_KONACHAN_POST"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Konachan_Post.json")!!)

@ContentSourceParser("YEALICO_LOFI_E_HENTAI", "Lofi.E-hentai", "en", ContentType.HENTAI_MANGA)
internal class Yealico_Lofi_E_hentai(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_LOFI_E_HENTAI"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Lofi.E-hentai.json")!!)

@ContentSourceParser("YEALICO_PINTEREST", "Pinterest", "en", ContentType.IMAGE_SET)
internal class Yealico_Pinterest(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_PINTEREST"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("Pinterest.json")!!)

@ContentSourceParser("YEALICO_PIXIV", "Pixiv", "ja", ContentType.IMAGE_SET)
internal class Yealico_Pixiv(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_PIXIV"
        override val locale = "ja"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("Pixiv.json")!!)

@ContentSourceParser("YEALICO_PORNHUB", "Pornhub", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_Pornhub(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_PORNHUB"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("Pornhub.json")!!)

@ContentSourceParser("YEALICO_REDTUBE", "Redtube", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_Redtube(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_REDTUBE"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("Redtube.json")!!)

@ContentSourceParser("YEALICO_TUBE8", "Tube8", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_Tube8(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_TUBE8"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("Tube8.json")!!)

@ContentSourceParser("YEALICO_TUMBLR", "Tumblr", "en", ContentType.IMAGE_SET)
internal class Yealico_Tumblr(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_TUMBLR"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("Tumblr.json")!!)

@ContentSourceParser("YEALICO_UI", "UI中国", "zh", ContentType.IMAGE_SET)
internal class Yealico_UI中国(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_UI"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("UI中国.json")!!)

@ContentSourceParser("YEALICO_UNSPLASH", "Unsplash", "en", ContentType.IMAGE_SET)
internal class Yealico_Unsplash(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_UNSPLASH"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("Unsplash.json")!!)

@ContentSourceParser("YEALICO_WORLDCOSPLAY", "WorldCosplay", "en", ContentType.IMAGE_SET)
internal class Yealico_WorldCosplay(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_WORLDCOSPLAY"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("WorldCosplay.json")!!)

@ContentSourceParser("YEALICO_XVIDEOS", "XVIDEOS", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_XVIDEOS(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_XVIDEOS"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("XVIDEOS.json")!!)

@ContentSourceParser("YEALICO_XBOORU_POOL", "Xbooru Pool", "en", ContentType.HENTAI_MANGA)
internal class Yealico_Xbooru_Pool(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_XBOORU_POOL"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Xbooru_Pool.json")!!)

@ContentSourceParser("YEALICO_XBOORU_POST", "Xbooru Post", "en", ContentType.HENTAI_MANGA)
internal class Yealico_Xbooru_Post(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_XBOORU_POST"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Xbooru_Post.json")!!)

@ContentSourceParser("YEALICO_YANDE_RE_POOL", "Yande.re Pool", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Yande_re_Pool(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_YANDE_RE_POOL"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Yande.re_Pool.json")!!)

@ContentSourceParser("YEALICO_YANDE_RE_POST", "Yande.re Post", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_Yande_re_Post(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_YANDE_RE_POST"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("Yande.re_Post.json")!!)

@ContentSourceParser("YEALICO_YOUAV", "YouAv", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_YouAv(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_YOUAV"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("YouAv.json")!!)

@ContentSourceParser("YEALICO_YOUPORN", "YouPorn", "en", ContentType.HENTAI_VIDEO)
internal class Yealico_YouPorn(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_YOUPORN"
        override val locale = "en"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("YouPorn.json")!!)

@ContentSourceParser("YEALICO_ANIME_PICTURES_NET", "anime-pictures.net", "ja", ContentType.IMAGE_SET)
internal class Yealico_anime_pictures_net(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_ANIME_PICTURES_NET"
        override val locale = "ja"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("anime-pictures.net.json")!!)

@ContentSourceParser("YEALICO_DEVIANTART", "deviantART", "en", ContentType.IMAGE_SET)
internal class Yealico_deviantART(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_DEVIANTART"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("deviantART.json")!!)

@ContentSourceParser("YEALICO_GIRLIMG", "girlimg", "zh", ContentType.HENTAI_MANGA)
internal class Yealico_girlimg(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_GIRLIMG"
        override val locale = "zh"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("girlimg.json")!!)

@ContentSourceParser("YEALICO_NICONICO", "niconico插画", "ja", ContentType.IMAGE_SET)
internal class Yealico_niconico插画(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_NICONICO"
        override val locale = "ja"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("niconico插画.json")!!)

@ContentSourceParser("YEALICO_WALLHAVEN", "wallhaven", "en", ContentType.IMAGE_SET)
internal class Yealico_wallhaven(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_WALLHAVEN"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("wallhaven.json")!!)

@ContentSourceParser("YEALICO_WALLI", "walli", "", ContentType.IMAGE_SET)
internal class Yealico_walli(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_WALLI"
        override val locale = ""
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("walli.json")!!)

@ContentSourceParser("YEALICO_WALLLS", "wallls", "en", ContentType.IMAGE_SET)
internal class Yealico_wallls(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_WALLLS"
        override val locale = "en"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("wallls.json")!!)

@ContentSourceParser("YEALICO_ZEROCHAN", "zerochan", "ja", ContentType.IMAGE_SET)
internal class Yealico_zerochan(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_ZEROCHAN"
        override val locale = "ja"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("zerochan.json")!!)

@ContentSourceParser("YEALICO_097E8237", "お宝エログ幕府", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_お宝エログ幕府(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_097E8237"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("お宝エログ幕府.json")!!)

@ContentSourceParser("YEALICO_BA0B5599", "ぷるるんお宝画像庫", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_ぷるるんお宝画像庫(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_BA0B5599"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("ぷるるんお宝画像庫.json")!!)

@ContentSourceParser("YEALICO_1042DC68", "みんくちゃんねる", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_みんくちゃんねる(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_1042DC68"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("みんくちゃんねる.json")!!)

@ContentSourceParser("YEALICO_E082FEAB", "エロチカ", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_エロチカ(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_E082FEAB"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("エロチカ.json")!!)

@ContentSourceParser("YEALICO_80E1CF27", "エロ漫画同人誌", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_エロ漫画同人誌(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_80E1CF27"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("エロ漫画同人誌.json")!!)

@ContentSourceParser("YEALICO_7B8B553D", "エロ画像すももちゃんねる", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_エロ画像すももちゃんねる(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_7B8B553D"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("エロ画像すももちゃんねる.json")!!)

@ContentSourceParser("YEALICO_C4CA1558", "モモんガッ(･∀･)!!", "ja", ContentType.IMAGE_SET)
internal class Yealico_モモんガッ(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_C4CA1558"
        override val locale = "ja"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("モモんガッ_______.json")!!)

@ContentSourceParser("YEALICO_A4F15F58", "写真图吧", "zh", ContentType.IMAGE_SET)
internal class Yealico_写真图吧(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_A4F15F58"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("写真图吧.json")!!)

@ContentSourceParser("YEALICO_DC4D663F", "图虫网", "zh", ContentType.IMAGE_SET)
internal class Yealico_图虫网(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_DC4D663F"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("图虫网.json")!!)

@ContentSourceParser("YEALICO_BBA0C477", "堆糖", "zh", ContentType.IMAGE_SET)
internal class Yealico_堆糖(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_BBA0C477"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("堆糖.json")!!)

@ContentSourceParser("YEALICO_6B8A612B", "妹子图2", "zh", ContentType.HENTAI_MANGA)
internal class Yealico_妹子图2(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_6B8A612B"
        override val locale = "zh"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("妹子图2.json")!!)

@ContentSourceParser("YEALICO_C79A71CE", "宅男女神", "zh", ContentType.IMAGE_SET)
internal class Yealico_宅男女神(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_C79A71CE"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("宅男女神.json")!!)

@ContentSourceParser("YEALICO_BEB9CDBB", "安卓壁纸", "zh", ContentType.IMAGE_SET)
internal class Yealico_安卓壁纸(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_BEB9CDBB"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("安卓壁纸.json")!!)

@ContentSourceParser("YEALICO_937ADB2E", "必应每日一图", "zh", ContentType.IMAGE_SET)
internal class Yealico_必应每日一图(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_937ADB2E"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("必应每日一图.json")!!)

@ContentSourceParser("YEALICO_B803EDEA", "搜狗图片", "zh", ContentType.IMAGE_SET)
internal class Yealico_搜狗图片(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_B803EDEA"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("搜狗图片.json")!!)

@ContentSourceParser("YEALICO_EA0ED09A", "维基百科每日图片", "zh", ContentType.IMAGE_SET)
internal class Yealico_维基百科每日图片(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_EA0ED09A"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("维基百科每日图片.json")!!)

@ContentSourceParser("YEALICO_40D831D9", "美女图片集", "zh", ContentType.HENTAI_MANGA)
internal class Yealico_美女图片集(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_40D831D9"
        override val locale = "zh"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("美女图片集.json")!!)

@ContentSourceParser("YEALICO_619759FB", "胖次网", "zh", ContentType.IMAGE_SET)
internal class Yealico_胖次网(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_619759FB"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("胖次网.json")!!)

@ContentSourceParser("YEALICO_7BB26BF5", "花瓣网-画板", "zh", ContentType.IMAGE_SET)
internal class Yealico_花瓣网_画板(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_7BB26BF5"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("花瓣网-画板.json")!!)

@ContentSourceParser("YEALICO_B1DDFA19", "花瓣网-采集", "zh", ContentType.IMAGE_SET)
internal class Yealico_花瓣网_采集(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_B1DDFA19"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("花瓣网-采集.json")!!)

@ContentSourceParser("YEALICO_ED56E7CE", "草榴社区", "zh", ContentType.HENTAI_VIDEO)
internal class Yealico_草榴社区(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_ED56E7CE"
        override val locale = "zh"
        override val contentType = ContentType.HENTAI_VIDEO
    }, YealicoRuleParser.loadRuleJson("草榴社区.json")!!)

@ContentSourceParser("YEALICO_00A04FCF", "萌春画", "ja", ContentType.HENTAI_MANGA)
internal class Yealico_萌春画(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_00A04FCF"
        override val locale = "ja"
        override val contentType = ContentType.HENTAI_MANGA
    }, YealicoRuleParser.loadRuleJson("萌春画.json")!!)

@ContentSourceParser("YEALICO_C7C6203C", "零域动漫壁纸", "zh", ContentType.IMAGE_SET)
internal class Yealico_零域动漫壁纸(context: ContentLoaderContext) :
    YealicoRuleParser(context, object : ContentSource {
        override val name = "YEALICO_C7C6203C"
        override val locale = "zh"
        override val contentType = ContentType.IMAGE_SET
    }, YealicoRuleParser.loadRuleJson("零域动漫壁纸.json")!!)
