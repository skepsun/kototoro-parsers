@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONArray
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
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 漫蛙（manwa2.com），中文圈流通度较高的韩漫/日漫汉化聚合站，与滴答漫画、古风漫画同属一套
 * MCCMS 内核，作品与章节 ID 空间、阅读参数协议一致（前 16 字节 IV + AES-CBC，密钥
 * `9S8$vJnU2ANeSRoF`，复用 [AesCbcDecoder]），差别在站点路由与前端 DOM。
 *
 * 协议要点（2026-11 实机核对）：
 * - 列表：分类页 `/category` 每页 30 条，筛选是**单段路径**并且各段互相独立、顺序不敏感，
 *   已核对 `/category/list/3/finish/2` 与 `/category/finish/2/list/3` 结果一致。翻页用
 *   `/category[/…]/page/{n}`，越界会 302 到 `/err/404`（解析为空列表即以收敛）。
 *   卡片在 `ul.manga-list-2 > li`：标题 `p.manga-list-2-title a`，简介 `p.manga-list-2-tip`；
 *   封面两种写法都要看——懒加载容器用 `img[data-original]`，分类/排行页把真封面直接放在 `img[src]`，
 *   只有个别未出图的条目留 `packs/mccms/empty.png` 占位图（这种情况按「没有封面」处理）。
 * - 排序：`/custom/top`（人气总排行）与 `/custom/update`（最新更新）都是固定 30 条、**不分页**，
 *   所以只作为第一页使用，带筛选或翻页时回落到 `/category`。
 * - 搜索：`/search?key={关键词}`，服务端渲染 `ul.book-list > li`（标题 `p.book-list-info-title`、
 *   简介 `p.book-list-info-desc`、作者 `span.book-list-info-bottom-item`、状态
 *   `span.book-list-info-bottom-right-font`）。该页面**没有分页控件**，`page` 参数被忽略，
 *   只给 30 条结果，因此搜索固定单页。
 * - 详情：`/comic/{id}`。标题 `h1.detail-main-info-title`，封面 `div.detail-main-cover img`，
 *   信息为多行 `p.detail-main-info-author`（`span.detail-main-info-author-field` 标签 +
 *   `span.detail-main-info-value` 取值，含作者/更新状态/最新章节/地区/类别），简介 `p.detail-desc`。
 *   站内没有独立的分类标签锚点，标签只能由「地区」与空格分隔的「类别」文本推导。
 * - 目录：`ul#detail-list-select > li > a.chapteritem`，**全部章节都在 SSR 里**（实测 924 章的单页
 *   同样完整），顺序是**新→旧**，这里反转成阅读顺序。页面上的「倒序 / 展开全部章节」都是纯前端
 *   行为（前者改 `sort-btn` 的 mode，后者只放开容器 max-height），不触发额外请求。
 * - 阅读：`/chapter/{bookId}/{chapterId}`。图片不在 DOM 里——SSR 只给一段
 *   `var params = '…'`（Base64，前 16 字节为 IV），由 `chapter.js` 用 AES-CBC 解出
 *   `{host, source_id, images[], lazy}` 后在前端渲染。这里在 Kotlin 侧解密，不执行站点 JS。
 *   `source_id=12` 时本站给的是**绝对地址**（`img1.baipiaoguai.org`），图床按 `Referer: https://{域名}/`
 *   做防盗链（不带 Referer 返回 403），故在 `ContentPage.headers` 里带上 Referer。
 *   若将来出现相对路径，按同族站点惯例拼到 [BaipiaoguaiImageDecoder.HOST] 上。
 * - 成人内容：站点主体是成人漫画，隐藏的 `#adult-content` 区块在抽样的四个作品里都没有独立章节
 *   列表（`#adult-list-select` 不存在），成人章节混在普通目录里，无需额外处理。
 */
