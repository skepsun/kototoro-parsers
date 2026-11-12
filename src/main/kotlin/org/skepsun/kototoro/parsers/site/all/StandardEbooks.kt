package org.skepsun.kototoro.parsers.site.all

import androidx.annotation.VisibleForTesting
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet
import java.util.Locale

/**
 * Standard Ebooks - 精选公有领域文学的逐字校对版（英文，全部无 DRM、无需登录）
 *
 * 最后验证: 2026-11（经本机 7890 代理实机验证）
 *
 * 站点协议:
 * - 列表/搜索: `/ebooks?page=N&per-page=24&query={q}&sort={default|author-alpha|popularity|length|reading-ease}`
 *   可叠加 `tags[]={slug}`；列表项是 `ol.ebooks-list > li[about="/ebooks/{author}/{title}[/{translator}]"]`
 * - 详情: `/ebooks/{author}/{title}`，结构化数据（schema.org 微数据）标出书名、作者、
 *   摘要、主题标签、字数与下载区
 * - 下载: `/ebooks/.../downloads/{slug}.epub`（compatible）、`.azw3`、`.kepub.epub`、
 *   `{slug}_advanced.epub`（高级排版），直链无 DRM
 *
 * 说明:
 * - 站点只有英文书，无登录、无借阅；排序口径里 `default` 就是「按 Standard Ebooks 发布/更新日期，新→旧」
 * - 每个下载格式输出为一个独立章节，格式经 `ContentPage.preview` 传给宿主
 */
