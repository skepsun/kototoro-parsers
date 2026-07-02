package org.skepsun.kototoro.parsers.site.yealico

/** 36 Yealico rule parsers ({'IMAGE_SET': 36}). Generated. */
public object YealicoParserRegistry {
    public data class RuleEntry(
        val title: String, val type: String, val contentType: String,
        val locale: String, val cacheFile: String, val nsfw: Boolean)

    public val ALL_RULES: List<RuleEntry> = listOf(
        RuleEntry("AVMEMO","gallery","IMAGE_SET","","AVMEMO.json",false),
        RuleEntry("AVMOO","gallery","IMAGE_SET","","AVMOO.json",false),
        RuleEntry("ArtStation","gallery","IMAGE_SET","","ArtStation.json",false),
        RuleEntry("Danbooru Pool","gallery","IMAGE_SET","","Danbooru_Pool.json",false),
        RuleEntry("Danbooru Post","gallery","IMAGE_SET","","Danbooru_Post.json",false),
        RuleEntry("Dribbble","gallery","IMAGE_SET","","Dribbble.json",false),
        RuleEntry("G.E-hentai","gallery","IMAGE_SET","","G.E-hentai.json",false),
        RuleEntry("Gelbooru Pool","gallery","IMAGE_SET","","Gelbooru_Pool.json",false),
        RuleEntry("Gelbooru Post","gallery","IMAGE_SET","","Gelbooru_Post.json",false),
        RuleEntry("HPJav","gallery","IMAGE_SET","","HPJav.json",false),
        RuleEntry("Iwara-動画","gallery","IMAGE_SET","","Iwara-動画.json",false),
        RuleEntry("Iwara-画像","gallery","IMAGE_SET","","Iwara-画像.json",false),
        RuleEntry("Konachan Pool","gallery","IMAGE_SET","","Konachan_Pool.json",false),
        RuleEntry("Konachan Post","gallery","IMAGE_SET","","Konachan_Post.json",false),
        RuleEntry("Pinterest","gallery","IMAGE_SET","","Pinterest.json",false),
        RuleEntry("Redtube","gallery","IMAGE_SET","","Redtube.json",false),
        RuleEntry("Tumblr","gallery","IMAGE_SET","","Tumblr.json",false),
        RuleEntry("UI中国","gallery","IMAGE_SET","","UI中国.json",false),
        RuleEntry("Unsplash","gallery","IMAGE_SET","","Unsplash.json",false),
        RuleEntry("WorldCosplay","gallery","IMAGE_SET","","WorldCosplay.json",false),
        RuleEntry("Xbooru Pool","gallery","IMAGE_SET","","Xbooru_Pool.json",false),
        RuleEntry("Yande.re Pool","gallery","IMAGE_SET","","Yande.re_Pool.json",false),
        RuleEntry("Yande.re Post","gallery","IMAGE_SET","","Yande.re_Post.json",false),
        RuleEntry("anime-pictures.net","gallery","IMAGE_SET","","anime-pictures.net.json",false),
        RuleEntry("deviantART","gallery","IMAGE_SET","","deviantART.json",false),
        RuleEntry("niconico插画","gallery","IMAGE_SET","","niconico插画.json",false),
        RuleEntry("walli","gallery","IMAGE_SET","","walli.json",false),
        RuleEntry("zerochan","gallery","IMAGE_SET","","zerochan.json",false),
        RuleEntry("エロ漫画同人誌","gallery","IMAGE_SET","","エロ漫画同人誌.json",false),
        RuleEntry("写真图吧","gallery","IMAGE_SET","","写真图吧.json",false),
        RuleEntry("安卓壁纸","gallery","IMAGE_SET","","安卓壁纸.json",false),
        RuleEntry("必应每日一图","gallery","IMAGE_SET","","必应每日一图.json",false),
        RuleEntry("维基百科每日图片","gallery","IMAGE_SET","","维基百科每日图片.json",false),
        RuleEntry("美女图片集","gallery","IMAGE_SET","","美女图片集.json",false),
        RuleEntry("花瓣网-画板","gallery","IMAGE_SET","","花瓣网-画板.json",false),
        RuleEntry("花瓣网-采集","gallery","IMAGE_SET","","花瓣网-采集.json",false),
    )
    public val byType = ALL_RULES.groupBy { it.type }
    public val byContentType = ALL_RULES.groupBy { it.contentType }
    public val byLocale = ALL_RULES.groupBy { it.locale }
    public val safeOnly = ALL_RULES.filter { !it.nsfw }
    public val nsfwOnly = ALL_RULES.filter { it.nsfw }
}
