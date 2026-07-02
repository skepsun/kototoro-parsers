package org.skepsun.kototoro.parsers.site.yealico

/** 36 Yealico rules ({'HENTAI_MANGA': 15, 'IMAGE_SET': 18, 'HENTAI_VIDEO': 3}). Generated. Do not edit. */
public object YealicoParserRegistry {
    public data class RuleEntry(
        val title: String, val type: String, val contentType: String,
        val locale: String, val cacheFile: String, val nsfw: Boolean)

    public val ALL_RULES: List<RuleEntry> = listOf(
        RuleEntry("AVMEMO","gallery","HENTAI_MANGA","en","AVMEMO.json",true),
        RuleEntry("AVMOO","gallery","HENTAI_MANGA","en","AVMOO.json",true),
        RuleEntry("ArtStation","gallery","IMAGE_SET","en","ArtStation.json",false),
        RuleEntry("Danbooru Pool","gallery","HENTAI_MANGA","ja","Danbooru_Pool.json",true),
        RuleEntry("Danbooru Post","gallery","HENTAI_MANGA","ja","Danbooru_Post.json",true),
        RuleEntry("Dribbble","gallery","IMAGE_SET","en","Dribbble.json",false),
        RuleEntry("G.E-hentai","gallery","HENTAI_MANGA","en","G.E-hentai.json",true),
        RuleEntry("Gelbooru Pool","gallery","HENTAI_MANGA","ja","Gelbooru_Pool.json",true),
        RuleEntry("Gelbooru Post","gallery","HENTAI_MANGA","ja","Gelbooru_Post.json",true),
        RuleEntry("HPJav","gallery","HENTAI_VIDEO","en","HPJav.json",true),
        RuleEntry("Iwara-動画","gallery","HENTAI_VIDEO","ja","Iwara-動画.json",true),
        RuleEntry("Iwara-画像","gallery","HENTAI_MANGA","ja","Iwara-画像.json",true),
        RuleEntry("Konachan Pool","gallery","HENTAI_MANGA","ja","Konachan_Pool.json",true),
        RuleEntry("Konachan Post","gallery","HENTAI_MANGA","ja","Konachan_Post.json",true),
        RuleEntry("Pinterest","gallery","IMAGE_SET","en","Pinterest.json",false),
        RuleEntry("Redtube","gallery","HENTAI_VIDEO","en","Redtube.json",true),
        RuleEntry("Tumblr","gallery","IMAGE_SET","en","Tumblr.json",false),
        RuleEntry("UI中国","gallery","IMAGE_SET","zh","UI中国.json",false),
        RuleEntry("Unsplash","gallery","IMAGE_SET","en","Unsplash.json",false),
        RuleEntry("WorldCosplay","gallery","IMAGE_SET","en","WorldCosplay.json",false),
        RuleEntry("Xbooru Pool","gallery","HENTAI_MANGA","en","Xbooru_Pool.json",true),
        RuleEntry("Yande.re Pool","gallery","HENTAI_MANGA","ja","Yande.re_Pool.json",true),
        RuleEntry("Yande.re Post","gallery","HENTAI_MANGA","ja","Yande.re_Post.json",true),
        RuleEntry("anime-pictures.net","gallery","IMAGE_SET","ja","anime-pictures.net.json",false),
        RuleEntry("deviantART","gallery","IMAGE_SET","en","deviantART.json",false),
        RuleEntry("niconico插画","gallery","IMAGE_SET","ja","niconico插画.json",false),
        RuleEntry("walli","gallery","IMAGE_SET","","walli.json",false),
        RuleEntry("zerochan","gallery","IMAGE_SET","ja","zerochan.json",false),
        RuleEntry("エロ漫画同人誌","gallery","HENTAI_MANGA","ja","エロ漫画同人誌.json",true),
        RuleEntry("写真图吧","gallery","IMAGE_SET","zh","写真图吧.json",false),
        RuleEntry("安卓壁纸","gallery","IMAGE_SET","zh","安卓壁纸.json",false),
        RuleEntry("必应每日一图","gallery","IMAGE_SET","zh","必应每日一图.json",false),
        RuleEntry("维基百科每日图片","gallery","IMAGE_SET","zh","维基百科每日图片.json",false),
        RuleEntry("美女图片集","gallery","HENTAI_MANGA","zh","美女图片集.json",true),
        RuleEntry("花瓣网-画板","gallery","IMAGE_SET","zh","花瓣网-画板.json",false),
        RuleEntry("花瓣网-采集","gallery","IMAGE_SET","zh","花瓣网-采集.json",false),
    )
    public val byType = ALL_RULES.groupBy { it.type }
    public val byContentType = ALL_RULES.groupBy { it.contentType }
    public val byLocale = ALL_RULES.groupBy { it.locale }
    public val safeOnly = ALL_RULES.filter { !it.nsfw }
    public val nsfwOnly = ALL_RULES.filter { it.nsfw }
}
