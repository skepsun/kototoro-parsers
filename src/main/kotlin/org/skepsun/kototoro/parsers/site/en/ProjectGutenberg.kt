package org.skepsun.kototoro.parsers.site.en

import androidx.annotation.VisibleForTesting
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Project Gutenberg - 公版书数字图书馆（以文学经典为主，含历史/哲学/传记）
 *
 * 最后验证: 2026-09（经本机 7890 代理实机验证）
 *
 * 站点协议（站点明示 HTML 页面不用于抓取，统一走官方 OPDS 接口）:
 * - 搜索: `/ebooks/search.opds/?query={q}&sort_order={downloads|release_date|title}&start_index=N`
 * - 浏览: 同一路径省略 query，用 `sort_order={downloads|release_date|random}`
 *   （start 目录里的 `/ebooks.search.opds/` 点号变体会返回 403，勿用）
 * - 详情: `/ebooks/{id}.opds`，含封面 (rel=opds image)、主题 (category term)、
 *   语言 (dcterms:language) 与获取链接 (rel=http://opds-spec.org/acquisition)
 * - 搜索结果的 feed 会混入 "Authors"/"Subjects" 导航条目，按 id 形状 `/ebooks/{数字}.opds` 过滤
 * - 封面 URL 规律固定: `/cache/epub/{id}/pg{id}.cover.small.jpg`
 *
 * 下载: EPUB 直链会 302 到镜像，交给 HTTP 客户端跟随
 */
@ContentSourceParser("GUTENBERG", "Project Gutenberg", type = ContentType.NOVEL)
internal class ProjectGutenberg(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.GUTENBERG, pageSize = 25) {

	override val configKeyDomain = ConfigKey.Domain("www.gutenberg.org", "gutenberg.org")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableContentTypes = EnumSet.of(ContentType.NOVEL),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val response = webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders())
		return parseList(response.parseOpds())
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
		val sortOrder = when (order) {
			SortOrder.NEWEST -> "release_date"
			SortOrder.ALPHABETICAL -> "title"
			SortOrder.POPULARITY, SortOrder.RELEVANCE -> "downloads"
			else -> "downloads"
		}
		val url = buildString {
			append("https://").append(domain).append("/ebooks/search.opds/?")
			if (query != null) {
				append("query=").append(query.urlEncoded()).append('&')
			}
			// 相关度搜索保持引擎默认排序；浏览与其他排序显式传 sort_order
			if (query == null || order != SortOrder.RELEVANCE) {
				append("sort_order=").append(sortOrder).append('&')
			}
			if (page > 1) {
				append("start_index=").append((page - 1) * pageSize + 1).append('&')
			}
		}
		return url.trimEnd('&')
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseList(doc: Document): List<Content> {
		val items = ArrayList<Content>()
		val seen = LinkedHashSet<String>()
		for (entry in doc.select("entry")) {
			val idText = entry.selectFirst("id")?.textOrNull() ?: continue
			val bookId = EBOOK_ID_REGEX.find(idText)?.groupValues[1] ?: continue
			if (!seen.add(bookId)) {
				continue
			}
			val title = entry.selectFirst("title")?.textOrNull() ?: continue
			val author = entry.select("author > name").firstOrNull()?.textOrNull()
				?: entry.selectFirst("content")?.textOrNull()

			items.add(
				Content(
					id = generateUid(bookId),
					title = title,
					altTitles = emptySet(),
					url = "ebooks/$bookId.opds",
					publicUrl = "https://www.gutenberg.org/ebooks/$bookId",
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = coverUrl(bookId),
					tags = emptySet(),
					state = null,
					authors = author?.let { setOf(it) }.orEmpty(),
					largeCoverUrl = coverUrl(bookId, medium = true),
					description = null,
					chapters = null,
					source = source,
				),
			)
		}
		return items
	}

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders())
		return parseDetails(response.parseOpds(), manga)
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDetails(doc: Document, manga: Content): Content {
		val entry = doc.selectFirst("entry")
			?: throw ParseException("OPDS 详情缺少 entry", "https://$domain/${manga.url}")

		val title = entry.selectFirst("title")?.textOrNull() ?: manga.title
		val authors = entry.select("author > name").mapNotNull { it.textOrNull() }.toSet()
		val description = entry.selectFirst("content")?.textOrNull()
		val links = collectLinks(entry)
		val coverUrl = links.firstOrNull { it.rel.endsWith("/image") }?.href
		val language = (entry.getElementsByTag("dcterms:language").ifEmpty { entry.getElementsByTag("language") })
			.firstOrNull()?.textOrNull()

		val tags = entry.select("category")
			.mapNotNull { it.attrOrNull("term") }
			.map { ContentTag(it, "subject:${it.urlEncoded()}", source) }
			.toSet()

		val download = pickAcquisitionUrl(links)
		val chapters = download?.let { url ->
			listOf(
				ContentChapter(
					id = generateUid("${manga.url}|download"),
					title = "Download EPUB",
					number = 1f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			)
		}

		val fullDesc = buildString {
			if (!description.isNullOrBlank()) {
				append(description)
			}
			if (!language.isNullOrBlank()) {
				if(isNotEmpty()) append('\n')
				append("Language: ").append(language)
			}
		}.ifBlank { manga.description }

		return manga.copy(
			title = title,
			coverUrl = coverUrl ?: manga.coverUrl,
			largeCoverUrl = coverUrl ?: manga.largeCoverUrl,
			description = fullDesc,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			chapters = chapters ?: manga.chapters,
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		return listOf(
			ContentPage(
				id = generateUid(chapter.url),
				url = chapter.url,
				preview = null,
				source = source,
			),
		)
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun Response.parseOpds(): Document = use {
		Jsoup.parse(it.body.string(), it.request.url.toString(), Parser.xmlParser())
	}

	private data class OpdsLink(val rel: String, val href: String, val title: String, val type: String)

	private fun collectLinks(entry: org.jsoup.nodes.Element): List<OpdsLink> =
		entry.select("link").map {
			OpdsLink(
				rel = it.attr("rel"),
				href = it.attr("href"),
				title = it.attr("title"),
				type = it.attr("type"),
			)
		}

	private fun pickAcquisitionUrl(links: List<OpdsLink>): String? {
		val ebooks = links.filter { ACQUISITION_REL in it.rel && it.type == EPUB_MIME }
		return ebooks.firstOrNull { it.title.contains("EPUB 3", ignoreCase = true) }?.href
			?: ebooks.firstOrNull { it.title.contains("images", ignoreCase = true) }?.href
			?: ebooks.firstOrNull()?.href
	}

	private fun coverUrl(bookId: String, medium: Boolean = false): String =
		"https://www.gutenberg.org/cache/epub/$bookId/pg$bookId.cover.${if (medium) "medium" else "small"}.jpg"

	private companion object {
		val EBOOK_ID_REGEX = Regex("/ebooks/(\\d+)\\.opds")
		const val ACQUISITION_REL = "http://opds-spec.org/acquisition"
		const val EPUB_MIME = "application/epub+zip"
	}
}
