package org.skepsun.kototoro.parsers.site.all

import androidx.annotation.VisibleForTesting
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Z-Library - 大型电子书图书馆（出版物为主）
 *
 * 网站: https://z-library.sk/
 * 最后验证: 2026-09（页面结构以 2025 年 Wayback 快照比对；线上有 DiamWall JS 盾）
 *
 * 站点协议（匿名可读、下载需登录）:
 * - 搜索: `/s/{query}?page=N`，可加 `selected_content_types[]=book`、`extensions[]=EPUB`、`languages[]=english`、`order=popular|date|year`
 * - 分类: `/category/{id}/s/?page=N`（id 为站方分类树编号，如 Mathematics=23, Physics=27, Philosophy 在 36 下）
 * - 结果卡片: `<z-bookcard id href download year language extension filesize rating ...>`，服务端渲染，无需登录
 * - 详情: `/book/{id}/...html`，下载按钮 `a.addDownloadedBook[href=/dl/{id}/{hash}]`，未登录时被重定向到 `/login`
 *
 * 认证:
 * - 浏览搜索匿名可用；下载需要 remix_userid + remix_userkey cookie（WebView 登录）
 * - 站方 DiamWall 会向非浏览器客户端下发 307+`__diamwall` cookie 挑战，通过后同样返回 200；
 *   若持续得到 517 或登录重定向则说明会话不可用
 *
 * 限制:
 * - 下载有每日配额；本解析器不处理验证码与账号付费逻辑
 */
