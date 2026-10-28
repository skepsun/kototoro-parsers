package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import java.util.Base64
import java.util.EnumSet
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Headers

/**
 * 51吃瓜 - Mirages Typecho 主题站。
 *
 * 站点结构（列表页）：
 * - 文章卡片 = article[itemscope=BlogPosting] + /archives/{id} 链接；ad-item 为外链广告
 * - 封面：2026-10 起改为 div.blog-background[z-bg-loader-url]（无 <img>、无内联脚本），
 *   旧模板才是 loadBannerDirect 脚本或 img 懒加载属性，见 [listCoverUrl]
 *
 * 站点结构（详情页）：
 * - 正文容器：#post .post-content[itemprop=articleBody]
 * - 正文内混有大量非正文块（广告按钮/站址公告/下载APP按钮/上下篇/热门推荐/评论区），
 *   统一由 [noiseSelector] 移除后再取文本或图片
 * - 视频：正文内 div.dplayer[data-config]，JSON 中只有 video.url 是主视频；
 *   video_h265 是同内容的 H.265 编码版（多数播放器不支持），video_player_ads 是贴片广告
 * - 图片（封面与图集）：AES-128-CBC 加密，密钥/IV 见 [decryptImage]
 */
@ContentSourceParser(name = "CG51", title = "51吃瓜", locale = "zh", type = ContentType.HENTAI_VIDEO)
internal class Cg51(context: ContentLoaderContext) : PagedContentParser(
    context = context,
    source = ContentParserSource.CG51,
    pageSize = 20,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("51cg1.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    private val categories = listOf(
        "今日吃瓜" to "wpcz",
        "学生校园" to "xsxy",
        "网红黑料" to "whhl",
        "热门大瓜" to "rdsj",
        "吃瓜榜单" to "mrdg",
        "必看大瓜" to "bkdg",
        "看片娱乐" to "ysyl",
        "每日大赛" to "mrds",
        "伦理道德" to "lldd",
        "探花精选" to "thjx",
        "网黄合集" to "whhj",
        "免费短剧" to "cbdj",
        "骚男骚女" to "snsn",
        "明星黑料" to "whmx",
        "海外吃瓜" to "hwcg",
        "领导干部" to "ldcg",
        "吃瓜看戏" to "qubk",
        "擦边聊骚" to "dcbq",
        "51涨知识" to "zzs",
        "原创博主" to "yczq",
        "51剧场" to "51djc",
    )

    private val violentKeywords = listOf("血腥", "暴力", "虐待", "杀人", "死亡", "尸体", "袭警", "毒贩", "吸毒", "强奸", "冰毒")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Referer", "https://$domain/")
        .build()

    /**
     * 正文容器内的非正文元素：
     * - .txt-apps / .tjtagmanager / .btn-download / .copy-box / button：广告按钮与分享框
     * - blockquote：站址公告或跨作品推广
     * - table：站内导航表格
     * - .post-near：上一篇/下一篇
     * - .content-copyright：转载声明
     * - .tags：标签块（单独解析）
     * - .hot-news-section / .content-tabs：热门推荐与评论标签页
     * - .dplayer：播放器（视频地址单独解析）
     * - .line / script / style：分隔线与脚本样式
     */
    private val noiseSelector = ".txt-apps, .tjtagmanager, .btn-download, .copy-box, button, blockquote, table," +
        " .post-near, .content-copyright, .tags, .hot-news-section, .content-tabs, .dplayer, .line, script, style"

    /** 主题/插件资源路径（占位图、图标等，非内容图片） */
    private val assetPathPrefixes = listOf("/usr/themes/", "/usr/plugins/")

    /** 懒加载属性优先级：自定义解密属性 > 通用懒加载 > 原始 src */
    private val lazySrcAttrs = listOf("data-xkrkllgl", "data-src", "data-original")

    /**
     * 列表封面属性。2026-10 模板改版把封面从 loadBannerDirect 脚本改成声明式背景加载
     * （image.20260416.js 读取 z-bg-loader-url），文章卡片里不再有 <img> 与内联脚本。
     */
    private val listCoverAttrs = listOf("z-bg-loader-url", "data-bg-loader-url")

    /** 旧模板：loadBannerDirect('封面地址', ...) 内联脚本 */
    private val bannerScriptRegex = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""")

    /** 兜底：background-image:url(...) 内联样式 */
    private val cssBackgroundRegex = Regex(
        """background(?:-image)?\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val tags = categories.map { (name, id) ->
            ContentTag(key = "category:$id", title = name, source = source)
        }.toSet()
        return ContentListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(ContentTagGroup("分类", tags)),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val selectedCategory = filter.tags.firstOrNull { it.key.startsWith("category:") }
            ?.key?.removePrefix("category:")
        val url = when {
            !filter.query.isNullOrEmpty() -> {
                if (page == 1) "https://$domain/search/${filter.query}"
                else "https://$domain/search/${filter.query}/$page"
            }
            selectedCategory != null -> {
                if (page == 1) "https://$domain/category/$selectedCategory/"
                else "https://$domain/category/$selectedCategory/$page/"
            }
            else -> "https://$domain/page/$page/"
        }
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        // 封面是 AES 加密字节流（与图集图片同密钥），解密为临时文件后才能显示
        return coroutineScope {
            parseList(doc).map { item ->
                async {
                    val cover = item.coverUrl
                    if (cover != null && !cover.startsWith("data:") && !cover.startsWith("file:")) {
                        item.copy(coverUrl = decryptImage(cover) ?: cover)
                    } else {
                        item
                    }
                }
            }.awaitAll()
        }
    }

    /** 列表条目：article 且首个链接指向 /archives/{id}；广告条目（article.ad-item）链接到外部域名，被此规则排除 */
    internal fun parseList(doc: Document): List<Content> {
        return doc.select("article").mapNotNull { node ->
            val link = node.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            if (!href.contains("/archives/")) return@mapNotNull null
            val absoluteUrl = href.toAbsoluteUrl()

            val titleNode = node.selectFirst("h2, h3, .title, .post-title")
            titleNode?.select(".wrap, .wraps")?.remove()
            val title = link.attr("title").ifEmpty {
                titleNode?.text()?.trim() ?: link.text().trim()
            }
            if (title.isEmpty()) return@mapNotNull null
            if (violentKeywords.any { title.contains(it, ignoreCase = true) }) return@mapNotNull null

            val cover = listCoverUrl(node)
            Content(
                id = generateUid(absoluteUrl),
                title = title,
                url = absoluteUrl,
                publicUrl = absoluteUrl,
                coverUrl = cover,
                source = source,
                tags = emptySet(),
                authors = emptySet(),
                altTitles = emptySet(),
                rating = 0f,
                state = null,
                contentRating = ContentRating.ADULT,
                largeCoverUrl = null,
                description = null,
                chapters = null,
            )
        }
    }

    /**
     * 列表封面：声明式背景属性（当前模板）> 旧模板 loadBannerDirect 脚本 >
     * 内联 background-image 样式 > <img> 懒加载属性。
     */
    internal fun listCoverUrl(node: Element): String? {
        listCoverAttrs.firstNotNullOfOrNull { name ->
            node.selectFirst("[$name]")?.attr(name)?.trim()?.takeIf { it.isNotEmpty() }
        }?.let { return it.sanitizeUrl() }

        bannerScriptRegex.find(node.outerHtml())?.groupValues?.get(1)?.let { return it.sanitizeUrl() }

        node.selectFirst("[style*=background]")?.attr("style")?.let { style ->
            cssBackgroundRegex.find(style)?.groupValues?.get(1)?.let { return it.sanitizeUrl() }
        }

        return node.selectFirst("img")?.contentImageSrc()
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(), getRequestHeaders()).parseHtml()
        val result = parseDetails(doc, manga)
        // 列表页已解密的封面直接沿用；详情页新提取的封面需要解密
        val cover = result.largeCoverUrl
        if (cover != null && cover != manga.coverUrl && !cover.startsWith("data:") && !cover.startsWith("file:")) {
            return result.copy(largeCoverUrl = decryptImage(cover) ?: cover)
        }
        return result
    }

    internal fun parseDetails(doc: Document, manga: Content): Content {
        val body = findContentBody(doc)
        val clean = cleanBody(body)

        // 描述：移除噪音块后的正文文本
        val desc = clean.text().replace(Regex("\\s+"), " ").trim().take(500).takeIf { it.isNotEmpty() }

        // 封面：列表页封面优先；详情页取 itemprop=image（og:image 是主题默认分享图，不可用），
        // 再退到清洗后正文的首图（原始正文首图可能是广告图）
        val cover = manga.coverUrl
            ?: doc.selectFirst("#post meta[itemprop=image]")?.attr("content")?.takeIf { it.isNotBlank() }?.sanitizeUrl()
            ?: clean.selectFirst("img")?.contentImageSrc()

        // 标签：仅 #post 内的文章标签块
        val tags = doc.select("#post .tags a").mapNotNull {
            val name = it.text().trim()
            if (name.isNotEmpty()) ContentTag(key = name, title = name, source = source) else null
        }.toSet()

        // 章节：每个视频一个章节；无视频则视为图集
        val videoLinks = extractVideos(doc)
        val chapters = if (videoLinks.isNotEmpty()) {
            videoLinks.mapIndexed { index, link ->
                ContentChapter(
                    id = generateUid("${manga.url}#video_index=$index"),
                    url = "${manga.url}#video_index=$index",
                    title = "Video ${index + 1}",
                    number = (index + 1).toFloat(),
                    uploadDate = 0,
                    volume = 0,
                    branch = null,
                    source = source,
                    scanlator = null,
                )
            }
        } else {
            listOf(
                ContentChapter(
                    id = generateUid(manga.url),
                    url = manga.url,
                    title = "Gallery",
                    number = 1f,
                    uploadDate = 0,
                    volume = 0,
                    branch = null,
                    source = source,
                    scanlator = null,
                ),
            )
        }

        return manga.copy(
            description = desc ?: manga.description,
            tags = tags,
            largeCoverUrl = cover,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url
        val isVideoChapter = chapterUrl.contains("#video_index=")
        val doc = webClient.httpGet(chapterUrl.substringBefore("#").toAbsoluteUrl(), getRequestHeaders()).parseHtml()
        val videoLinks = extractVideos(doc)

        if (isVideoChapter) {
            val index = chapterUrl.substringAfter("#video_index=").toIntOrNull() ?: 0
            val src = videoLinks.getOrNull(index) ?: videoLinks.firstOrNull()
            if (src != null) {
                return listOf(
                    ContentPage(
                        id = generateUid(src),
                        url = src,
                        source = source,
                        preview = null,
                    ),
                )
            }
        }

        // 图集：清洗后的正文图片；loadImage/loadBannerDirect 脚本图均为广告或主题资源，不采集
        val images = parseGalleryImages(findContentBody(doc))

        val decryptedImages = coroutineScope {
            images.map { url ->
                async { decryptImage(url) ?: url }
            }.awaitAll()
        }

        return decryptedImages.mapIndexed { index, src ->
            ContentPage(
                id = generateUid(src),
                url = src,
                source = source,
                preview = null,
            )
        }
    }

    private fun findContentBody(doc: Document): Element =
        doc.selectFirst("#post .post-content[itemprop=articleBody]") ?: doc.selectFirst(".post-content") ?: doc

    /** 克隆正文并移除噪音块（不能在原文档上 remove，会破坏后续解析） */
    private fun cleanBody(body: Element): Element = body.clone().apply {
        select(noiseSelector).remove()
    }

    /** 图集图片：清洗后的正文 img，按懒加载属性取真实地址，过滤主题/插件资源路径 */
    internal fun parseGalleryImages(body: Element): List<String> = cleanBody(body).select("img").mapNotNull { img ->
        img.contentImageSrc()?.takeIf { src ->
            src.startsWith("data:") || assetPathPrefixes.none { src.contains(it) }
        }
    }.distinct()

    /**
     * 视频提取：
     * 1. div.dplayer[data-config] 的 video.url —— 站点唯一的正片来源。
     *    同一 JSON 中的 video_h265（H.265 编码版，多数播放器不可播）与
     *    video_player_ads（贴片广告）不能作为章节，否则出现不可播放的多余视频。
     * 2. 兜底：#post 内的原生 video/iframe（当前模板未使用，防御性保留）。
     */
    private fun extractVideos(doc: Document): List<String> {
        val videos = LinkedHashSet<String>()
        val scope = doc.selectFirst("#post") ?: doc

        scope.select(".dplayer").forEach { player ->
            val configJson = player.attr("data-config")
            if (configJson.isNotBlank()) {
                runCatching {
                    val url = JSONObject(configJson).optJSONObject("video")?.optString("url")
                    if (!url.isNullOrBlank()) {
                        videos.add(url.replace("\\/", "/"))
                    }
                }
            }
        }
        if (videos.isNotEmpty()) return videos.toList()

        scope.select("video").forEach { video ->
            video.attr("src").takeIf { it.isNotBlank() }?.let { videos.add(it.toAbsoluteUrl()) }
            video.select("source").forEach { source ->
                source.attr("src").takeIf { it.isNotBlank() }?.let { videos.add(it.toAbsoluteUrl()) }
            }
        }
        scope.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains(".mp4") || src.contains(".m3u8")) {
                videos.add(src.toAbsoluteUrl())
            }
        }
        return videos.toList()
    }

    /** 图片懒加载地址：自定义解密属性 > 通用懒加载 > src；data: 直接透传 */
    private fun Element.contentImageSrc(): String? {
        val src = attr("src")
        if (src.startsWith("data:")) return src
        return lazySrcAttrs.firstNotNullOfOrNull { attr(it).takeIf { v -> v.isNotBlank() } }
            ?: src.takeIf { it.isNotBlank() }
    }

    private fun String.sanitizeUrl(): String = toAbsoluteUrl().replace(Regex("(?<!:)//+"), "/")

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("data:") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://$domain$this"
        startsWith("http") -> this
        else -> "https://$domain/$this"
    }

    /**
     * 图片为 AES-128-CBC 加密的字节流，解密后写入临时文件返回 file:// URI
     * （避免大体积 Base64 data URI 触发 TransactionTooLargeException）。
     */
    private suspend fun decryptImage(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (url.startsWith("data:")) return url
        return withContext(Dispatchers.IO) {
            try {
                val bytes = if (!url.startsWith("http") && url.length > 32) {
                    // 站点有时直接内联 Base64 密文
                    try {
                        Base64.getDecoder().decode(url)
                    } catch (e: Exception) {
                        return@withContext url
                    }
                } else {
                    webClient.httpGet(url, getRequestHeaders()).body?.bytes() ?: return@withContext null
                }

                val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
                val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())
                // PKCS5 与 PKCS7 对 AES 是同一填充算法; 用 PKCS5 保证桌面 JVM(测试)与 Android 都可用
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, iv)

                val tempFile = java.io.File.createTempFile("cg51_", ".jpg")
                tempFile.writeBytes(cipher.doFinal(bytes))
                "file://${tempFile.absolutePath}"
            } catch (e: Exception) {
                // 解密失败（未加密的图或网络错误）回退原地址
                url
            }
        }
    }
}
