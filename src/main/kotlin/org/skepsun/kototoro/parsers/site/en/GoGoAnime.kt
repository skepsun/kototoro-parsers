package org.skepsun.kototoro.parsers.site.en

import org.json.JSONArray
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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.Base64
import java.util.EnumSet
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.Headers

@ContentSourceParser("GOGOANIME", "GoGoAnime", "en", type = ContentType.VIDEO)
internal class GoGoAnime(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.GOGOANIME, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("gogoanime.by")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true, isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
        availableTags = buildFilterTags(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseList(doc)
    }

    override suspend fun getDetails(manga: Content): Content {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select("a[href*=/genre/], a[href*=/genres/]").mapNotNull { a ->
            val text = a.text().trim()
            val key = a.attr("href").substringAfter("/genre/").substringAfter("/genres/").trimEnd('/')
            if (text.isNotBlank() && key.isNotBlank()) ContentTag(text, key, source) else null
        }.toSet()

        val selfUrl = manga.url.trimEnd('/')
        val chapterLinks = doc.select("a[href*=-episode-], a[href*=/episode/], a[href*=/watch/]").filter { el ->
            val href = el.attr("href").trimEnd('/')
            el.text().trim().isNotBlank() &&
                href != selfUrl &&
                href.count { it == '/' } >= 2 &&
                !href.contains("/genre/") && !href.contains("/tag/") && !href.contains("/category/")
        }
        val chapters = chapterLinks.mapIndexed { index, link ->
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexed null
            val chTitle = link.ownText().trim().ifBlank { "Episode ${index + 1}" }
            val absoluteUrl = href.toAbsoluteUrl(domain)
            ContentChapter(
                id = generateUid(absoluteUrl),
                title = chTitle,
                number = (index + 1).toFloat(),
                volume = 0,
                url = absoluteUrl.removePrefix("https://$domain"),
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.filterNotNull()

        return manga.copy(
            title = title, description = description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
            contentRating = ContentRating.SAFE,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = chapters.ifEmpty { manga.chapters },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()

        val gogoIframe = doc.selectFirst("div.play-video iframe[src]")
            ?: doc.selectFirst("iframe[src*='gogoplay']")
            ?: doc.selectFirst("iframe[src*='streaming.php']")
        if (gogoIframe != null) {
            val iframeSrc = gogoIframe.attr("src").takeIf { it.isNotBlank() } ?: return emptyList()
            val pages = tryExtractFromGogoPlay(iframeSrc, chapterUrl, doc)
            if (pages.isNotEmpty()) return pages
        }

        context.requestBrowserAction(this, chapterUrl)
        return emptyList()
    }

    private suspend fun tryExtractFromGogoPlay(
        iframeSrc: String,
        chapterUrl: String,
        doc: Document,
    ): List<ContentPage> {
        val id = Regex("id=([^&]+)").find(iframeSrc)?.groupValues?.get(1) ?: return emptyList()
        val host = Regex("//([^/]+)").find(iframeSrc)?.groupValues?.get(1) ?: return emptyList()

        val encryptedId = cryptoHandler(id, IV, SECRET_KEY, encrypt = true)

        val scriptData = doc.select("script[data-name='episode']").attr("data-value")
        if (scriptData.isBlank()) return emptyList()

        val headersDecrypted = cryptoHandler(scriptData, IV, SECRET_KEY, encrypt = false)
        val query = "id=$encryptedId&alias=$id&${headersDecrypted.substringAfter("&")}"

        val ajaxHeaders = Headers.Builder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "https://$host/")
            .add("User-Agent", context.getDefaultUserAgent())
            .build()

        val ajaxResponse = try {
            webClient.httpGet("https://$host/encrypt-ajax.php?$query", ajaxHeaders)
        } catch (_: Exception) {
            return emptyList()
        }

        val json = try {
            JSONObject(ajaxResponse.body.string())
        } catch (_: Exception) {
            return emptyList()
        }

        val dataEncrypted = json.optString("data", "")
        if (dataEncrypted.isBlank()) return emptyList()

        val decryptedData = cryptoHandler(dataEncrypted, IV, SECRET_DECRYPT_KEY, encrypt = false)
        val videoJson = try {
            JSONObject(decryptedData)
        } catch (_: Exception) {
            return emptyList()
        }

        val sources = mutableListOf<ContentPage>()
        val sourceArr = videoJson.optJSONArray("source") ?: return emptyList()
        for (i in 0 until sourceArr.length()) {
            val srcObj = sourceArr.optJSONObject(i) ?: continue
            val file = srcObj.optString("file", "")
            if (file.isNotBlank()) {
                sources.add(ContentPage(
                    id = generateUid("${chapterUrl}|$i"),
                    url = file,
                    preview = null,
                    source = source,
                ))
            }
        }

        val sourceBkArr = videoJson.optJSONArray("sourceBk")
        if (sourceBkArr != null) {
            for (i in 0 until sourceBkArr.length()) {
                val srcObj = sourceBkArr.optJSONObject(i) ?: continue
                val file = srcObj.optString("file", "")
                if (file.isNotBlank()) {
                    sources.add(ContentPage(
                        id = generateUid("${chapterUrl}|bk$i"),
                        url = file,
                        preview = null,
                        source = source,
                    ))
                }
            }
        }

        return sources
    }

    companion object {
        private const val IV = "3134003223491201"
        private const val SECRET_KEY = "37911490979715163134003223491201"
        private const val SECRET_DECRYPT_KEY = "54674138327930866480207815084989"

        private fun cryptoHandler(string: String, iv: String, secretKeyString: String, encrypt: Boolean): String {
            val ivParameterSpec = IvParameterSpec(iv.toByteArray())
            val secretKey = SecretKeySpec(secretKeyString.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            return if (!encrypt) {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
                String(cipher.doFinal(Base64.getDecoder().decode(string)))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec)
                Base64.getEncoder().encodeToString(cipher.doFinal(string.toByteArray()))
            }
        }
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent())
        .build()

    private fun buildFilterTags(): Set<ContentTag> {
        val tags = LinkedHashSet<ContentTag>()
        tags += ContentTag("Action", "action", source)
        tags += ContentTag("Adventure", "adventure", source)
        tags += ContentTag("Comedy", "comedy", source)
        tags += ContentTag("Drama", "drama", source)
        tags += ContentTag("Fantasy", "fantasy", source)
        tags += ContentTag("Horror", "horror", source)
        tags += ContentTag("Mecha", "mecha", source)
        tags += ContentTag("Music", "music", source)
        tags += ContentTag("Mystery", "mystery", source)
        tags += ContentTag("Romance", "romance", source)
        tags += ContentTag("Sci-Fi", "sci-fi", source)
        tags += ContentTag("Slice of Life", "slice-of-life", source)
        tags += ContentTag("Sports", "sports", source)
        tags += ContentTag("Supernatural", "supernatural", source)
        tags += ContentTag("Thriller", "thriller", source)
        tags += ContentTag("Shounen", "shounen", source)
        tags += ContentTag("Seinen", "seinen", source)
        tags += ContentTag("Shoujo", "shoujo", source)
        tags += ContentTag("Josei", "josei", source)
        tags += ContentTag("Ecchi", "ecchi", source)
        tags += ContentTag("Harem", "harem", source)
        tags += ContentTag("Isekai", "isekai", source)
        tags += ContentTag("Magic", "magic", source)
        tags += ContentTag("Martial Arts", "martial-arts", source)
        tags += ContentTag("Military", "military", source)
        tags += ContentTag("School", "school", source)
        tags += ContentTag("Super Power", "super-power", source)
        tags += ContentTag("Vampire", "vampire", source)
        tags += ContentTag("Game", "game", source)
        tags += ContentTag("Historical", "historical", source)
        tags += ContentTag("Kids", "kids", source)
        tags += ContentTag("Parody", "parody", source)
        tags += ContentTag("Samurai", "samurai", source)
        tags += ContentTag("Psychological", "psychological", source)
        tags += ContentTag("Demons", "demons", source)
        tags += ContentTag("Space", "space", source)
        tags += ContentTag("Cars", "cars", source)
        tags += ContentTag("Dementia", "dementia", source)
        tags += ContentTag("Police", "police", source)
        tags += ContentTag("Mahou Shoujo", "mahou-shoujo", source)
        tags += ContentTag("Shoujo Ai", "shoujo-ai", source)
        tags += ContentTag("Shounen Ai", "shounen-ai", source)
        return tags
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        val q = filter.query?.urlEncoded() ?: ""
        if (q.isNotEmpty()) {
            val tagParam = filter.tags.joinToString(",") { it.key }
            val genreParam = if (tagParam.isNotEmpty()) "&genre=$tagParam" else ""
            return "https://$domain/?s=$q&page=$page$genreParam"
        }
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "popular"
            else -> "latest"
        }
        val tagParam = filter.tags.joinToString(",") { it.key }
        val genreParam = if (tagParam.isNotEmpty()) "&genre=$tagParam" else ""
        return "https://$domain/?sort=$sortParam&page=$page$genreParam"
    }

    private fun parseList(doc: Document): List<Content> {
        val items = ArrayList<Content>()
        val seen = LinkedHashSet<String>()
        var cards: List<Element> = doc.select("a.film-poster-ahref[href], a.dynamic-name[href], a.item[href], .card a[href], .video-item a[href], .video-card a[href], article a[href], .post a[href]")
        if (cards.isEmpty()) {
            cards = doc.select("a[href*=/anime/]").filter { a ->
                val h = a.attr("href")
                val hasContent = a.selectFirst("img") != null || a.selectFirst("h3,h2,h4,.title,.name") != null
                val notNav = !h.contains("genre") && !h.contains("category") && !h.contains("tag") &&
                    !h.contains("login") && !h.contains("signup") && !h.contains("random") &&
                    !h.contains("cdn") && !h.contains("static") && !h.contains("assets") &&
                    !h.contains("javascript") && !h.contains("facebook") && !h.contains("twitter") &&
                    h.startsWith("/") && h.count { it == '/' } >= 2 && h.length > 5
                hasContent || notNav
            }
        }
        for (link in cards) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val absoluteUrl = href.toAbsoluteUrl(domain).substringBefore("?")
            if (!seen.add(absoluteUrl)) continue
            val title = link.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: link.selectFirst("h3,h2,h4,.title,.name")?.text()?.trim()
                ?: link.text().trim().ifEmpty { continue }
            val thumb = link.selectFirst("img[src]")?.attr("src")?.toAbsoluteUrlOrNull(domain)
            items.add(Content(
                id = generateUid(absoluteUrl),
                url = absoluteUrl.removePrefix("https://$domain"),
                publicUrl = absoluteUrl, title = title, altTitles = emptySet(),
                coverUrl = thumb, largeCoverUrl = thumb,
                authors = emptySet(), tags = emptySet(), state = null, description = null,
                contentRating = ContentRating.SAFE, source = source, rating = RATING_UNKNOWN,
            ))
        }
        return items
    }
}
