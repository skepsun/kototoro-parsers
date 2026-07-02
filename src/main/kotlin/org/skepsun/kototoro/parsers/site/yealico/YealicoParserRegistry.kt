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
        val nsfw: Boolean,
    )

    public val ALL_RULES: List<RuleEntry> = listOf(
        RuleEntry("AVMEMO", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("AVMOO", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("ArtStation", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("Danbooru Pool", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("Danbooru Post", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("DesignersPics", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("Dribbble", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("E-shuushuu", "gallery", "IMAGE_SET", "ja", false),
        RuleEntry("FindA.Photo", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("G.E-hentai", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("Gelbooru Pool", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("Gelbooru Post", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("HPJav", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("Hentai.Cafe", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("Iwara-動画", "video", "HENTAI_VIDEO", "ja", true),
        RuleEntry("Iwara-画像", "video", "HENTAI_MANGA", "ja", true),
        RuleEntry("Konachan Pool", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("Konachan Post", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("Lofi.E-hentai", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("Pinterest", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("Pixiv", "gallery", "IMAGE_SET", "ja", false),
        RuleEntry("Pornhub", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("Redtube", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("Tube8", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("Tumblr", "api_gallery", "IMAGE_SET", "en", false),
        RuleEntry("UI中国", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("Unsplash", "api_gallery", "IMAGE_SET", "en", false),
        RuleEntry("WorldCosplay", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("XVIDEOS", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("Xbooru Pool", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("Xbooru Post", "gallery", "HENTAI_MANGA", "en", true),
        RuleEntry("Yande.re Pool", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("Yande.re Post", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("YouAv", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("YouPorn", "video", "HENTAI_VIDEO", "en", true),
        RuleEntry("anime-pictures.net", "gallery", "IMAGE_SET", "ja", false),
        RuleEntry("deviantART", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("girlimg", "gallery", "HENTAI_MANGA", "zh", true),
        RuleEntry("niconico插画", "gallery", "IMAGE_SET", "ja", false),
        RuleEntry("wallhaven", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("walli", "api_gallery", "IMAGE_SET", "", false),
        RuleEntry("wallls", "gallery", "IMAGE_SET", "en", false),
        RuleEntry("zerochan", "gallery", "IMAGE_SET", "ja", false),
        RuleEntry("お宝エログ幕府", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("ぷるるんお宝画像庫", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("みんくちゃんねる", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("エロチカ", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("エロ漫画同人誌", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("エロ画像すももちゃんねる", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("モモんガッ(･∀･)!!", "gallery", "IMAGE_SET", "ja", false),
        RuleEntry("写真图吧", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("图虫网", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("堆糖", "api_gallery", "IMAGE_SET", "zh", false),
        RuleEntry("妹子图2", "gallery", "HENTAI_MANGA", "zh", true),
        RuleEntry("宅男女神", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("安卓壁纸", "api_gallery", "IMAGE_SET", "zh", false),
        RuleEntry("必应每日一图", "api_gallery", "IMAGE_SET", "zh", false),
        RuleEntry("搜狗图片", "api_gallery", "IMAGE_SET", "zh", false),
        RuleEntry("维基百科每日图片", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("美女图片集", "gallery", "HENTAI_MANGA", "zh", true),
        RuleEntry("胖次网", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("花瓣网-画板", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("花瓣网-采集", "gallery", "IMAGE_SET", "zh", false),
        RuleEntry("草榴社区", "video", "HENTAI_VIDEO", "zh", true),
        RuleEntry("萌春画", "gallery", "HENTAI_MANGA", "ja", true),
        RuleEntry("零域动漫壁纸", "gallery", "IMAGE_SET", "zh", false),
    )

    public val byType: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.type }
    public val byContentType: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.contentType }
    public val byLocale: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.locale }
    public val safeOnly: List<RuleEntry> = ALL_RULES.filter { !it.nsfw }
    public val nsfwOnly: List<RuleEntry> = ALL_RULES.filter { it.nsfw }
}
