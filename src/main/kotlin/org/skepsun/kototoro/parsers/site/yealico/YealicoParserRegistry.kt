package org.skepsun.kototoro.parsers.site.yealico

/**
 * Registry of all Yealico site rule sources (66 total: {'HENTAI_MANGA': 26, 'IMAGE_SET': 31, 'HENTAI_VIDEO': 9}).
 * Excludes sites that already have dedicated parsers (nhentai, wnacg, exhentai, hanime).
 * Auto-generated — do not edit by hand.
 */
public object YealicoParserRegistry {
    public data class RuleEntry(
        val title: String,
        val type: String,
        val contentType: String,
        val locale: String,
        val cacheFile: String,
        val nsfw: Boolean,
    )

    public val ALL_RULES: List<RuleEntry> = listOf(
        RuleEntry("AVMEMO", "gallery", "HENTAI_MANGA", "en", "AVMEMO.json", true),
        RuleEntry("AVMOO", "gallery", "HENTAI_MANGA", "en", "AVMOO.json", true),
        RuleEntry("ArtStation", "gallery", "IMAGE_SET", "en", "ArtStation.json", false),
        RuleEntry("Danbooru Pool", "gallery", "HENTAI_MANGA", "ja", "Danbooru_Pool.json", true),
        RuleEntry("Danbooru Post", "gallery", "HENTAI_MANGA", "ja", "Danbooru_Post.json", true),
        RuleEntry("DesignersPics", "gallery", "IMAGE_SET", "en", "DesignersPics.json", false),
        RuleEntry("Dribbble", "gallery", "IMAGE_SET", "en", "Dribbble.json", false),
        RuleEntry("E-shuushuu", "gallery", "IMAGE_SET", "ja", "E-shuushuu.json", false),
        RuleEntry("FindA.Photo", "gallery", "IMAGE_SET", "en", "FindA.Photo.json", false),
        RuleEntry("G.E-hentai", "gallery", "HENTAI_MANGA", "en", "G.E-hentai.json", true),
        RuleEntry("Gelbooru Pool", "gallery", "HENTAI_MANGA", "ja", "Gelbooru_Pool.json", true),
        RuleEntry("Gelbooru Post", "gallery", "HENTAI_MANGA", "ja", "Gelbooru_Post.json", true),
        RuleEntry("HPJav", "video", "HENTAI_VIDEO", "en", "HPJav.json", true),
        RuleEntry("Hentai.Cafe", "gallery", "HENTAI_MANGA", "en", "Hentai.Cafe.json", true),
        RuleEntry("Iwara-動画", "video", "HENTAI_VIDEO", "ja", "Iwara-動画.json", true),
        RuleEntry("Iwara-画像", "video", "HENTAI_MANGA", "ja", "Iwara-画像.json", true),
        RuleEntry("Konachan Pool", "gallery", "HENTAI_MANGA", "ja", "Konachan_Pool.json", true),
        RuleEntry("Konachan Post", "gallery", "HENTAI_MANGA", "ja", "Konachan_Post.json", true),
        RuleEntry("Lofi.E-hentai", "gallery", "HENTAI_MANGA", "en", "Lofi.E-hentai.json", true),
        RuleEntry("Pinterest", "gallery", "IMAGE_SET", "en", "Pinterest.json", false),
        RuleEntry("Pixiv", "gallery", "IMAGE_SET", "ja", "Pixiv.json", false),
        RuleEntry("Pornhub", "video", "HENTAI_VIDEO", "en", "Pornhub.json", true),
        RuleEntry("Redtube", "video", "HENTAI_VIDEO", "en", "Redtube.json", true),
        RuleEntry("Tube8", "video", "HENTAI_VIDEO", "en", "Tube8.json", true),
        RuleEntry("Tumblr", "api_gallery", "IMAGE_SET", "en", "Tumblr.json", false),
        RuleEntry("UI中国", "gallery", "IMAGE_SET", "zh", "UI中国.json", false),
        RuleEntry("Unsplash", "api_gallery", "IMAGE_SET", "en", "Unsplash.json", false),
        RuleEntry("WorldCosplay", "gallery", "IMAGE_SET", "en", "WorldCosplay.json", false),
        RuleEntry("XVIDEOS", "video", "HENTAI_VIDEO", "en", "XVIDEOS.json", true),
        RuleEntry("Xbooru Pool", "gallery", "HENTAI_MANGA", "en", "Xbooru_Pool.json", true),
        RuleEntry("Xbooru Post", "gallery", "HENTAI_MANGA", "en", "Xbooru_Post.json", true),
        RuleEntry("Yande.re Pool", "gallery", "HENTAI_MANGA", "ja", "Yande.re_Pool.json", true),
        RuleEntry("Yande.re Post", "gallery", "HENTAI_MANGA", "ja", "Yande.re_Post.json", true),
        RuleEntry("YouAv", "video", "HENTAI_VIDEO", "en", "YouAv.json", true),
        RuleEntry("YouPorn", "video", "HENTAI_VIDEO", "en", "YouPorn.json", true),
        RuleEntry("anime-pictures.net", "gallery", "IMAGE_SET", "ja", "anime-pictures.net.json", false),
        RuleEntry("deviantART", "gallery", "IMAGE_SET", "en", "deviantART.json", false),
        RuleEntry("girlimg", "gallery", "HENTAI_MANGA", "zh", "girlimg.json", true),
        RuleEntry("niconico插画", "gallery", "IMAGE_SET", "ja", "niconico插画.json", false),
        RuleEntry("wallhaven", "gallery", "IMAGE_SET", "en", "wallhaven.json", false),
        RuleEntry("walli", "api_gallery", "IMAGE_SET", "", "walli.json", false),
        RuleEntry("wallls", "gallery", "IMAGE_SET", "en", "wallls.json", false),
        RuleEntry("zerochan", "gallery", "IMAGE_SET", "ja", "zerochan.json", false),
        RuleEntry("お宝エログ幕府", "gallery", "HENTAI_MANGA", "ja", "お宝エログ幕府.json", true),
        RuleEntry("ぷるるんお宝画像庫", "gallery", "HENTAI_MANGA", "ja", "ぷるるんお宝画像庫.json", true),
        RuleEntry("みんくちゃんねる", "gallery", "HENTAI_MANGA", "ja", "みんくちゃんねる.json", true),
        RuleEntry("エロチカ", "gallery", "HENTAI_MANGA", "ja", "エロチカ.json", true),
        RuleEntry("エロ漫画同人誌", "gallery", "HENTAI_MANGA", "ja", "エロ漫画同人誌.json", true),
        RuleEntry("エロ画像すももちゃんねる", "gallery", "HENTAI_MANGA", "ja", "エロ画像すももちゃんねる.json", true),
        RuleEntry("モモんガッ(･∀･)!!", "gallery", "IMAGE_SET", "ja", "モモんガッ_______.json", false),
        RuleEntry("写真图吧", "gallery", "IMAGE_SET", "zh", "写真图吧.json", false),
        RuleEntry("图虫网", "gallery", "IMAGE_SET", "zh", "图虫网.json", false),
        RuleEntry("堆糖", "api_gallery", "IMAGE_SET", "zh", "堆糖.json", false),
        RuleEntry("妹子图2", "gallery", "HENTAI_MANGA", "zh", "妹子图2.json", true),
        RuleEntry("宅男女神", "gallery", "IMAGE_SET", "zh", "宅男女神.json", false),
        RuleEntry("安卓壁纸", "api_gallery", "IMAGE_SET", "zh", "安卓壁纸.json", false),
        RuleEntry("必应每日一图", "api_gallery", "IMAGE_SET", "zh", "必应每日一图.json", false),
        RuleEntry("搜狗图片", "api_gallery", "IMAGE_SET", "zh", "搜狗图片.json", false),
        RuleEntry("维基百科每日图片", "gallery", "IMAGE_SET", "zh", "维基百科每日图片.json", false),
        RuleEntry("美女图片集", "gallery", "HENTAI_MANGA", "zh", "美女图片集.json", true),
        RuleEntry("胖次网", "gallery", "IMAGE_SET", "zh", "胖次网.json", false),
        RuleEntry("花瓣网-画板", "gallery", "IMAGE_SET", "zh", "花瓣网-画板.json", false),
        RuleEntry("花瓣网-采集", "gallery", "IMAGE_SET", "zh", "花瓣网-采集.json", false),
        RuleEntry("草榴社区", "video", "HENTAI_VIDEO", "zh", "草榴社区.json", true),
        RuleEntry("萌春画", "gallery", "HENTAI_MANGA", "ja", "萌春画.json", true),
        RuleEntry("零域动漫壁纸", "gallery", "IMAGE_SET", "zh", "零域动漫壁纸.json", false),
    )

    public val byType: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.type }
    public val byContentType: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.contentType }
    public val byLocale: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.locale }
    public val safeOnly: List<RuleEntry> = ALL_RULES.filter { !it.nsfw }
    public val nsfwOnly: List<RuleEntry> = ALL_RULES.filter { it.nsfw }
}