@ContentSourceParser("STANDARDEBOOKS", "Standard Ebooks", type = ContentType.NOVEL)
internal class StandardEbooks(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.STANDARDEBOOKS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("standardebooks.org")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
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
		availableTags = TAGS.map { (slug, name) -> ContentTag(name, "tag:$slug", source) }.toSet(),
		availableContentTypes = EnumSet.of(ContentType.NOVEL),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val response = webClient.httpGet(buildListUrl(page, order, filter), getRequestHeaders())
		return parseList(response.parseHtml())
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String = buildString {
		append("https://").append(domain).append("/ebooks?page=").append(page)
		append("&per-page=").append(pageSize)
		filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { append("&query=").append(it.urlEncoded()) }
		sortParam(order)?.let { append("&sort=").append(it) }
		for (tag in filter.tags) {
			tag.key.substringAfter("tag:", "").ifEmpty { null }?.let {
				append("&tags%5B%5D=").append(it.urlEncoded())
			}
		}
	}

	private fun sortParam(order: SortOrder): String? = when (order) {
		SortOrder.NEWEST -> "default" // 站点默认排序 = 发布/更新日期，新→旧
		SortOrder.ALPHABETICAL -> "author-alpha"
		SortOrder.POPULARITY -> "popularity"
		else -> null // RELEVANCE: 交给站点默认相关度
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseList(doc: Document): List<Content> {
		val items = ArrayList<Content>()
		val seen = LinkedHashSet<String>()
		for (element in doc.select("ol.ebooks-list li")) {
			val url = element.attrOrNull("about")?.takeIf { it.startsWith("/ebooks/") } ?: continue
			val relativeUrl = url.trimStart('/')
			val title = element
				.selectFirst("p:not(.author) a[property=\"schema:url\"] span[property=\"schema:name\"]")
				?.textOrNull()
				?: continue
			if (!seen.add(relativeUrl)) {
				continue
			}
			val author = element.selectFirst("p.author span[property=\"schema:name\"]")?.textOrNull()
			val cover = element.selectFirst("img[property=\"schema:image\"]")
				?.attrOrNull("src")
				?.toAbsoluteUrl(domain)
			items.add(
				Content(
					id = generateUid(relativeUrl),
					title = title,
					altTitles = emptySet(),
					url = relativeUrl,
					publicUrl = url.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = cover,
					tags = emptySet(),
					state = null,
					authors = author?.let { setOf(it) }.orEmpty(),
					largeCoverUrl = cover,
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
		return parseDetails(response.parseHtml(), manga)
	}

	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDetails(doc: Document, manga: Content): Content {
		val article = doc.selectFirst("article.ebook")
			?: throw ParseException("详情页缺少 article.ebook", manga.url.toAbsoluteUrl(domain))

		val title = article.selectFirst("h1[property=\"schema:name\"]")?.textOrNull() ?: manga.title
		val authors = article.select("hgroup a[property=\"schema:author\"] span[property=\"schema:name\"]")
			.mapNotNull { it.textOrNull() }
			.toSet()
			.ifEmpty { manga.authors }
		val tags = article.select("ul.tags a[href^=\"/subjects/\"]")
			.mapNotNull { link ->
				val name = link.textOrNull() ?: return@mapNotNull null
				val slug = link.attrOrNull("href")?.substringAfter("/subjects/", "")?.ifEmpty { null }
				ContentTag(name, "subject:${slug ?: name.urlEncoded()}", source)
			}
			.toSet()
		val chapters = parseDownloads(article)

		val abstract = article.selectFirst("meta[property=\"schema:abstract\"]")
			?.attrOrNull("content")
			?.trim()
			?.ifEmpty { null }
		val paragraphs = article.selectFirst("div[property=\"schema:description\"]")
			?.select("p")
			?.mapNotNull { it.textOrNull() }
			?.filter { it.isNotBlank() }
			.orEmpty()
		val series = article.selectFirst("aside#reading-ease a[property=\"schema:isPartOf\"]")?.textOrNull()
		val wordCount = article.selectFirst("aside#reading-ease meta[property=\"schema:wordCount\"]")
			?.attrOrNull("content")
			?.toIntOrNull()
		val readingEase = article.selectFirst("aside#reading-ease p")?.textOrNull()
			?.takeIf { "reading ease" in it.lowercase(Locale.ROOT) }

		val description = buildString {
			append(paragraphs.joinToString("\n\n").ifEmpty { abstract.orEmpty() })
			if (isNotEmpty()) {
				append("\n\n")
			}
			append("--- 书籍信息 ---")
			if (wordCount != null) {
				append("\nWords: ").append(wordCount)
			}
			if (readingEase != null) {
				append('\n').append(readingEase)
			}
			if (series != null) {
				append("\nSeries: ").append(series)
			}
			append("\nPublic domain (US), DRM-free")
		}
		val cover = manga.coverUrl ?: article.selectFirst("figure img[src]")
			?.attrOrNull("src")
			?.toAbsoluteUrl(domain)

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = manga.largeCoverUrl ?: cover,
			description = description,
			authors = authors,
			tags = tags.ifEmpty { manga.tags },
			chapters = chapters.ifEmpty { manga.chapters },
		)
	}

	/** 下载区每个 `schema:contentUrl` 是一种排版格式；`section#read-online` 里的链接不算下载 */
	@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
	internal fun parseDownloads(article: Element): List<ContentChapter> {
		val links = article.select("section#download li a[property=\"schema:contentUrl\"]")
		// 章节日期取该书最近一次发布/更新（history 里最新的 time）
		val uploadDate = parseIsoDate(
			article.selectFirst("section#history time[datetime]")?.attrOrNull("datetime"),
		)
		val chapters = ArrayList<ContentChapter>(links.size)
		for (link in links) {
			val href = link.attrOrNull("href") ?: continue
			val format = EbookFormat.fromExtension(href) ?: continue
			val label = link.textOrNull()?.trim().orEmpty()
			chapters.add(
				ContentChapter(
					id = generateUid(href),
					title = chapterTitle(label, format),
					number = (chapters.size + 1).toFloat(),
					volume = 0,
					url = href.toAbsoluteUrl(domain),
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				),
			)
		}
		return chapters
	}

	/** 站点只标「Compatible epub / Advanced epub / kepub / azw3」，扩展名区分不出高级排版，按链接文案命名 */
	private fun chapterTitle(label: String, format: EbookFormat): String {
		val lower = label.lowercase(Locale.ROOT)
		return when {
			"advanced" in lower -> "EPUB (Advanced)"
			"kepub" in lower -> "KEPUB"
			"compatible" in lower -> "EPUB"
			else -> format.displayName.uppercase(Locale.ROOT)
		}
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> = listOf(
		ContentPage(
			id = generateUid(chapter.url),
			url = chapter.url,
			// 真实文件格式（EPUB/AZW3/...），宿主据此选择文本或页面模态
			preview = EbookFormat.fromExtension(chapter.url.substringBefore('?').substringAfterLast('/'))?.name,
			source = source,
		),
	)

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	/** history 里的时间是带偏移量的 ISO 8601（如 2026-11-10T04:16:14+00:00），Instant.parse 只吃 Z 结尾 */
	private fun parseIsoDate(raw: String?): Long = raw?.let {
		runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
	} ?: 0L

	private companion object {
		// 站点筛选面板里的标签（顺序与站点一致），key 形如 tag:philosophy
		val TAGS = listOf(
			"adventure" to "冒险",
			"autobiography" to "自传",
			"biography" to "传记",
			"childrens" to "儿童",
			"comedy" to "喜剧",
			"drama" to "戏剧",
			"fantasy" to "奇幻",
			"fiction" to "小说",
			"horror" to "恐怖",
			"memoir" to "回忆录",
			"mystery" to "推理",
			"nonfiction" to "非虚构",
			"philosophy" to "哲学",
			"poetry" to "诗歌",
			"satire" to "讽刺",
			"science-fiction" to "科幻",
			"shorts" to "短篇",
			"spirituality" to "灵修",
			"travel" to "旅行",
		)
	}
}
