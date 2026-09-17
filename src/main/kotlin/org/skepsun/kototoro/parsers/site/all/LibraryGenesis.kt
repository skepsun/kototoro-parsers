package org.skepsun.kototoro.parsers.site.all

import androidx.annotation.VisibleForTesting
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * Library Genesis (libgen.li) - 出版物搜索引擎（小说 + 科技/学术书）
 *
 * 最后验证: 2026-09（经本机 7890 代理实机验证）
 *
 * 站点协议:
 * - 搜索: `/index.php?req={query}&page=N&res=50`，无查询时 `req=fmode:last` 为最新收录
 * - 结果表: `table#tablelibgen`，首行为 `<th>` 表头（列序随库不同会变化，按表头文本定位列），
 *   每行 Mirrors 列含 `ads.php?md5={md5}` 站内详情链接，Size 列含 `file.php?id={id}`
 * - 排序: 表头链接参数 `order=time_added|title|year|...` + `ordermode=asc|desc`
 * - 详情: `/ads.php?md5={md5}`，左侧封面 `a[href*="/covers/"]`，元数据在一个 `<td>` 内以
 *   `Label: value<br>` 行排布；下载链接 `a[href^="get.php"]`（一次性 `key`，需现取现用）
 * - `get.php` 302 跳转到 CDN（cdn*.booksdl.lc），key 在跳转中透传
 *
 * 说明:
 * - 搜索表单带 `topics[]` 分库过滤（站内称 topics：Libgen/Comics/Fiction/Scientific Articles/
 *   Magazines/Fiction RUS/Standards），可多选，映射为本源标签
 * - 站点无官方 API、无登录；不带 topics 时为全库混合结果
 * - 页面有较多广告脚本，只解析需要的静态节点，不请求广告域
 */
@ContentSourceParser("LIBRARYGENESIS", "Library Genesis", type = ContentType.NOVEL)
internal class LibraryGenesis(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.LIBRARYGENESIS, pageSize = 50) {

