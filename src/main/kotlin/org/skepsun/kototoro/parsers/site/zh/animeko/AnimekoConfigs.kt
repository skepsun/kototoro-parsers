package org.skepsun.kototoro.parsers.site.zh.animeko

/**
 * AUTO-GENERATED: All animeko source configs embedded as Kotlin code.
 * This avoids runtime classpath issues with resource loading on Android.
 */
internal object AnimekoConfigs {

    val all: List<AnimekoMediaSource> = listOf(
        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "omofun111",
            description = "直连，来自omofun动漫视频网，导航：https://enlienli.link/",
            iconUrl = "https://enlienli.link/upload/mxprocms/20230707-1/49f4b8a7fd5cbf77ffcfa7a52e755675.gif",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://enlienli.link/vod/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".module-card-item>.module-card-item-info>.module-card-item-title>a",
            preferShorterName = false
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "div>div>div>div>div>div.module-tab-items-box>.module-tab-item>span",
            matchChannelName = "(?!高清线路3)",
            selectEpisodeLists = ".module-play-list-content",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "girigiri愛動漫",
            description = "",
            iconUrl = "https://picui.ogmua.cn/s1/2026/03/10/69afb489ae341.webp",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://ani.girigirilove.com/search/-------------/?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.video-info-header > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = "body > .box-width .vod-detail .detail-info .slide-info-title",
            selectLinks = "body > .box-width .vod-detail .detail-info > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab > .swiper-wrapper a",
            matchChannelName = "(?<ch>.+?)(\\d+?)",
            selectEpisodeLists = ".anthology-list-box",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = ".anthology-list-play a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "(第\\s*(?<ep>.+)\\s*[话集])|1080P"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(vip|index\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)|(url=(?<v>.+playlist.m3u8))",
            cookies = "quality=1080P",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "风铃动漫",
            description = "直连，来自风铃动漫，导航：https://www.aafun.cc/",
            iconUrl = "https://www.aafun.cc/favicon.ico",
            tier = 1,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.aafun.cc/feng-s.html?wd={keyword}&submit=",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".hl-item-thumb",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".hl-tabs-btn",
            matchChannelName = "^(?!I号线$)(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".hl-plays-list",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "叽哔动漫",
            description = "直连，来自叽哔动漫，导航：https://www.jibi.cc/",
            iconUrl = "https://www.jibi.cc/upload/mxprocms/20240530-1/811f55cb787194c59e3f6d1d8724571c.jpg",
            tier = 2,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.jibi.cc/index.php/vod/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.module-card-item-title > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".module-tab-item.tab-item span",
            matchChannelName = "^(?<ch>.+?)$",
            selectEpisodeLists = ".module-play-list-content",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>\\d+)"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^https?:\\/\\/(?:play\\.xluuss\\.com|hn\\.bfvvs\\.com|play\\.subokk\\.com).+\\.(mp4|m3u8|flv|mkv)(\\?.+)?)|(url=(?<v>https?:\\/\\/(?:play\\.xluuss\\.com|hn\\.bfvvs\\.com|play\\.subokk\\.com).+\\.(mp4|m3u8|flv|mkv)(\\?.+)?))",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "E-ACG",
            description = "",
            iconUrl = "https://i.loli.net/2019/12/09/17hvXK2LemTtgfs.png",
            tier = 2,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://eacg.net/vodsearch/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 5000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "body > div.fed-main-info.fed-min-width > div > div > dl > dd.fed-deta-content > h1 a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".fed-part-layout .fed-deta-info h1.fed-part-eone",
            selectLinks = ".fed-part-layout > .fed-deta-info h1.a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "no-channel",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".fed-play-btns a",
            matchChannelName = "^(?<ch>.+?)(\\d+)?",
            selectEpisodeLists = ".fed-play-item .fed-part-rows",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = ".fed-drop-boxs .fed-part-rows .fed-col-lg1 a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/)(?!.*(?:vip\\.dytt-hot\\.com|vip\\.ffzy-play3\\.com|vip\\.dytt-cine\\.com)).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "稀饭动漫",
            description = "",
            iconUrl = "https://dm1.xfdm.pro/upload/site/20240308-1/813e41f81d6f85bfd7a44bf8a813f9e5.png",
            tier = 2,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://dm1.xfdm.pro/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.video-info-header > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = "body > .box-width .search-box .thumb-content > .thumb-txt",
            selectLinks = "body > .box-width .search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab > .swiper-wrapper a",
            matchChannelName = "^()?(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anthology-list-box",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "森之屋动漫",
            description = "直连，来自森之屋动漫，导航：https://senfun.in/",
            iconUrl = "https://senfun.in/static/senfun/mxtheme/images/favicon.png",
            tier = 3,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://senfun.in/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.module-card-item-title > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "div.module-tab-item.tab-item span",
            matchChannelName = "",
            selectEpisodeLists = "#panel1",
            selectEpisodesFromList = "a.module-play-list-link",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "风车影视",
            description = "直连，来自风车影视，导航：https://www.dongmandaquan.vip/",
            iconUrl = "https://www.dongmandaquan.vip/template/a_0011/images/favicon.ico?v=20221112",
            tier = 3,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.dongmandaquan.vip/vodsearch/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = false,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".post-list .block-info .entry-title>a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".ewave-playlist-tab>.swiper-wrapper>li>a",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".playlist",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "去看吧",
            description = "直连，来自去看吧，导航：https://11kt.net/",
            iconUrl = "https://11kt.net/klogo.png",
            tier = 3,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://11kt.net/index.php/vod/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".fed-part-eone.fed-font-xvi a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".fed-drop-boxs.fed-drop-tops.fed-matp-v a",
            matchChannelName = "",
            selectEpisodeLists = ".fed-play-item.fed-drop-item",
            selectEpisodesFromList = "a.fed-btns-info",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "海星动漫",
            description = "直连，来自海星动漫，导航：https://www.haixingdmx.com/",
            iconUrl = "https://www.haixingdmx.com/hdst/hx_pic/favicon.ico",
            tier = 3,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.haixingdmx.com/s_all?ex=1&kw={keyword}",
                searchUseOnlyFirstWord = false,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div ul li h2 > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".tabs .menu0 li",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".tabs .main0 div ul",
            selectEpisodesFromList = "li a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "(第\\s*(?<ep>.+)\\s*[话集])|(\\d+)"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080P",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "樱花动漫",
            description = "直连，来自樱花动漫，导航：https://www.yhdm6go.top/",
            iconUrl = "https://yhdm6go.top/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://yhdm6go.top/vsh/-------------/?wd={keyword}&submit=",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "h4.vodlist_title > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "div.play_source_tab a[href=\"javascript:void(0);\"]",
            matchChannelName = "",
            selectEpisodeLists = "div.play_list_box.hide",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^https?:\\/\\/(?:[^\\/]+\\.)?cdn\\.ryplay12\\.com.+\\.(mp4|m3u8|flv|mkv)(\\?.+)?)|(url=(?<v>https?:\\/\\/(?:[^\\/]+\\.)?cdn\\.ryplay12\\.com.+\\.(mp4|m3u8|flv|mkv)(\\?.+)?))",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "第一动漫",
            description = "直连，NSFW，第一动漫，导航：https://1anime.org/",
            iconUrl = "https://1anime2025.me/template/conch/asset/img/favicon1.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://1anime2025.me/vodsearch/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.video-info-header > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "div.module-tab-item > span",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".module-list.module-player-list",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "次元方舟",
            description = "直连，来自次元方舟，导航：https://www.cyfz.vip/",
            iconUrl = "https://cdn-y.tencentmusic.com/musician/commonPic/cos_8ea4f0680719892017ebae4d411f84c9.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "http://www.cyfz.vip/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".thumb-txt a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab a",
            matchChannelName = "(?<ch>.*?\\d)",
            selectEpisodeLists = ".anthology-list-play.size",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "米粒动漫",
            description = "直连，来自米粒动漫，导航：https://milimili.nl/",
            iconUrl = "https://milimili.nl/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://milimili.nl/search?q={keyword}",
                searchUseOnlyFirstWord = false,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "#search_list > ul > li > h6 > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "section .menu-tabs",
            matchChannelName = "",
            selectEpisodeLists = ".row.list-unstyled.gutters-1 ",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "萌道动漫",
            description = "直连，来自萌道动漫，导航：https://www.gpjda.com/",
            iconUrl = "https://www.gpjda.com/statics/img/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.gpjda.com/search.php?searchword={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "ul.stui-vodlist li h4 a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".stui-pannel > div > h3 >span",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = "#playlist #playlist1",
            selectEpisodesFromList = "ul li a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "UZVOD",
            description = "直连，来自UZVOD优质影院，导航：https://uzvod.com/",
            iconUrl = "https://uzvod.com/template/mxone/mxstatic/picture/favicon.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://uzvod.com/vodsearch/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".video-info-header>h3>a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".tab-item",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".module-blocklist>.scroll-content",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "嘀哩嘀哩",
            description = "直连，来自嘀哩嘀哩，导航：https://dilidili.io/",
            iconUrl = "https://bkimg.cdn.bcebos.com/pic/c2fdfc039245d688d43f36eaa0986a1ed21b0ef48e71?x-bce-process=image/format,f_auto/quality,Q_70/resize,m_lfit,limit_1,w_536",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://dilidili.io/search?q={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "dl h3 a[href^='/anime/']",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "span.pk_time",
            matchChannelName = "",
            selectEpisodeLists = "ul.clear",
            selectEpisodesFromList = "a span",
            selectEpisodeLinksFromList = "a",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "2k动漫",
            description = "直连，来自2k动漫，导航：https://www.2kdm.org/",
            iconUrl = "https://www.2kdm.org/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.2kdm.org/search.php?searchword={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "#cont_pub > dl> dd:nth-child(2) > div > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "#liebiao > font:nth-child(1)",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".playList",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "新优酷",
            description = "直连，来自新优酷，导航：https://www.youknow.tv/",
            iconUrl = "https://www.youknow.tv/upload/mxprocms/20240119-1/55e70266f81055026bb40dee6a603812.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.youknow.tv/search/-------------/?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".module-card-item-title > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = null,
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".module-tab-items-box > .module-tab-item.tab-item",
            matchChannelName = "(?<ch>.*?\\d)",
            selectEpisodeLists = ".module-play-list-content.module-play-list-base",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = null,
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "$^",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http).+\\.(mp4|m3u8|flv|mkv))|(url=(?<v>http(s)?:\\/\\/.+\\.(mp4|m3u8|flv|mkv)))|(^http(s)?:\\/\\/(?!.*http).+(sign\\.bytetos|sign\\.byteimg|mcloud\\.139|cloudflarestorage|tos-cn)\\.com(?!.*\\.ts).+(-expires|-signature))|((bilivideo|akamaized|szbdyd)\\.com(?!.*\\.ts))|(\\/video\\/tos\\/alisg\\/)|(\\/video\\/.*mime_type=video)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "hanime1[1080p]",
            description = "魔法，NSFW，1080P，网址：https://hanime1.me/",
            iconUrl = "https://gitee.com/w658/configs-online/raw/master/animeko/favicon/hanime1[1080].png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://hanime1.me/search?query={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 2,
                rawBaseUrl = "",
                requestInterval = 1233,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.home-rows-videos-wrapper a",
            preferShorterName = false
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = "#home-rows-wrapper > div.content-padding-new > div > div > div > div > a > div.title",
            selectLinks = "#home-rows-wrapper > div.content-padding-new > div > div > div> div > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "#video-artist-name",
            matchChannelName = "",
            selectEpisodeLists = "#playlist-scroll",
            selectEpisodesFromList = "div.related-watch-wrap .card-mobile-title",
            selectEpisodeLinksFromList = "#playlist-scroll > div.related-watch-wrap > a",
            matchEpisodeSortFromName = "(?<ep>(?:SP\\s\\d+|\\d+))"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#playlist-scroll > div.related-watch-wrap .card-mobile-title",
            selectEpisodeLinks = "#playlist-scroll > div.related-watch-wrap > a",
            matchEpisodeSortFromName = "(?<ep>(?:SP\\s\\d+|\\d+))"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = false,
            distinguishChannelName = false
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^(?=.*(index))(?=.*(1080|720|480)).*?(m3u8|mp4|vip|xigua\\.php).*",
            matchVideoUrl = "(?<v>https?:\\/\\/(?:[^\\/]*\\.)?(vdownload|abre-videos|xvideos).*?(1080).*\\.(m3u8|mp4|vip|xigua\\.php)(?:\\?.+)?)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "hanime1[720p]",
            description = "魔法，NSFW，Hanime1无1080P时可使用，网址：https://hanime1.me/",
            iconUrl = "https://gitee.com/w658/configs-online/raw/master/animeko/favicon/hanime1[720].png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://hanime1.me/search?query={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 2,
                rawBaseUrl = "",
                requestInterval = 1233,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.home-rows-videos-wrapper a",
            preferShorterName = false
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = "#home-rows-wrapper > div.content-padding-new > div > div > div > div > a > div.title",
            selectLinks = "#home-rows-wrapper > div.content-padding-new > div > div > div> div > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "#video-artist-name",
            matchChannelName = "",
            selectEpisodeLists = "#playlist-scroll",
            selectEpisodesFromList = "div.related-watch-wrap .card-mobile-title",
            selectEpisodeLinksFromList = "#playlist-scroll > div.related-watch-wrap > a",
            matchEpisodeSortFromName = "(?<ep>(?:SP\\s\\d+|\\d+))"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#playlist-scroll > div.related-watch-wrap .card-mobile-title",
            selectEpisodeLinks = "#playlist-scroll > div.related-watch-wrap > a",
            matchEpisodeSortFromName = "(?<ep>(?:SP\\s\\d+|\\d+))"
        ),
                defaultResolution = "720P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = false,
                filterBySubjectName = false,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = false,
            distinguishChannelName = false
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^(?=.*(index))(?=.*(1080|720|480)).*?(m3u8|mp4|vip|xigua\\.php).*",
            matchVideoUrl = "(?<v>https?:\\/\\/(?:[^\\/]*\\.)?(vdownload|abre-videos|xvideos).*?(1080|720|480).*\\.(m3u8|mp4|vip|xigua\\.php)(?:\\?.+)?)",
            cookies = "quality=720",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "风车动漫",
            description = "",
            iconUrl = "https://vdm10.com/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://vdm10.com/search/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "body > div.sear_box > div.sear_con > div> div > div > div.result_title > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "#play div.con_juji_bg div.playlist-tab ul li.tab-switch a",
            matchChannelName = "^()?(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".con_c2_list",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = ".con_c2_list > li > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "趣动漫",
            description = "",
            iconUrl = "https://www.qdm8.com/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.qdm8.com/search/-------------.html?wd={keyword}&submit=",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "#searchList > li > div.detail > h4 > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "body > div.container > div > div.col-md-wide-7.col-xs-1.padding-0 > div:nth-child(4) > div > div.myui-panel_hd > div > ul > li > a",
            matchChannelName = "^()?(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = "body > div.container > div > div.col-md-wide-7.col-xs-1.padding-0 > div:nth-child(4) > div > div.tab-content.myui-panel_bd > div",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#playlist1 > ul > li > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 1,
            name = "咕咕番",
            description = "",
            iconUrl = "https://www.gugu3.com/upload/site/20240809-1/7128d3562abaed14571d6227e1240aab.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.gugu3.com/index.php/vod/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 5000,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".box-width a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content .thumb-txt",
            selectLinks = ".search-box .thumb-menu a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab  .swiper-wrapper a",
            matchChannelName = "^()?(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anthology-list-box",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)|(.+player/\\?url=(?<v>.+))",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "喵物次元",
            description = "",
            iconUrl = "https://www.mwcy.net/upload/site/20241103-1/0e9b2520a7f643e88a7f51412f6b4665.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.mwcy.net/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "body > div.box-width > div > div.row-9 > div > div > div.left.public-list-bj > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab > .swiper-wrapper a",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anthology-list-box",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)|(.+ec\\.php\\?code=qw\\&if=1\\&url=(?<v>[http].+))",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "虾皮动漫",
            description = "",
            iconUrl = "https://fileserver.cdn.huya.com/huyavideo_pic_upload/c2428389216a4bfba24cd0bf2f011a62.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://xiapidm.com/xvseabcdefghigklm.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.video-info-header > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab > .swiper-wrapper a",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anthology-list-box",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "漫次元",
            description = "",
            iconUrl = "https://www.mcydh.com/upload/site/20240712-1/85d6f66f330692404fce0f4067e47153.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.mcydh.com/vodsearch/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.video-info-header > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab > .swiper-wrapper a",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anthology-list-box",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "720P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "蜜桃动漫",
            description = "",
            iconUrl = "https://www.mitaodm.com/template/jianbai/statics/img/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.mitaodm.com/vodsearch.html?wd={keyword}&submit=",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 5000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div > div > h4 > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "div.stui-vodlist__head > h3.title",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = "div.stui-vodlist__head > .stui-content__playlist",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "次元城动画",
            description = "",
            iconUrl = "https://www.cyc-anime.net/upload/site/20240319-1/25e700991446a527804c82a744731b60.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.cyc-anime.net/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 0,
                subjectFormatId = "indexed",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".module-main .module-items .module-card-item-info a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".anthology-tab",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anthology-list-play",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#y-playList",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 1,
            name = "MX动漫",
            description = "",
            iconUrl = "https://www.mxdm.xyz/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.mxdm.xyz/search/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 5000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.video-info-header > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = null,
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".module-tab-item",
            matchChannelName = "(?<ch>.+?)(\\d+?)",
            selectEpisodeLists = ".module-list > .module-blocklist",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+/m3u8",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080P",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "动漫蛋",
            description = "",
            iconUrl = "https://cdn.jsdelivr.net/gh/zkk7/jsku@master/statics/img/favicon.ico",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.dmdan8.com/search/-------------.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = false,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 5000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = ".stui-pannel-box .stui-vodlist__media h3 a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = "",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = ".stui-pannel-box .nav-tabs li a",
            matchChannelName = "^()?(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".tab-content",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "1080P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "饭团动漫",
            description = "",
            iconUrl = "https://acgfta.com/template/ft-v2/icon/acgfantuan-icon-72.png",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://acgfta.com/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "body > main > div > div.mt-2-5 > div > div > div > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "body > main > div > div.row.mt-1-25.mb-5 > div > div > div > ul > li > button",
            matchChannelName = "^(?<ch>.+?)(\\d+)?$",
            selectEpisodeLists = ".anime-episode",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#线路一 > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "720P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/)(?!.*google-analytics).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)|(.+player/\\?url=(?<v>.+))",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        ),

        AnimekoMediaSource(
            factoryId = "web-selector",
            version = 2,
            name = "番茄动漫",
            description = "",
            iconUrl = "https://www.fqdm.cc/upload/mxprocms/20240530-1/3d17fab3cb763e6ad7031974bf87f322.jpg",
            tier = 4,
            searchConfig = AnimekoSearchConfig(
                searchUrl = "https://www.fqdm.cc/index.php/vod/search.html?wd={keyword}",
                searchUseOnlyFirstWord = true,
                searchRemoveSpecial = true,
                searchUseSubjectNamesCount = 1,
                rawBaseUrl = "",
                requestInterval = 3000,
                subjectFormatId = "a",
                selectorSubjectFormatA = AnimekoSelectorA(
            selectLists = "div.module-card-item-info > div.module-card-item-title > a",
            preferShorterName = true
        ),
                selectorSubjectFormatIndexed = AnimekoSelectorIndexed(
            selectNames = ".search-box .thumb-content > .thumb-txt",
            selectLinks = ".search-box .thumb-menu > a",
            preferShorterName = true
        ),
                selectorSubjectFormatJsonPathIndexed = AnimekoSelectorJsonPathIndexed(
            selectLinks = "$[*]['url', 'link']",
            selectNames = "$[*]['title','name']",
            preferShorterName = true
        ),
                channelFormatId = "index-grouped",
                selectorChannelFormatFlattened = AnimekoSelectorChannelFlattened(
            selectChannelNames = "#y-playList > div > span",
            matchChannelName = "",
            selectEpisodeLists = "#panel1",
            selectEpisodesFromList = "a",
            selectEpisodeLinksFromList = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                selectorChannelFormatNoChannel = AnimekoSelectorChannelNoChannel(
            selectEpisodes = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
            selectEpisodeLinks = "",
            matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]"
        ),
                defaultResolution = "720P",
                defaultSubtitleLanguage = "CHS",
                filterByEpisodeSort = true,
                filterBySubjectName = true,
                selectMedia = AnimekoSelectMedia(
            distinguishSubjectName = true,
            distinguishChannelName = true
        ),
                matchVideo = AnimekoMatchVideo(
            enableNestedUrl = true,
            matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?",
            matchVideoUrl = "(^https?:\\/\\/(?:play\\.xluuss\\.com|hn\\.bfvvs\\.com|play\\.subokk\\.com).+((\\.mp4)|(\\.mkv)|(\\.m3u8$)).*(\\?.+)?)|(.+top/\\?url=(?<v>https?:\\/\\/(?:play\\.xluuss\\.com|hn\\.bfvvs\\.com|play\\.subokk\\.com).+))",
            cookies = "quality=1080",
            addHeadersToVideo = mapOf("referer" to "", "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
        )
            )
        )
    )

    private val byName: Map<String, AnimekoMediaSource> = all.associateBy { it.name }

    fun byName(name: String): AnimekoMediaSource =
        byName[name] ?: error("Animeko source not found: $name")

    fun count(): Int = all.size
}