@ContentSourceParser("ZLIBRARY", "Z-Library", type = ContentType.NOVEL)
internal class ZLibrary(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.ZLIBRARY, pageSize = 50),
	ContentParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("z-library.sk", "zh.z-library.sk")

	override val authUrl: String
		get() = "https://$domain/"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		.add("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
		.build()

	override suspend fun isAuthorized(): Boolean {
		val cookies = context.cookieJar.getCookies(domain)
		return cookies.any { it.name == "remix_userid" } && cookies.any { it.name == "remix_userkey" }
	}

	override suspend fun getUsername(): String {
		val cookies = context.cookieJar.getCookies(domain)
		val userId = cookies.firstOrNull { it.name == "remix_userid" }?.value
		return if (userId != null) "User #$userId" else "Unknown"
	}

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = buildCategoryTags() + buildContentTypeTags() + buildExtensionTags() + buildLanguageTags(),
		availableContentTypes = EnumSet.of(ContentType.NOVEL),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val response = webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders())
		checkAuth(response)
		return parseBookList(response.parseHtml())
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val categoryTag = filter.tags.firstOrNull { it.key.startsWith("cat:") }
		val languages = filter.tags.mapNotNull { it.key.takeIf { k -> k.startsWith("lang:") }?.substringAfter("lang:") }
		val extensions = filter.tags.mapNotNull { it.key.takeIf { k -> k.startsWith("fmt:") }?.substringAfter("fmt:") }
		val contentTypes = filter.tags.mapNotNull { it.key.takeIf { k -> k.startsWith("type:") }?.substringAfter("type:") }
		return buildString {
			append("https://").append(domain)
			if (categoryTag != null) {
				// 分类浏览，如 /category/23/s/ 为 Mathematics
				append("/category/").append(categoryTag.key.substringAfter("cat:")).append("/s/")
			} else {
				append("/s/")
				val query = filter.query
				if (!query.isNullOrBlank()) {
					append(query.urlEncoded())
				}
			}
			append('?')
			if (languages.isNotEmpty()) {
				for (lang in languages) {
					append("languages%5B%5D=").append(lang.urlEncoded()).append('&')
				}
			}
			if (extensions.isNotEmpty()) {
				for (ext in extensions) {
					append("extensions%5B%5D=").append(ext.urlEncoded()).append('&')
				}
			}
			if (contentTypes.isNotEmpty()) {
				for (type in contentTypes) {
					append("selected_content_types%5B%5D=").append(type.urlEncoded()).append('&')
				}
			} else {
				append("selected_content_types%5B%5D=book&")
			}
			when (order) {
				SortOrder.POPULARITY -> append("order=popular&")
				SortOrder.NEWEST -> append("order=year&")
				SortOrder.UPDATED -> append("order=date&")
				else -> {} // RELEVANCE 即站点默认
			}
			if (page > 1) {
				append("page=").append(page)
			}
		}.trimEnd('&', '?')
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseBookList(doc: Document): List<Content> {
		val items = ArrayList<Content>()
		val seen = LinkedHashSet<String>()
		for (item in doc.select("z-bookcard")) {
			val bookId = item.attrOrNull("id") ?: continue
			if (!seen.add(bookId)) {
				continue
			}
			val bookUrl = item.attrOrNull("href") ?: continue
			val title = item.selectFirst("div[slot=title]")?.textOrNull() ?: continue
			val authors = item.selectFirst("div[slot=author]")?.textOrNull()?.let { parseAuthors(it) }.orEmpty()
			val year = item.attr("year")
			val language = item.attr("language")
			val extension = item.attr("extension")
			val filesize = item.attr("filesize")
			val publisher = item.attr("publisher")
			val rating = parseRating(item.attr("rating"))

			val descParts = ArrayList<String>(6)
			if (publisher.isNotBlank()) descParts.add("Publisher: $publisher")
			if (year.isNotBlank()) descParts.add("Year: $year")
			if (language.isNotBlank()) descParts.add("Language: $language")
			if (extension.isNotBlank()) descParts.add("Format: $extension")
			if (filesize.isNotBlank()) descParts.add("Size: $filesize")
			if (rating != RATING_UNKNOWN) descParts.add("Rating: ${formatRating(rating)}")


			items.add(
				Content(
					id = generateUid(bookId),
					title = title,
					altTitles = emptySet(),
					url = bookUrl,
					publicUrl = bookUrl.toAbsoluteUrl(domain),
					rating = rating,
					contentRating = ContentRating.SAFE,
					coverUrl = parseCoverUrl(item),
					tags = emptySet(),
					state = null,
					authors = authors,
					largeCoverUrl = null,
					description = descParts.joinToString("\n"),
					chapters = null,
					source = source,
				),
			)
		}
		return items
	}

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders())
		checkAuth(response)
		return parseDetails(response.parseHtml(), manga)
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDetails(doc: Document, manga: Content): Content {
		val zcover = doc.selectFirst("z-cover")
		val detailsBox = doc.selectFirst("div.bookDetailsBox")

		val properties = LinkedHashMap<String, String>()
		detailsBox?.select("div.bookProperty")?.forEach { prop ->
			val label = prop.selectFirst("div.property_label")?.text()?.trim()?.removeSuffix(":")
			val value = prop.selectFirst("div.property_value")?.textOrNull()
			if (label != null && value != null) {
				properties[label] = value
			}
		}

		val description = doc.selectFirst("div#bookDescriptionBox")?.textOrNull()

		// 分类以链接路径呈现，如 "Biography & Autobiography - Historical"
		val tags = detailsBox
			?.select("div.property_categories div.property_value a")
			?.mapNotNull { el ->
				el.textOrNull()?.split(" - ")?.mapNotNull { seg -> seg.trim().nullIfEmpty() }
			}
			?.flatten()
			?.distinct()
			?.map { ContentTag(it, "cat:text/${it.urlEncoded()}", source) }
			?.toSet()
			.orEmpty()

		val downloadUrl = doc.selectFirst("a.btn.addDownloadedBook")
			?.takeUnless { it.text().contains("unavailable", ignoreCase = true) }
			?.attrOrNull("href")

		val chapters = downloadUrl?.let {
			listOf(
				ContentChapter(
					id = generateUid("${manga.url}|download"),
					url = it,
					title = "Download",
					number = 1f,
					volume = 0,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			)
		}

		val fullDesc = buildString {
			if (description != null) {
				append(description)
				append("\n\n")
			}
			append("--- 书籍信息 ---\n")
			for ((key, value) in properties) {
				append(key).append(": ").append(value).append('\n')
			}
		}

		return manga.copy(
			title = zcover?.attrOrNull("title") ?: manga.title,
			coverUrl = doc.selectFirst("z-cover img.image")?.attrOrNull("src") ?: manga.coverUrl,
			largeCoverUrl = null,
			description = fullDesc.trimEnd('\n'),
			authors = zcover?.attrOrNull("author")?.let { parseAuthors(it) } ?: manga.authors,
			tags = tags,
			chapters = chapters ?: manga.chapters,
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		// 本地已下载的 EPUB 章节由宿主（epub:// 协议）处理，不走网络阅读器
		if (chapter.url.startsWith("epub://") || (chapter.url.startsWith("file://") && chapter.url.contains("#chapter/"))) {
			return emptyList()
		}
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val downloadUrl = chapter.url.toAbsoluteUrl(domain)
		return listOf(
			ContentPage(
				id = generateUid(downloadUrl),
				url = downloadUrl,
				preview = PREVIEW_FILE_FORMAT,
				source = source,
			),
		)
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun checkAuth(response: Response) {
		if (response.code == HTTP_ANTIBOT) {
			throw ParseException(
				"Z-Library 反爬墙 (HTTP $HTTP_ANTIBOT)，请在应用内重新打开站点并登录",
				response.request.url.toString(),
			)
		}
		if (response.request.url.encodedPath.startsWith("/login") || response.request.url.queryParameter("redirectUrl") != null) {
			throw AuthRequiredException(source)
		}
	}

	private fun parseCoverUrl(item: org.jsoup.nodes.Element): String? {
		val img = item.selectFirst("img") ?: return null
		val url = img.attrOrNull("data-src") ?: img.attrOrNull("src") ?: return null
		return if (url.contains("cover-not-exists")) null else url
	}

	/**
	 * 卡片上的 rating 是 0..5 的纯数字（如 "4.2"），详情页可能出现 "x/y" 形式；统一折算为 0..1
	 */
	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseRating(raw: String?): Float {
		if (raw.isNullOrBlank()) {
			return RATING_UNKNOWN
		}
		val parts = raw.split('/')
		val value = parts[0].trim().toFloatOrNull() ?: return RATING_UNKNOWN
		val max = if (parts.size > 1) parts[1].trim().toFloatOrNull() ?: return RATING_UNKNOWN else MAX_RATING
		if (value <= 0f || max <= 0f || !value.isFinite() || !max.isFinite()) {
			return RATING_UNKNOWN
		}
		val normalized = value / max
		return if (normalized.isFinite() && normalized > 0f) normalized else RATING_UNKNOWN
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun formatRating(rating: Float): String = String.format("%.1f/5", rating * MAX_RATING)

	/**
	 * 作者串可能混入书店推广、评论数、邮箱、分类路径等噪音（分号/逗号/& 分隔），先清洗再拆分
	 */
	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseAuthors(raw: String): Set<String> {
		var cleaned = raw
		for (pattern in NON_AUTHOR_PATTERNS) {
			cleaned = pattern.replace(cleaned, "")
		}
		return cleaned.split(AUTHOR_SEPARATOR)
			.map { it.trim() }
			.filter { it.length >= 2 && !NON_AUTHOR_WORDS.any { w -> it.contains(w, ignoreCase = true) } && !it.contains(" - ") && !it.all { c -> c.isDigit() || c.isWhitespace() } }
			.toSet()
	}

	private fun buildCategoryTags(): Set<ContentTag> {
		val tags = LinkedHashSet<ContentTag>()
		// 顶层分类取自站方 /categories 页（2025 快照），此处收录适合"出版物"的主要类目
		for ((id, name) in CATEGORIES) {
			tags.add(ContentTag(name, "cat:$id", source))
		}
		return tags
	}

	private fun buildContentTypeTags(): Set<ContentTag> = setOf(
		ContentTag("Books", "type:book", source),
		ContentTag("Comics", "type:comics", source),
		ContentTag("Articles", "type:article", source),
	)

	private fun buildExtensionTags(): Set<ContentTag> = setOf(
		ContentTag("EPUB", "fmt:EPUB", source),
		ContentTag("PDF", "fmt:PDF", source),
		ContentTag("MOBI", "fmt:MOBI", source),
		ContentTag("AZW3", "fmt:AZW3", source),
		ContentTag("DJVU", "fmt:DJVU", source),
	)

	private fun buildLanguageTags(): Set<ContentTag> = setOf(
		ContentTag("English", "lang:english", source),
		ContentTag("中文", "lang:chinese", source),
		ContentTag("日本語", "lang:japanese", source),
		ContentTag("한국어", "lang:korean", source),
		ContentTag("Русский", "lang:russian", source),
		ContentTag("Español", "lang:spanish", source),
		ContentTag("Français", "lang:french", source),
		ContentTag("Deutsch", "lang:german", source),
		ContentTag("Italiano", "lang:italian", source),
		ContentTag("Português", "lang:portuguese", source),
		ContentTag("العربية", "lang:arabic", source),
		ContentTag("Türkçe", "lang:turkish", source),
		ContentTag("Polski", "lang:polish", source),
		ContentTag("Nederlands", "lang:dutch", source),
		ContentTag("Tiếng Việt", "lang:vietnamese", source),
	)

	private companion object {
		const val HTTP_ANTIBOT = 517
		const val MAX_RATING = 5f
		const val PREVIEW_FILE_FORMAT = "EPUB"
		val AUTHOR_SEPARATOR = Regex("[;&,]、")
		val NON_AUTHOR_PATTERNS = listOf(
			Regex("Barnes\\s*&\\s*Noble", RegexOption.IGNORE_CASE),
			Regex("Bookshop\\.org", RegexOption.IGNORE_CASE),
			Regex("\\d+\\s*comments?", RegexOption.IGNORE_CASE),
			Regex("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}", RegexOption.IGNORE_CASE),
		)
		val NON_AUTHOR_WORDS = listOf("amazon", "barnes", "noble", "bookshop")

		// z-library /categories 顶层分类 id -> 名称（2025 快照核对）
		val CATEGORIES = listOf(
			1 to "Arts",
			3 to "Biography & Autobiography",
			5 to "Business & Economics",
			6 to "Chemistry",
			8 to "Comics & Graphic Novels",
			10 to "Computers",
			11 to "Crime, Thrillers & Mystery",
			13 to "Engineering",
			14 to "Fiction",
			17 to "History",
			21 to "Languages",
			22 to "Linguistics",
			23 to "Mathematics",
			24 to "Medicine",
			27 to "Physics",
			28 to "Poetry",
			29 to "Psychology",
			30 to "Reference",
			31 to "Religion & Spirituality",
			32 to "Romance",
			33 to "Science (General)",
			34 to "Science Fiction",
			36 to "Society, Politics & Philosophy",
			39 to "Travel",
			609 to "Ancient & Medieval Philosophy",
			610 to "Asian Philosophy",
			613 to "European & American Philosophy",
			621 to "Renaissance & Modern Philosophy",
		)
	}
}