	override val configKeyDomain = ConfigKey.Domain("libgen.li", "libgen.bz", "libgen.gl")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = TOPICS.map { (letter, name) -> ContentTag(name, "topic:$letter", source) }.toSet(),
		availableContentTypes = EnumSet.of(ContentType.NOVEL),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val response = webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders())
		return parseList(response.parseHtml())
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
		return buildString {
			append("https://").append(domain).append("/index.php?req=")
			if (query != null) {
				append(query.urlEncoded())
			} else {
				append("fmode%3Alast") // 无关键词时浏览最新收录
			}
			append("&res=").append(pageSize)
			for (tag in filter.tags) {
				if (tag.key.startsWith("topic:")) {
					append("&topics%5B%5D=").append(tag.key.substringAfter("topic:"))
				}
			}
			when (order) {
				SortOrder.NEWEST -> append("&order=time_added&ordermode=desc")
				SortOrder.NEWEST_ASC -> append("&order=time_added&ordermode=asc")
				SortOrder.ALPHABETICAL -> append("&order=title&ordermode=asc")
				else -> {} // RELEVANCE: 站点默认（最新收录优先）
			}
			if (page > 1) {
				append("&page=").append(page)
			}
		}
	}

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders())
		return parseDetails(response.parseHtml(), manga)
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseList(doc: Document): List<Content> {
		val table = doc.selectFirst("table#tablelibgen") ?: return emptyList()
		val rows = table.select("tr")
		if (rows.size < 2) {
			return emptyList()
		}
		val columns = parseHeaderColumns(rows[0])
		val items = ArrayList<Content>(rows.size)
		val seen = LinkedHashSet<String>()
		for (row in rows.drop(1)) {
			val cells = row.select("td")
			if (cells.isEmpty()) {
				continue
			}
			val mirrorUrl = row.select("a[href*=ads.php]").firstOrNull()?.attrOrNull("href") ?: continue
			val md5 = mirrorUrl.substringAfter("md5=").substringBefore('&')
			if (!seen.add(md5)) {
				continue
			}
			val titleCell = cells[0]
			val title = titleCell.selectFirst("a")?.textOrNull() ?: titleCell.textOrNull() ?: continue
			val author = columns["Author(s)"]?.takeIf { it < cells.size }?.let { cells[it].textOrNull() }
			val publisher = columns["Publisher"]?.takeIf { it < cells.size }?.let { cells[it].textOrNull() }
			val year = columns["Year"]?.takeIf { it < cells.size }?.let { cells[it].textOrNull() }
			val language = columns["Language"]?.takeIf { it < cells.size }?.let { cells[it].textOrNull() }
			val size = columns["Size"]?.takeIf { it < cells.size }?.let { cells[it].textOrNull() }
			val format = columns["Ext."]?.takeIf { it < cells.size }?.let { cells[it].textOrNull() }

			val descParts = ArrayList<String>(5)
			if (publisher != null) descParts.add("Publisher: $publisher")
			if (year != null) descParts.add("Year: $year")
			if (language != null) descParts.add("Language: $language")
			if (format != null) descParts.add("Format: $format")
			if (size != null) descParts.add("Size: $size")

			val relativeUrl = mirrorUrl.trimStart('/')
			items.add(
				Content(
					id = generateUid(md5),
					title = title,
					altTitles = emptySet(),
					url = relativeUrl,
					publicUrl = relativeUrl.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = null,
					tags = emptySet(),
					state = null,
					authors = author?.let { parseAuthors(it) }.orEmpty(),
					largeCoverUrl = null,
					description = descParts.joinToString("\n"),
					chapters = null,
					source = source,
				),
			)
		}
		return items
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDetails(doc: Document, manga: Content): Content {
		var title = manga.title
		var description = manga.description
		var authors = manga.authors
		val properties = LinkedHashMap<String, String>()

		for (cell in doc.select("td")) {
			val text = cell.html()
				.replace(Regex("(?i)<br\\s*/?>"), "\n")
				.replace(Regex("<[^>]+>"), " ")
				.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
			if (!text.contains("Title:")) {
				continue
			}
			for (line in text.split('\n')) {
				val key = line.substringBefore(':', "").trim()
				if (key !in METADATA_LABELS) {
					continue
				}
				val value = line.substringAfter(':', "").trim()
				if (value.isNotEmpty()) {
					properties[key] = value
				}
			}
			break
		}

		properties["Title"]?.let { title = it }
		properties["Author(s)"]?.let { authors = parseAuthors(it) }
		properties["Abstract"]?.let { description = it }

		val coverUrl = doc.selectFirst("a[href*=covers]")
			?.takeUnless { it.selectFirst("img")?.attr("src")?.contains("blank") == true }
			?.attrOrNull("href")
			?.toAbsoluteUrl(domain)

		// 实际文件格式（pdf/epub/djvu/fb2/...），随章节 URL 的 #ext 片段带给 getPages
		val format = (
			properties["Format"]
				?: properties["Type"]
				?: FORMAT_LINE_FIND.find(manga.description.orEmpty())?.groupValues?.get(1)
			)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 6 }
		val chapters = listOf(
			ContentChapter(
				id = generateUid("${manga.url}|download"),
				title = "Download" + (format?.let { " (${it.uppercase()})" } ?: ""),
				number = 1f,
				volume = 0,
				url = manga.url + (format?.let { "#ext=$it" } ?: ""), // 详情页含一次性下载 key，读取时重新获取
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			),
		)

		val fullDesc = buildString {
			if (!description.isNullOrBlank()) {
				append(description)
				append("\n\n")
			}
			append("--- 书籍信息 ---")
			for ((key, value) in properties) {
				if (key == "Title") continue
				append('\n').append(key).append(": ").append(value)
			}
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			description = fullDesc,
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val ext = chapter.url.substringAfter("#ext=", "").uppercase().takeIf { it.isNotEmpty() }
		val detailUrl = chapter.url.substringBefore('#').toAbsoluteUrl(domain)
		return listOf(
			ContentPage(
				id = generateUid(detailUrl),
				url = detailUrl,
				preview = ext, // 真实文件格式（PDF/EPUB/DJVU/...），宿主据此选择下载/阅读方式
				source = source,
			),
		)
	}

	/**
	 * `get.php` 的 key 是一次性的，读文件前从详情页现取；返回后由 302 跳到 CDN
	 */
	override suspend fun getPageUrl(page: ContentPage): String {
		val response = webClient.httpGet(page.url, getRequestHeaders())
		return parseDownloadUrl(response.parseHtml(), response.request.url.toString())
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDownloadUrl(doc: Document, url: String): String {
		val href = doc.selectFirst("a[href^=get.php]")?.attrOrNull("href")
			?: throw ParseException("未找到下载链接 (get.php)", url)
		return href.toAbsoluteUrl(domain)
	}

	/** 表头文本 -> 列下标；表头带排序链接，取净化后的文本匹配 */
	private fun parseHeaderColumns(headerRow: org.jsoup.nodes.Element): Map<String, Int> {
		val map = HashMap<String, Int>()
		headerRow.select("th").forEachIndexed { index, th ->
			val name = th.text().replace(Regex("[↕↑↓]"), "").trim()
			if (name.isNotEmpty()) {
				map[name] = index
			}
		}
		return map
	}

	/** libgen 作者字段用分号分隔，常带尾随逗号与空段；无分隔符时保留整串 */
	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseAuthors(raw: String): Set<String> {
		val parts = raw.split(';')
			.map { it.trim().trimEnd(',', ';').trim() }
			.filter { it.isNotBlank() }
			.toSet()
		return when {
			parts.isNotEmpty() -> parts
			raw.isNotBlank() -> setOf(raw.trim())
			else -> emptySet()
		}
	}

	private companion object {
		// 站方 topics[] 分库（顺序与站点搜索表单一致）
		val TOPICS = listOf(
			"l" to "Libgen",
			"c" to "Comics",
			"f" to "Fiction",
			"a" to "Scientific Articles",
			"m" to "Magazines",
			"r" to "Fiction RUS",
			"s" to "Standards",
		)

		// 列表行 description 中的 "Format: pdf" 兜底
		val FORMAT_LINE_FIND = Regex("Format:\\s*(\\w+)")

		// ads.php 元数据块出现的标签；白名单避免把页内广告脚本当成字段
		val METADATA_LABELS = setOf(
			"Title", "Series", "Author(s)", "Publisher", "Year", "Pages", "Language",
			"Type", "Format", "Size", "ISBN", "ISSN", "ASIN", "UDCC", "DOI",
			"Identifier", "Abstract", "Registrator", "Registered", "File name",
		)
	}
}
