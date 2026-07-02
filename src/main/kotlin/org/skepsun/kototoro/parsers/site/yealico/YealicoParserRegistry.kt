package org.skepsun.kototoro.parsers.site.yealico

/**
 * Registry of all Yealico site rule sources (67 total: api_gallery=7, gallery=49, video=11).
 * Excludes sites that already have dedicated parsers (nhentai, wnacg, exhentai, hanime).
 * Auto-generated — do not edit by hand.
 */
public object YealicoParserRegistry {
    public data class RuleEntry(
        val title: String,
        val type: String,
        val status: String,
        val cacheFile: String,
    )

    public val ALL_RULES: List<RuleEntry> = listOf(
        RuleEntry("AVMEMO", "gallery", "可达", "AVMEMO.json"),
        RuleEntry("AVMOO", "gallery", "可达", "AVMOO.json"),
        RuleEntry("ArtStation", "gallery", "可达", "ArtStation.json"),
        RuleEntry("Danbooru Pool", "gallery", "反爬(403)", "Danbooru_Pool.json"),
        RuleEntry("Danbooru Post", "gallery", "反爬(403)", "Danbooru_Post.json"),
        RuleEntry("DesignersPics", "gallery", "反爬(403)", "DesignersPics.json"),
        RuleEntry("Dribbble", "gallery", "可达", "Dribbble.json"),
        RuleEntry("E-shuushuu", "gallery", "可达", "E-shuushuu.json"),
        RuleEntry("FindA.Photo", "gallery", "可达", "FindA.Photo.json"),
        RuleEntry("G.E-hentai", "gallery", "可达", "G.E-hentai.json"),
        RuleEntry("GayHub", "video", "可达", "GayHub.json"),
        RuleEntry("Gelbooru Pool", "gallery", "限流(429)", "Gelbooru_Pool.json"),
        RuleEntry("Gelbooru Post", "gallery", "限流(429)", "Gelbooru_Post.json"),
        RuleEntry("HPJav", "video", "可达", "HPJav.json"),
        RuleEntry("Hentai.Cafe", "gallery", "可达", "Hentai.Cafe.json"),
        RuleEntry("Iwara-動画", "video", "反爬(403)", "Iwara-動画.json"),
        RuleEntry("Iwara-画像", "video", "反爬(403)", "Iwara-画像.json"),
        RuleEntry("Konachan Pool", "gallery", "可达", "Konachan_Pool.json"),
        RuleEntry("Konachan Post", "gallery", "可达", "Konachan_Post.json"),
        RuleEntry("Lofi.E-hentai", "gallery", "可达", "Lofi.E-hentai.json"),
        RuleEntry("Pinterest", "gallery", "可达", "Pinterest.json"),
        RuleEntry("Pixiv", "gallery", "可达", "Pixiv.json"),
        RuleEntry("Pornhub", "video", "可达", "Pornhub.json"),
        RuleEntry("Redtube", "video", "可达", "Redtube.json"),
        RuleEntry("Tube8", "video", "可达", "Tube8.json"),
        RuleEntry("Tumblr", "api_gallery", "需登录", "Tumblr.json"),
        RuleEntry("UI中国", "gallery", "需登录", "UI中国.json"),
        RuleEntry("Unsplash", "api_gallery", "可达", "Unsplash.json"),
        RuleEntry("WorldCosplay", "gallery", "可达", "WorldCosplay.json"),
        RuleEntry("XVIDEOS", "video", "可达", "XVIDEOS.json"),
        RuleEntry("Xbooru Pool", "gallery", "可达", "Xbooru_Pool.json"),
        RuleEntry("Xbooru Post", "gallery", "可达", "Xbooru_Post.json"),
        RuleEntry("Yande.re Pool", "gallery", "可达", "Yande.re_Pool.json"),
        RuleEntry("Yande.re Post", "gallery", "可达", "Yande.re_Post.json"),
        RuleEntry("YouAv", "video", "可达", "YouAv.json"),
        RuleEntry("YouPorn", "video", "可达", "YouPorn.json"),
        RuleEntry("anime-pictures.net", "gallery", "反爬(403)", "anime-pictures.net.json"),
        RuleEntry("deviantART", "gallery", "反爬(403)", "deviantART.json"),
        RuleEntry("girlimg", "gallery", "被墙", "girlimg.json"),
        RuleEntry("niconico插画", "gallery", "反爬(403)", "niconico插画.json"),
        RuleEntry("wallhaven", "gallery", "可达", "wallhaven.json"),
        RuleEntry("walli", "api_gallery", "可达", "walli.json"),
        RuleEntry("wallls", "gallery", "可达", "wallls.json"),
        RuleEntry("zerochan", "gallery", "可达", "zerochan.json"),
        RuleEntry("お宝エログ幕府", "gallery", "可达", "お宝エログ幕府.json"),
        RuleEntry("ぷるるんお宝画像庫", "gallery", "可达", "ぷるるんお宝画像庫.json"),
        RuleEntry("みんくちゃんねる", "gallery", "可达", "みんくちゃんねる.json"),
        RuleEntry("エロチカ", "gallery", "可达", "エロチカ.json"),
        RuleEntry("エロ漫画同人誌", "gallery", "需登录", "エロ漫画同人誌.json"),
        RuleEntry("エロ画像すももちゃんねる", "gallery", "可达", "エロ画像すももちゃんねる.json"),
        RuleEntry("モモんガッ(･∀･)!!", "gallery", "可达", "モモんガッ_______.json"),
        RuleEntry("写真图吧", "gallery", "可达", "写真图吧.json"),
        RuleEntry("图虫网", "gallery", "可达", "图虫网.json"),
        RuleEntry("堆糖", "api_gallery", "URL编码", "堆糖.json"),
        RuleEntry("妹子图2", "gallery", "可达", "妹子图2.json"),
        RuleEntry("宅男女神", "gallery", "可达", "宅男女神.json"),
        RuleEntry("安卓壁纸", "api_gallery", "可达", "安卓壁纸.json"),
        RuleEntry("必应每日一图", "api_gallery", "可达", "必应每日一图.json"),
        RuleEntry("搜狗图片", "api_gallery", "URL编码", "搜狗图片.json"),
        RuleEntry("维基百科每日图片", "gallery", "可达", "维基百科每日图片.json"),
        RuleEntry("美女图片集", "gallery", "反爬(403)", "美女图片集.json"),
        RuleEntry("胖次网", "gallery", "可达", "胖次网.json"),
        RuleEntry("花瓣网-画板", "gallery", "可达", "花瓣网-画板.json"),
        RuleEntry("花瓣网-采集", "gallery", "可达", "花瓣网-采集.json"),
        RuleEntry("草榴社区", "video", "可达", "草榴社区.json"),
        RuleEntry("萌春画", "gallery", "被墙", "萌春画.json"),
        RuleEntry("零域动漫壁纸", "gallery", "被墙", "零域动漫壁纸.json"),
    )

    public val byType: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.type }
    public val byStatus: Map<String, List<RuleEntry>> = ALL_RULES.groupBy { it.status }
    public val accessible: List<RuleEntry> = ALL_RULES.filter { it.status == "可达" }
    public val restricted: List<RuleEntry> = ALL_RULES.filter { it.status != "可达" }
}
