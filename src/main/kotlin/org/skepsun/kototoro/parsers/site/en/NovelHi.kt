package org.skepsun.kototoro.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.safety.Safelist
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toAbsoluteUrlOrNull
import java.util.EnumSet
import java.util.Locale
import okhttp3.Headers

@ContentSourceParser("NOVELHI", "NovelHi", "en", type = ContentType.NOVEL)
internal class NovelHi(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.NOVELHI, pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("novelhi.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.NOVEL),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = "https://$domain/book/searchBookListWithShelfState".toHttpUrl().newBuilder()
            .addQueryParameter("curr", page.toString())
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("keyword", filter.query.orEmpty())
            .build()
        val response = webClient.httpGet(url.toString(), getRequestHeaders()).parseJson()
        val list = response.optJSONObject("data")?.optJSONArray("list")
            ?: throw ParseException("NovelHi: missing novel list", url.toString())
        return (0 until list.length()).map { index ->
            val book = list.getJSONObject(index)
            val genres = book.optJSONArray("genres")
            val primaryGenre = genres?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                ?.firstOrNull { it.optString("genreId") == book.optString("primaryGenreId") }
            val genre = primaryGenre?.optString("genreName")?.toSlug().orEmpty().ifEmpty { "other" }
            val slug = book.optString("novelSlug").ifEmpty { book.getString("bookName").toSlug() }
            val path = if (book.optString("primaryGenreId").isEmpty() && book.optString("novelSlug").isEmpty()) {
                "/book/${book.getString("id")}.html"
            } else {
                "/novel/$genre/$slug"
            }
            val cover = book.optString("picUrl").toAbsoluteUrlOrNull(domain)
            Content(
                id = generateUid(path), url = path, publicUrl = path.toAbsoluteUrl(domain),
                title = book.getString("bookName"), altTitles = emptySet(),
                coverUrl = cover, largeCoverUrl = cover,
                authors = book.optString("authorName").takeIf { it.isNotBlank() }?.let { setOf(it) }.orEmpty(),
                tags = emptySet(), state = null, description = book.optString("bookDesc"),
                contentRating = ContentRating.SAFE, source = source, rating = RATING_UNKNOWN,
            )
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val detailUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(detailUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: manga.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrlOrNull(domain)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val directoryUrl = doc.selectFirst("a[href$=/chapters]")?.absUrl("href")
            ?: throw ParseException("NovelHi: missing chapter directory", detailUrl)
        val directory = webClient.httpGet(directoryUrl, getRequestHeaders()).parseHtml()
        val chapters = directory.select(".dirList a[href]").mapIndexed { index, el ->
            val chUrl = el.absUrl("href").toHttpUrl().encodedPath
            ContentChapter(
                id = generateUid(chUrl), url = chUrl,
                title = el.text().trim().ifEmpty { "Chapter ${index + 1}" },
                number = index + 1f, uploadDate = 0L, volume = 0,
                branch = null, scanlator = null, source = source,
            )
        }.toList()
        if (chapters.isEmpty()) throw ParseException("NovelHi: empty chapter directory", directoryUrl)
        return manga.copy(
            title = title, description = description ?: manga.description,
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover,
            contentRating = ContentRating.SAFE,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()
        val contentEl = doc.selectFirst("#showReading")
            ?: throw ParseException("NovelHi: missing chapter content", chapterUrl)
        val title = chapter.title ?: doc.selectFirst("h1, h2")?.text()?.trim() ?: ""
        var contentHtml = contentEl.html()
        var fontCss = ""
        var fontClass = ""
        if (contentEl.selectFirst(".chapter-content-loading") != null) {
            // Use the same anonymous content request as the site's chapter loader.
            val path = doc.selectFirst("#chapterContentPath")?.attr("value")
                ?: throw ParseException("NovelHi: missing content endpoint", chapterUrl)
            val token = doc.selectFirst("#chapterContentToken")?.attr("value")
                ?: throw ParseException("NovelHi: missing content request token", chapterUrl)
            val url = path.toAbsoluteUrl(domain).toHttpUrl().newBuilder()
                .addQueryParameter("token", token).build()
            val headers = getRequestHeaders().newBuilder()
                .set("Referer", chapterUrl).set("X-Requested-With", "XMLHttpRequest").build()
            val response = webClient.httpGet(url.toString(), headers).parseJson()
            if (response.optInt("code") != 200) {
                throw ParseException("NovelHi: chapter content unavailable", chapterUrl)
            }
            when (val data = response.opt("data")) {
                is String -> contentHtml = data
                is JSONObject -> {
                    contentHtml = data.optString("content")
                    if (data.optBoolean("fontObfuscation")) {
                        fontCss = data.optString("fontFaceCss")
                        fontClass = data.optString("fontClass")
                    }
                }
                else -> throw ParseException("NovelHi: missing chapter content response", chapterUrl)
            }
        }
        val body = Jsoup.parseBodyFragment(contentHtml)
        body.select("script, style, iframe, ins, .adsbygoogle, #unlock-div, #share-unlock-div").remove()
        if (body.body().text().isBlank()) throw ParseException("NovelHi: empty chapter content", chapterUrl)
        val htmlDoc = Jsoup.parse("<html><head><meta charset=utf-8></head><body></body></html>")
        htmlDoc.head().appendElement("base").attr("href", chapterUrl)
        if (fontCss.isNotBlank()) htmlDoc.head().appendElement("style").appendChild(DataNode(fontCss))
        htmlDoc.body().appendElement("h2").text(title)
        htmlDoc.body().appendElement("div").attr("class", fontClass)
            .html(Jsoup.clean(body.body().html(), Safelist.basic()))
        val html = htmlDoc.outerHtml()
        val encoded = context.encodeBase64(html.toByteArray(Charsets.UTF_8))
        return listOf(ContentPage(
            id = generateUid(chapter.id),
            url = "data:text/html;charset=utf-8;base64,$encoded",
            preview = null, source = source,
        ))
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", context.getDefaultUserAgent()).build()

    private fun String.toSlug(): String = trim().lowercase(Locale.ROOT)
        .replace(Regex("['’]"), "")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