@ContentSourceParser("MANWA2", "漫蛙", "zh", type = ContentType.HENTAI_MANGA)
internal class Manwa2Parser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.MANWA2, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("manwa2.com", "www.manwa2.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	/** 站点默认入口是「最新更新」，不要落到枚举顺序第一位的排行页。 */
	override val defaultSortOrder: SortOrder = SortOrder.UPDATED

	private val filterGroups: List<ContentTagGroup> by lazy {
		FILTER_GROUPS.map { (title, options) ->
			ContentTagGroup(title, options.mapTo(linkedSetOf()) { (label, segment) -> ContentTag(label, segment, source) })
		}
	}

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = filterGroups.flatMapTo(linkedSetOf()) { it.tags },
		tagGroups = filterGroups,
		// 站点作品全是成人向，只声明 ADULT，避免让用户选出一个不生效的分级筛选
		availableContentRating = EnumSet.of(ContentRating.ADULT),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "${baseUrl()}/")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			// 站内搜索不分页，第二页起直接返回空列表让分页收敛
			if (page > 1) return emptyList()
			val response = webClient.httpGet("${baseUrl()}/search?key=${query.urlEncoded()}", getRequestHeaders())
			if (!response.isSuccessful) return emptyList()
			return parseSearchResults(response.parseHtml())
		}
		val path = buildListPath(page, order, filter)
		if (path == null) return emptyList()
		val response = webClient.httpGet(baseUrl() + path, getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parseList(response.parseHtml())
	}

	/**
	 * 无筛选时第一页优先走站点自己的排行/更新页（内容更贴合语义），其余情况统一走分类分页。
	 * 排行与更新页是固定 30 条且不分页，因此只有第 1 页可用。
	 */
	internal fun buildListPath(page: Int, order: SortOrder, filter: ContentListFilter): String? {
		val tags = filter.tags
		if (tags.isEmpty()) {
			if (page > 1) return null
			return if (order == SortOrder.POPULARITY) TOP_PATH else UPDATED_PATH
		}
		return buildString {
			append("/category")
			// 同一分组只取第一个被选中的标签；不同分组的路径段互不影响（站点对顺序不敏感）
			filterGroups.forEach { group ->
				group.tags.firstOrNull { it in tags }?.let { tag -> append('/').append(tag.key) }
			}
			if (page > 1) append("/page/").append(page)
		}
	}

	internal fun parseList(document: Document): List<Content> =
		document.select("ul.manga-list-2 li").mapNotNull { card ->
			val anchor = card.selectFirst("a[href^=/comic/]") ?: return@mapNotNull null
			val href = anchor.attr("href").trim()
			if (!DETAIL_PATH.matches(href)) return@mapNotNull null
			val title = card.selectFirst("p.manga-list-2-title")?.text()?.trim().orEmpty()
				.ifEmpty { anchor.attr("title").trim() }
			if (title.isEmpty()) return@mapNotNull null
			Content(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = baseUrl() + href,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = resolveCover(card.selectFirst("img")),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = card.selectFirst("p.manga-list-2-tip")?.text()?.trim(),
				source = source,
			)
		}.distinctBy(Content::id)

	internal fun parseSearchResults(document: Document): List<Content> =
		document.select("ul.book-list li").mapNotNull { card ->
			val anchor = card.selectFirst("a[href^=/comic/]") ?: return@mapNotNull null
			val href = anchor.attr("href").trim()
			if (!DETAIL_PATH.matches(href)) return@mapNotNull null
			val title = card.selectFirst("p.book-list-info-title")?.text()?.trim().orEmpty()
				.ifEmpty { anchor.attr("title").trim() }
			if (title.isEmpty()) return@mapNotNull null
			val author = card.selectFirst("span.book-list-info-bottom-item")?.text()?.trim()
				?.substringAfter('：', "")
				?.trim()
				?.takeIf(String::isNotEmpty)
			Content(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = baseUrl() + href,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = resolveCover(card.selectFirst("img")),
				tags = emptySet(),
				state = parseState(card.selectFirst("span.book-list-info-bottom-right-font")?.text()),
				authors = if (author != null) setOf(author) else emptySet(),
				description = card.selectFirst("p.book-list-info-desc")?.text()?.trim(),
				source = source,
			)
		}.distinctBy(Content::id)

	override suspend fun getDetails(manga: Content): Content {
		val response = webClient.httpGet(baseUrl() + manga.url, getRequestHeaders())
		if (!response.isSuccessful) return manga
		return parseDetails(response.parseHtml(), manga)
	}

	internal fun parseDetails(document: Document, manga: Content): Content {
		val title = document.selectFirst("h1.detail-main-info-title")?.text()?.trim().orEmpty()
			.ifEmpty { manga.title }
		val cover = document.selectFirst("div.detail-main-cover img")?.let(::resolveCover) ?: manga.coverUrl
		val info = readableInfo(document)
		val authors = info["作者"]?.select("a")?.map { it.text().trim() }
			?.filterTo(linkedSetOf()) { it.isNotEmpty() }
			.orEmpty()
		val tags = linkedSetOf<ContentTag>()
		info["地区"]?.text()?.trim()?.takeIf(String::isNotEmpty)?.let { tags.add(ContentTag(it, it, source)) }
		info["类别"]?.text()?.split(' ', '　', '/')?.map(String::trim)?.forEach { value ->
			if (value.isNotEmpty()) tags.add(ContentTag(value, value, source))
		}

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = document.selectFirst("p.detail-desc")?.text()?.trim()?.takeIf(String::isNotEmpty)
				?: manga.description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = parseState(info["更新状态"]?.text()) ?: manga.state,
			chapters = parseChapters(document, manga),
			contentRating = ContentRating.ADULT,
		)
	}

	/** `p.detail-main-info-author` 是「标签 + 取值」两段结构，这里转成 标签 -> 取值元素。 */
	private fun readableInfo(document: Document): Map<String, Element> = buildMap {
		document.select("p.detail-main-info-author").forEach { row ->
			val label = row.selectFirst("span.detail-main-info-author-field")?.text()?.trim()
				?.trimEnd('：', ':')
				?.trim()
				.orEmpty()
			val value = row.selectFirst("span.detail-main-info-value") ?: return@forEach
			if (label.isNotEmpty()) put(label, value)
		}
	}

	internal fun parseChapters(document: Document, manga: Content): List<ContentChapter> =
		document.select("#detail-list-select a.chapteritem").asReversed().mapNotNull { anchor ->
			val href = anchor.attr("href").trim()
			val title = anchor.text().trim()
			if (!CHAPTER_PATH.matches(href) || title.isEmpty()) return@mapNotNull null
			ContentChapter(
				id = generateUid("${manga.id}:$href"),
				title = title,
				number = CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}.distinctBy(ContentChapter::id)

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val response = webClient.httpGet(baseUrl() + chapter.url, getRequestHeaders())
		if (!response.isSuccessful) return emptyList()
		return parsePages(response.parseHtml(), chapter.url)
	}

	internal fun parsePages(document: Document, chapterUrl: String): List<ContentPage> {
		val encoded = document.select("script").firstNotNullOfOrNull { script ->
			PARAMS_PATTERN.find(script.data())?.groupValues?.get(2)
		} ?: return emptyList()
		val params = AesCbcDecoder.decodeJsonWithPrefixedIv(encoded, PARAMS_KEY) ?: return emptyList()
		// 服务端会把请求 Host 写进参数；`www.` 别名视为同一站，其它 Host 说明拿到的是别人的包
		val paramsHost = params.optString("host")
		if (paramsHost.isNotEmpty() && paramsHost.removePrefix("www.") != domain.removePrefix("www.")) {
			return emptyList()
		}
		val images = params.optJSONArray("images") ?: return emptyList()
		val headers = mapOf("Referer" to "${baseUrl()}/")
		val pageKey = chapterUrl.substringAfter(domain)
		return buildList(images.length()) {
			for (index in 0 until images.length()) {
				val imageUrl = resolveImageUrl(images, index) ?: continue
				add(
					ContentPage(
						id = generateUid("$pageKey:$index"),
						url = imageUrl,
						preview = imageUrl,
						headers = headers,
						source = source,
					),
				)
			}
		}
	}

	/** `source_id=12` 在本站给的是图床绝对地址；相对路径按同族站点惯例补到 Baipiaoguai 图床上。 */
	private fun resolveImageUrl(images: JSONArray, index: Int): String? {
		val value = images.optString(index).trim()
		if (value.isEmpty()) return null
		return when {
			value.startsWith("https://") || value.startsWith("http://") -> value
			value.startsWith("//") -> "https:$value"
			else -> "${BaipiaoguaiImageDecoder.HOST}/${value.trimStart('/')}"
		}
	}

	override suspend fun getPageUrl(page: ContentPage): String = page.url

	/** 分类页的封面只有占位图，排行榜页把真封面放在 `src` 上，两者都要兼容。 */
	private fun resolveCover(image: Element?): String? {
		if (image == null) return null
		image.attr("data-original").trim().takeIf(::isRemoteUrl)?.let { return it }
		val src = image.attr("src").trim()
		if (!isRemoteUrl(src)) return null
		return src.takeUnless { url -> COVER_PLACEHOLDERS.any(url::contains) }
	}

	private fun isRemoteUrl(value: String): Boolean =
		value.startsWith("https://") || value.startsWith("http://")

	private fun parseState(value: String?): ContentState? = when {
		value == null -> null
		value.contains("完结") -> ContentState.FINISHED
		value.contains("连载") -> ContentState.ONGOING
		else -> null
	}

	private fun baseUrl(): String = "https://$domain"

	internal companion object {
		private const val PARAMS_KEY = "9S8\$vJnU2ANeSRoF"
		private const val TOP_PATH = "/custom/top"
		private const val UPDATED_PATH = "/custom/update"

		/** 站点懒加载占位图，出现在封面位置时要当成「没有封面」。 */
		private val COVER_PLACEHOLDERS = listOf("packs/mccms/empty.png", "imagecover_s.png", "/img/lazy")
		private val DETAIL_PATH = Regex("""/comic/\d+""")
		private val CHAPTER_PATH = Regex("""/chapter/\d+/\d+""")
		private val CHAPTER_NUMBER = Regex(
			"""(?:第|Stage\.?|Act\.?|Episode\.?)\s*(\d+(?:\.\d+)?)""",
			RegexOption.IGNORE_CASE,
		)
		private val PARAMS_PATTERN = Regex(
			"""\bparams\s*=\s*(['"])(.*?)\1""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)

		/** 站点筛选是 `{维度}/{id}` 的单段路径，各段可组合、顺序不敏感。 */
		private val FILTER_GROUPS = listOf(
			"地区" to listOf(
				"国产漫画" to "list/1",
				"日本漫画" to "list/2",
				"韩国漫画" to "list/3",
				"欧美漫画" to "list/4",
			),
			"主题" to listOf(
				"故事漫画" to "theme/32",
				"轻小说" to "theme/33",
				"四格" to "theme/34",
				"绘本" to "theme/35",
				"单幅" to "theme/36",
				"条漫" to "theme/37",
			),
			"产地" to listOf(
				"内地" to "city/42",
				"港台" to "city/43",
				"韩国" to "city/44",
				"日本" to "city/45",
				"欧美" to "city/134",
			),
			"品质" to listOf(
				"签约" to "quality/38",
				"精品" to "quality/39",
				"热门" to "quality/40",
				"新手" to "quality/41",
			),
			"版权" to listOf(
				"首发" to "copyright/46",
				"独家" to "copyright/47",
			),
			"状态" to listOf(
				"连载" to "finish/1",
				"完结" to "finish/2",
			),
			"标签" to listOf(
				"韩漫" to "tags/889",
				"恋爱" to "tags/872",
				"穿越" to "tags/891",
				"重生" to "tags/880",
				"逆袭" to "tags/883",
				"玄幻" to "tags/899",
				"都市" to "tags/870",
				"校园" to "tags/895",
				"搞笑" to "tags/902",
				"治愈" to "tags/901",
				"纯爱" to "tags/914",
				"悬疑" to "tags/900",
				"非H" to "tags/950",
			),
		)
	}
}
