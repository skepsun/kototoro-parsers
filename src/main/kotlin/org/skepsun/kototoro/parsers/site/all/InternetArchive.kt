package org.skepsun.kototoro.parsers.site.all

import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.getLongOrDefault
import java.util.EnumSet
import java.util.Locale

/**
 * Internet Archive (archive.org) - 公有领域扫描书库（文学/哲学/数学/历史等）
 *
 * 最后验证: 2026-11（经本机 7890 代理实机验证）
 *
 * 站点协议（站内 `/search` 页是 JS 壳，只能走官方 JSON 接口）:
 * - 搜索/浏览: `/advancedsearch.php?q={query}&fl[]=...&rows=N&page=N&sort[]=...&output=json`
 *   → `response.numFound` 与 `response.docs[]`；doc 字段值可能是字符串也可能是数组
 *   （`creator`/`subject`/`collection` 常见为数组，`language` 两种都有）
 * - 元数据: `/metadata/{identifier}` → `metadata`（标题/作者/年代/主题/版权状态）
 *   与 `files[]`（每项含 `format`/`name`/`size`，其中 EPUB / Text PDF / DjVuTXT 才是成品书，
 *   其余是 OCR、JPEG2000、scandata 等中间产物，必须按 [DOWNLOAD_FORMATS] 白名单过滤）
 * - 下载: `/download/{identifier}/{name}` 直链，无需登录、无 DRM
 * - 封面: `/services/img/{identifier}`
 *
 * 说明:
 * - 默认查询带 `NOT collection:inlibrary`，只列可自由下载的条目；选了「可借阅」标签才放开，
 *   需借阅的条目通常没有可下载文件，详情页会给出说明
 * - 每种可下载格式输出为一个独立章节，真实格式经 `ContentPage.preview` 传给宿主
 */
@ContentSourceParser("INTERNETARCHIVE", "Internet Archive", type = ContentType.NOVEL)
internal class InternetArchive(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.INTERNETARCHIVE, pageSize = 50) {

	override val configKeyDomain = ConfigKey.Domain("archive.org")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.POPULARITY,
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

	override suspend fun getFilterOptions(): ContentListFilterOptions {
		val languages = LANGUAGE_TAGS.map { (code, name) -> ContentTag(name, "lang:$code", source) }.toSet()
		val collections = COLLECTION_TAGS.map { (code, name) -> ContentTag(name, "collection:$code", source) }.toSet()
		return ContentListFilterOptions(
			availableTags = languages + collections,
			tagGroups = listOf(
				ContentTagGroup("语言", languages),
				ContentTagGroup("馆藏", collections),
			),
			availableContentTypes = EnumSet.of(ContentType.NOVEL),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val response = webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders())
		return parseList(response.parseJson())
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
		return buildString {
			append("https://").append(domain).append("/advancedsearch.php?q=")
			append(buildQuery(filter).urlEncoded())
			for (field in LIST_FIELDS) {
				append("&fl%5B%5D=").append(field)
			}
			append("&rows=").append(pageSize)
			append("&page=").append(page)
			sortParam(order, query)?.let { append("&sort%5B%5D=").append(it.urlEncoded()) }
			append("&output=json")
		}
	}

