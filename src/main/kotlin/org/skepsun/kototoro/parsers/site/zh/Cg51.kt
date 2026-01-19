package org.skepsun.kototoro.parsers.site.zh

import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
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
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import java.util.EnumSet

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

@MangaSourceParser(name = "CG51", title = "51吃瓜", locale = "zh", type = ContentType.HENTAI_VIDEO)
internal class Cg51(context: MangaLoaderContext) : PagedMangaParser(
    context = context,
    source = MangaParserSource.CG51,
    pageSize = 20,
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("51cg1.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false, // Selecting multiple categories usually not supported by simple path modification unless using query params, simpler to keep single selection for categories
            isTagsExclusionSupported = false
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
        "51剧场" to "51djc"
    )

    private val violentKeywords = listOf("血腥", "暴力", "虐待", "杀人", "死亡", "尸体", "袭警", "毒贩", "吸毒", "强奸", "冰毒")
    
    private val headers: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://51cg1.com/")
        .add("Accept", "image/jpeg,image/png,image/webp,*/*;q=0.8")
        .build()

    override fun getRequestHeaders(): Headers = headers

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = categories.map { (name, id) ->
            MangaTag(
                key = "category:$id",
                title = name,
                source = source
            )
        }.toSet()
        
        return MangaListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(MangaTagGroup("分类", tags))
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Handle category selection
        val selectedCategory = filter.tags.firstOrNull { it.key.startsWith("category:") }?.key?.removePrefix("category:")
        
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

        val doc = webClient.httpGet(url, headers).parseHtml()
        // Selectors based on common WordPress/CMS themes often used by these sites
        val nodes = doc.select("article, .post, .item, .list-item")
        
        return coroutineScope {
            nodes.map { node ->
                async {
                    val link = node.selectFirst("a[href]") ?: return@async null
                    val href = link.attr("href")
                    // Ensure we have an absolute URL
                    val absoluteUrl = href.toAbsoluteUrl()
                    
                    // Fix Title: remove label tags like "热搜 HOT"
                    val titleNode = node.selectFirst("h2, h3, .title, .post-title")
                    titleNode?.select(".wrap, .wraps")?.remove()
                    
                    val title = link.attr("title").ifEmpty { 
                        titleNode?.text()?.trim() ?: link.text().trim()
                    }
                    if (title.isEmpty()) return@async null
                    
                    // Filter out violent/bloody content
                    if (violentKeywords.any { title.contains(it, ignoreCase = true) }) {
                        return@async null
                    }
                    
                    // Extract cover from script (loadBannerDirect) or fallback to img
                    var cover = Regex("""loadBannerDirect\s*\(\s*'([^']+)'""").find(node.outerHtml())?.groupValues?.get(1)
                        ?.toAbsoluteUrl()?.replace(Regex("(?<!:)//+"), "/")
                        ?: node.selectFirst("img")?.let { 
                            val srcAttr = it.attr("src")
                            if (srcAttr.startsWith("data:")) return@let srcAttr
                            
                            val src = it.attr("data-src").ifEmpty { it.attr("data-original") }.ifEmpty { srcAttr }
                            src.toAbsoluteUrl()
                        }
                    
                    // Decrypt cover if valid URL
                    if (!cover.isNullOrEmpty() && !cover.startsWith("data:")) {
                         cover = decryptImage(cover) ?: cover
                    }

                    Manga(
                        id = generateUid(absoluteUrl),
                        title = title,
                        url = absoluteUrl, // Use absolute URL here to pass checking later
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
                        chapters = null
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // Double check URL validity before requesting
        val url = manga.url.toAbsoluteUrl()
        
        val doc = webClient.httpGet(url, headers).parseHtml()
        val content = doc.selectFirst(".entry-content, .post-content, article, .content")
        val desc = content?.text()?.take(500)
        
        // Extract high-res cover from details page
        val scriptImgRegex = Regex("""(loadBannerDirect|loadImage)\s*\(\s*["']([^"']+)["']""")
        val scriptCover = content?.let { 
             scriptImgRegex.find(it.outerHtml())?.groupValues?.get(2)
        }?.toAbsoluteUrl()?.replace(Regex("(?<!:)//+"), "/")

        // Priority 1: List cover (manga.coverUrl)
        // Priority 2: Script cover
        // Priority 3: OG Meta
        // Priority 4: First image
        
        var cover = manga.coverUrl?.takeIf { it.isNotBlank() }
            ?: scriptCover
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.toAbsoluteUrl()?.replace(Regex("(?<!:)//+"), "/")
            ?: content?.selectFirst("img")?.let { 
                val srcAttr = it.attr("src")
                if (srcAttr.startsWith("data:")) return@let srcAttr
                
                val src = it.attr("data-src").ifEmpty { it.attr("data-original") }.ifEmpty { srcAttr }
                src.toAbsoluteUrl()
            }
            
        // Decrypt details cover
        if (!cover.isNullOrEmpty() && !cover.startsWith("data:")) {
             cover = decryptImage(cover) ?: cover
        }
        
        val tags = doc.select("a[rel=tag], .tags a, .tag-cloud a").mapNotNull { 
            val name = it.text().trim()
            if (name.isNotEmpty()) MangaTag(key = name, title = name, source = source) else null 
        }.toSet()

        // Extract videos to determine chapters
        val videoLinks = extractVideos(doc)
        
        val chapters = if (videoLinks.isNotEmpty()) {
            videoLinks.mapIndexed { index, link ->
                MangaChapter(
                    id = generateUid("${manga.url}#video_index=$index"),
                    url = "${manga.url}#video_index=$index",
                    title = "Video ${index + 1}",
                    number = (index + 1).toFloat(),
                    uploadDate = System.currentTimeMillis(),
                    volume = 0,
                    branch = null,
                    source = source,
                    scanlator = null
                )
            }
        } else {
            // Create a single chapter for images/gallery
            listOf(
                MangaChapter(
                    id = generateUid(manga.url),
                    url = manga.url,
                    title = "Gallery",
                    number = 1f,
                    uploadDate = System.currentTimeMillis(),
                    volume = 0,
                    branch = null,
                    source = source,
                    scanlator = null
                )
            )
        }

        return manga.copy(
            description = desc,
            tags = tags,
            largeCoverUrl = cover,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url
        val isVideoChapter = chapterUrl.contains("#video_index=")
        
        val actualUrl = if (isVideoChapter) chapterUrl.substringBefore("#") else chapterUrl
        val doc = webClient.httpGet(actualUrl.toAbsoluteUrl(), headers).parseHtml()
        
        if (isVideoChapter) {
            val index = chapterUrl.substringAfter("#video_index=").toIntOrNull() ?: 0
            val videoLinks = extractVideos(doc)
            
            if (index in videoLinks.indices) {
                val src = videoLinks[index]
                return listOf(
                    MangaPage(
                        id = generateUid(src),
                        url = src,
                        source = source,
                        preview = null // Preview not easily available here without complex map matching, skipping for chapter mode
                    )
                )
            }
        }
        
        // Return all videos if not a specific video chapter (fallback)
        val videoLinks = extractVideos(doc)
        if (videoLinks.isNotEmpty() && !isVideoChapter) {
             return videoLinks.map { src ->
                MangaPage(
                    id = generateUid(src),
                    url = src,
                    source = source,
                    preview = null
                )
            }
        }
        
        // Fallback to Images if no videos found or requested
        val content = doc.selectFirst(".entry-content, .post-content, article, .content") ?: doc
        
        // 1. Images from img tags
        val imgTags = content.select("img").mapNotNull { img ->
            val srcAttr = img.attr("src")
            if (srcAttr.startsWith("data:")) return@mapNotNull srcAttr
            
            val src = img.attr("data-src").ifEmpty { img.attr("data-original") }.ifEmpty { srcAttr }
            if (src.isBlank()) return@mapNotNull null
            // Filter common assets
            if (src.contains("banner", true) || src.contains("logo", true) 
                || src.contains("avatar", true) || src.contains("icon", true)) return@mapNotNull null
            src.toAbsoluteUrl().replace(Regex("(?<!:)//+"), "/")
        }

        // 2. Images from scripts (loadImage/loadBannerDirect)
        val scriptImgRegex = Regex("""(loadBannerDirect|loadImage)\s*\(\s*["']([^"']+)["']""")
        val scriptImages = scriptImgRegex.findAll(content.outerHtml()).map { match ->
            match.groupValues[2].toAbsoluteUrl().replace(Regex("(?<!:)//+"), "/")
        }.toList()

        val allImages = (imgTags + scriptImages).distinct()
        
        // Decrypt all images in parallel
        val decryptedImages = coroutineScope {
            allImages.map { url -> 
                async { decryptImage(url) ?: url } 
            }.awaitAll()
        }
        
        return decryptedImages.mapIndexed { index, src ->
            MangaPage(
                id = generateUid(src),
                url = src, // This will drastically be longer for Data URIs
                source = source,
                preview = null
            )
        }
    }
    
    private fun extractVideos(doc: org.jsoup.nodes.Document): List<String> {
        val videoLinks = mutableListOf<String>()
        val content = doc.selectFirst(".entry-content, .post-content, article, .content") ?: doc

        // Direct video tags (global search)
        doc.select("video").forEach { video ->
            video.attr("src").takeIf { it.isNotBlank() }?.let { 
                videoLinks.add(it.toAbsoluteUrl())
            }
            video.select("source").forEach { source ->
                source.attr("src").takeIf { it.isNotBlank() }?.let { 
                    videoLinks.add(it.toAbsoluteUrl())
                }
            }
        }
        
        // Iframe extraction (global search)
        doc.select("iframe").forEach { iframe ->
             val src = iframe.attr("src")
             if (src.contains(".mp4") || src.contains(".m3u8")) {
                 videoLinks.add(src.toAbsoluteUrl())
             }
        }

        // Regex for un-embedded links - Search in full HTML
        val html = doc.outerHtml()
        val videoRegex = Regex("""https?:\\?/\\?/[^"'<>\s]+\.(?:mp4|m3u8)""", RegexOption.IGNORE_CASE)
        videoRegex.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            videoLinks.add(cleanUrl)
        }
        
        return videoLinks.distinct()
    }

    private fun String.toAbsoluteUrl(): String {
        return if (startsWith("data:")) {
            this
        } else if (startsWith("//")) {
            "https:$this"
        } else if (startsWith("/")) {
            "https://$domain$this"
        } else if (!startsWith("http")) {
            "https://$domain/$this"
        } else {
            this
        }
    }

    private suspend fun decryptImage(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        // If already data URI, return as is
        if (url.startsWith("data:")) return url
        
        return withContext(Dispatchers.IO) {
            try {
                // If it's not the encrypted domain, return original URL (let ImageLoader handle it)
                if (!url.contains("gbwgclh.cn")) return@withContext url
                
                // Fetch the encrypted binary
                val response = webClient.httpGet(url, headers)
                val bytes = response.body?.bytes() ?: return@withContext null
                
                // Decrypt
                val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
                val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())
                val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, iv)
                
                val decrypted = cipher.doFinal(bytes)
                val base64 = Base64.getEncoder().encodeToString(decrypted)
                "data:image/jpeg;base64,$base64"
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to original URL if decryption fails
                url
            }
        }
    }
}