	/** 把标签集合翻译成 advancedsearch 的 Lucene 查询；多种语言之间是 OR，其余条件之间是 AND */
	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun buildQuery(filter: ContentListFilter): String {
		val parts = ArrayList<String>(4)
		parts.add(BASE_QUERY)
		val collections = filter.tags.mapNotNull { it.key.substringAfter("collection:", "").ifEmpty { null } }
		// 借阅库条目大多下载不了，不主动选中就不纳入结果
		if (IN_LIBRARY !in collections) {
			parts.add("NOT collection:$IN_LIBRARY")
		}
		collections.forEach { parts.add("collection:$it") }
		val languages = filter.tags.mapNotNull { it.key.substringAfter("lang:", "").ifEmpty { null } }
		if (languages.isNotEmpty()) {
			parts.add("language:(${languages.joinToString(" OR ")})")
		}
		filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add("($it)") }
		return parts.joinToString(" AND ")
	}

	private fun sortParam(order: SortOrder, query: String?): String? = when (order) {
		SortOrder.POPULARITY -> "downloads desc"
		SortOrder.NEWEST -> "date desc"
		SortOrder.NEWEST_ASC -> "date asc"
		SortOrder.ALPHABETICAL -> "titleSorter asc"
		// 有关键词时交给站点相关度排序；空关键词浏览（默认排序）按入库时间倒序，
		// 否则全库默认序会把大量用户上传的噪音条目排在最前
		else -> if (query == null) "date desc" else null
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseList(json: JSONObject): List<Content> {
		val docs = json.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
		val items = ArrayList<Content>(docs.length())
		for (i in 0 until docs.length()) {
			val doc = docs.optJSONObject(i) ?: continue
			val identifier = doc.getStringOrNull("identifier") ?: continue
			val cover = coverUrl(identifier)
			items.add(
				Content(
					id = generateUid(identifier),
					title = doc.getStringOrNull("title") ?: identifier,
					altTitles = emptySet(),
					url = "details/$identifier",
					publicUrl = "https://$domain/details/$identifier",
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = cover,
					tags = doc.optStrings("subject").take(MAX_LIST_TAGS)
						.map { ContentTag(it, "subject:${it.urlEncoded()}", source) }
						.toSet(),
					state = null,
					authors = doc.optStrings("creator").toSet(),
					largeCoverUrl = cover,
					description = buildListDescription(doc),
					chapters = null,
					source = source,
				),
			)
		}
		return items
	}

	override suspend fun getDetails(manga: Content): Content {
		val identifier = manga.url.substringAfterLast('/')
		val response = webClient.httpGet("https://$domain/metadata/$identifier", getRequestHeaders())
		return parseDetails(response.parseJson(), manga)
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDetails(json: JSONObject, manga: Content): Content {
		val identifier = manga.url.substringAfterLast('/')
		val meta = json.optJSONObject("metadata")
			?: throw ParseException("metadata 响应缺少 metadata 字段", "https://$domain/metadata/$identifier")

		val chapters = parseFiles(json.optJSONArray("files"), identifier)
		val abstract = meta.optStrings("description").joinToString("\n").ifEmpty { null }
		val info = buildString {
			fun line(label: String, value: String?) {
				if (value.isNullOrBlank()) {
					return
				}
				append('\n').append(label).append(": ").append(value)
			}
			line("Publisher", meta.getStringOrNull("publisher"))
			line("Year", meta.getStringOrNull("year") ?: meta.getStringOrNull("date"))
			line("Language", meta.optStrings("language").joinToString(", ").ifEmpty { null })
			line("Pages", meta.getStringOrNull("imagecount"))
			line("Subjects", meta.optStrings("subject").joinToString("; ").ifEmpty { null })
			line("Collection", meta.optStrings("collection").joinToString(", ").ifEmpty { null })
			line("Copyright", meta.getStringOrNull("possible-copyright-status"))
		}
		val description = buildString {
			if (!abstract.isNullOrBlank()) {
				append(abstract)
				append("\n\n")
			}
			append("--- 书籍信息 ---")
			append(info)
			if (chapters.isEmpty()) {
				append("\n\n此条目在 archive.org 上需要借阅（不属于这里可直接下载的公有领域文件）")
			}
		}
		val cover = coverUrl(meta.getStringOrNull("identifier") ?: identifier)

		return manga.copy(
			title = meta.getStringOrNull("title") ?: manga.title,
			coverUrl = manga.coverUrl ?: cover,
			largeCoverUrl = manga.largeCoverUrl ?: cover,
			description = description,
			authors = meta.optStrings("creator").toSet().ifEmpty { manga.authors },
			tags = meta.optStrings("subject")
				.map { ContentTag(it, "subject:${it.urlEncoded()}", source) }
				.toSet()
				.ifEmpty { manga.tags },
			chapters = chapters,
		)
	}

	/** 按 [DOWNLOAD_FORMATS] 白名单挑出成品书文件，同一格式只留第一个（多版本 PDF 只取主文件） */
	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseFiles(files: JSONArray?, identifier: String): List<ContentChapter> {
		if (files == null) {
			return emptyList()
		}
		val picked = LinkedHashMap<EbookFormat, JSONObject>()
		for (i in 0 until files.length()) {
			val file = files.optJSONObject(i) ?: continue
			if (file.optString("private") == "true" || file.optString("name").isEmpty()) {
				continue
			}
			val format = fileFormat(file) ?: continue
			if (!picked.containsKey(format)) {
				picked[format] = file
			}
		}
		return picked.entries
			.sortedBy { DOWNLOAD_FORMATS.indexOf(it.key) }
			.mapIndexed { index, (format, file) ->
				val name = file.optString("name")
				val size = humanSize(file.getStringOrNull("size"))
				ContentChapter(
					id = generateUid("$identifier|$name"),
					title = if (size != null) "${format.displayName} ($size)" else format.displayName,
					number = (index + 1).toFloat(),
					volume = 0,
					url = downloadUrl(identifier, name),
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
	}

	/** IA 的 `format` 名称到电子书格式的映射；未收录的按文件名扩展名兜底，但限制在白名单内 */
	private fun fileFormat(file: JSONObject): EbookFormat? = when (file.optString("format")) {
		"EPUB" -> EbookFormat.EPUB
		"Text PDF", "Additional Text PDF", "Image Container PDF", "Colored PDF" -> EbookFormat.PDF
		"Kindle" -> EbookFormat.MOBI
		"DjVuTXT", "Text" -> EbookFormat.TXT
		else -> EbookFormat.fromExtension(file.optString("name"))?.takeIf { it in DOWNLOAD_FORMATS }
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> = listOf(
		ContentPage(
			id = generateUid(chapter.url),
			url = chapter.url,
			// 真实文件格式（EPUB/PDF/TXT/...），宿主据此选择文本或页面模态
			preview = EbookFormat.fromExtension(chapter.url.substringBefore('?').substringAfterLast('/'))?.name,
			source = source,
		),
	)

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	private fun buildListDescription(doc: JSONObject): String? = buildString {
		doc.getStringOrNull("year")?.let { append("Year: ").append(it) }
		doc.optStrings("language").firstOrNull()?.let {
			if (isNotEmpty()) append('\n')
			append("Language: ").append(it)
		}
		doc.getLongOrDefault("downloads", 0L).takeIf { it > 0L }?.let {
			if (isNotEmpty()) append('\n')
			append("Downloads: ").append(it)
		}
	}.ifEmpty { null }

	private fun coverUrl(identifier: String): String = "https://$domain/services/img/$identifier"

	/** 文件名里可能有空格和撇号，按路径段编码（+ 号在路径里是字面量，必须换成 %20） */
	private fun downloadUrl(identifier: String, name: String): String =
		"https://$domain/download/$identifier/" + name.urlEncoded().replace("+", "%20")

	private fun humanSize(raw: String?): String? {
		val bytes = raw?.toLongOrNull() ?: return null
		return when {
			bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.ROOT, bytes / 1_000_000_000.0)
			bytes >= 1_000_000L -> "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
			bytes >= 1_000L -> "%.0f KB".format(Locale.ROOT, bytes / 1_000.0)
			else -> "$bytes B"
		}
	}

	private fun JSONObject.optStrings(key: String): List<String> {
		val value = opt(key) ?: return emptyList()
		if (value === JSONObject.NULL) {
			return emptyList()
		}
		if (value is JSONArray) {
			val result = ArrayList<String>(value.length())
			for (i in 0 until value.length()) {
				value.opt(i)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
			}
			return result
		}
		return value.toString().trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
	}

	private companion object {
		const val BASE_QUERY = "mediatype:texts"
		const val IN_LIBRARY = "inlibrary"
		const val MAX_LIST_TAGS = 8

		// 列表接口只取这里列出的字段；description 等大字段留给 metadata 接口
		val LIST_FIELDS = listOf("identifier", "title", "creator", "year", "language", "subject", "downloads")

		// 可作为下载载体的成品文件格式，顺序即章节顺序
		val DOWNLOAD_FORMATS = listOf(EbookFormat.EPUB, EbookFormat.PDF, EbookFormat.TXT, EbookFormat.MOBI)

		val LANGUAGE_TAGS = listOf(
			"chi" to "中文",
			"eng" to "英文",
			"jpn" to "日文",
			"fre" to "法文",
			"ger" to "德文",
			"rus" to "俄文",
			"spa" to "西班牙语",
		)

		val COLLECTION_TAGS = listOf(
			"gutenberg" to "Project Gutenberg",
			"opensource" to "用户上传",
			"inlibrary" to "可借阅",
		)
	}
}
