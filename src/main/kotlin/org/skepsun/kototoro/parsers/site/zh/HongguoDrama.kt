package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Favicons
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * 红果短剧（字节番茄系短剧 / 漫剧）解析器。
 *
 * 官方端没有可直连的公开接口：数据侧要 App 签名与 spade 解密（`a_bogus`、`x-argus`），
 * 播放侧是分轨加密的 mp4（bytevc1），解析器里无法稳定复刻。因此这里接的是自建网关
 * [hongguo-web](https://github.com/hongguo-web)，由它把「签名 + 解密 + 转封装」全部做完，
 * 对外只暴露一组普通 JSON 接口：
 *
 * - `GET /browse?genre=&sort=&offset=&session_id=` 列表（短剧 / 漫剧 / AI 短剧）
 * - `GET /search?q=&limit=&offset=` 搜索
 * - `GET /episodes?series_id=` 详情 + 分集（带 `vid`）
 * - `GET /stream?vid=` 播放：服务端解密后按 Range 输出 mp4，可直接交给播放器
 *
 * 服务地址填在 `configKeyDomain`（默认 `127.0.0.1:8000`，即本机 `start.sh` / `start.bat` 的默认端口，
 * 设置页另有 `10.0.2.2:8000` 候选，供 Android 模拟器访问宿主机）。
 * 远程部署请填带协议的完整地址（`https://hg.example.com`），不带协议时按 `http://` 处理——
 * 本机/内网服务通常没有证书，默认走明文更符合实际部署。
 *
 * 接口鉴权用 `X-Api-Key` 头：`/api-key` 免鉴权，启动后第一条请求自动取一次并缓存；
 * 也可以在来源设置里手填（多实例或禁用 `/api-key` 时用）。
 *
 * 部署、局域网访问与故障排查的完整步骤见 `docs/hongguo_short_play_service_research.md`
 * 的「部署与设置向导」一节。
 */
@ContentSourceParser("HONGGUO", "红果短剧", "zh", type = ContentType.VIDEO)
internal class HongguoDrama(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.HONGGUO, pageSize = LIST_PAGE_SIZE) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(*SERVICE_PRESETS)

	/**
	 * 手填的服务密钥。留空时自动读取 `/api-key`，见 [resolveApiKey]。
	 *
	 * 标题里写明「留空自动获取」，是因为设置页只渲染 ConfigKey 的标题，
	 * 这行字就是用户能在设置页看到的最短提示；完整步骤见
	 * `docs/hongguo_short_play_service_research.md` 的「部署与设置向导」。
	 */
	internal val apiKeyConfig: ConfigKey.Text = ConfigKey.Text(
		key = "service_api_key",
		title = "网关 API Key（留空自动获取）",
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(apiKeyConfig)
	}

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = GENRES.mapTo(mutableSetOf()) { (key, title) -> ContentTag(title, key, source) },
		availableContentTypes = EnumSet.of(ContentType.VIDEO),
	)

	/**
	 * 自建网关没有可解析的 favicon，返回空列表避免对服务地址发起无意义请求。
	 */
	override suspend fun getFavicons(): Favicons = Favicons(emptyList(), referer = null)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: ContentListFilter,
	): List<Content> {
		if (page <= 1) {
			cursors.clear()
			seenIds.clear()
			endPage = Int.MAX_VALUE
		}
		if (page > endPage) {
			return emptyList()
		}
		val url = buildListUrl(page, order, filter)
		val json = request(url)
		val items = json.optJSONArray("items") ?: json.optJSONArray("results") ?: JSONArray()
		val parsed = parseList(items, url)
		// 网关的深池续页会带少量与前面重复的条目，按内部 id 去重（同一实例内有效）
		val list = ArrayList<Content>(parsed.size)
		for (item in parsed) {
			val id = seriesIdOf(item.url).orEmpty()
			if (id.isEmpty() || seenIds.add(id)) {
				list += item
			}
		}
		// `has_more` 缺失时按「拿满一页就当还有」处理，避免少翻一页
		val hasMore = json.optBoolean("has_more", list.size >= pageSize)
		if (hasMore) {
			val session = json.optString("session_id").ifEmpty { cursorOf(page).session }
			val nextOffset = json.optInt("next_offset", cursorOf(page).offset + list.size)
			cursors[page + 1] = Cursor(nextOffset, session)
		} else {
			endPage = page
		}
		return list
	}

	override suspend fun getDetails(content: Content): Content {
		val seriesId = seriesIdOf(content.url) ?: return content
		val url = "$serviceUrl/episodes?series_id=$seriesId"
		val json = request(url)
		val meta = json.optJSONObject("meta") ?: JSONObject()
		val chapters = parseChapters(json.optJSONArray("episodes"), url)
		val cover = meta.optString("cover").trim().ifEmpty { null }
		val title = meta.optString("title").trim().ifEmpty { content.title }
		val description = meta.optString("abstract").trim().ifEmpty { meta.optString("series_intro").trim() }
		return content.copy(
			title = title,
			description = description.takeIf(String::isNotEmpty) ?: content.description,
			coverUrl = cover ?: content.coverUrl,
			largeCoverUrl = cover ?: content.largeCoverUrl,
			tags = parseTags(meta).ifEmpty { content.tags },
			state = parseState(meta.optString("status")) ?: content.state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val vid = VID_REGEX.find(chapter.url)?.groupValues?.getOrNull(1)
			?: throw ParseException("红果短剧：无法识别的分集地址 ${chapter.url}", chapter.url)
		val infoUrl = "$serviceUrl/video_url?vid=$vid"
		// 上游新分集只给 ByteVC2（H.266 私有封装）片源，网关会以 415 拒绝 /stream；
		// 先查一次直链信息，把「播不了」变成带原因的报错，而不是播放器黑屏
		val playable = try {
			webClient.httpGet(infoUrl, requestHeaders()).parseJson().optBoolean("playable", true)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			// 取不到直链信息不阻塞播放，交给 /stream 自己报错
			true
		}
		if (!playable) {
			throw ParseException(
				shortMessage = "红果短剧：该集只有 ByteVC2 片源，客户端无法在线播放（可在 hongguo-web 下载后本地播放）",
				url = infoUrl,
			)
		}
		val key = resolveApiKey()
		return listOf(
			ContentPage(
				id = generateUid("$vid:stream"),
				// 服务端解密后按 Range 输出 mp4，播放器拿到的就是可直接播的 http 地址
				url = "$serviceUrl/stream?vid=$vid&quality=best",
				preview = null,
				headers = mapOf("X-Api-Key" to key),
				source = source,
			),
		)
	}

	/**
	 * 列表/搜索地址。翻页必须沿用上一页返回的 `session_id` 与 `next_offset`：
	 * 网关的官网快路径按「页码」翻、App 深池按「条目偏移 + 会话游标」翻，两套游标
	 * 不能自己按 `page*pageSize` 推。
	 */
	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val cursor = cursorOf(page)
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			return "$serviceUrl/search?q=${query.urlEncoded()}&limit=$pageSize&offset=${cursor.offset}"
		}
		val tag = filter.tags.firstOrNull()
		val genre = tag?.let { GENRES.keys.firstOrNull { key -> key == it.key } }
		// 详情里的内容标签（「逆袭」「重生」…）在网关没有对应筛选接口，退化成关键词搜索
		if (tag != null && genre == null) {
			val keyword = tag.title.trim().ifEmpty { tag.key.trim() }
			if (keyword.isNotEmpty()) {
				return "$serviceUrl/search?q=${keyword.urlEncoded()}&limit=$pageSize&offset=${cursor.offset}"
			}
		}
		val params = buildList {
			add("genre=${genre ?: DEFAULT_GENRE}")
			add("sort=${sortOf(order)}")
			add("limit=$pageSize")
			add("offset=${cursor.offset}")
			if (cursor.session.isNotEmpty()) {
				add("session_id=${cursor.session.urlEncoded()}")
			}
		}
		return "$serviceUrl/browse?${params.joinToString("&")}"
	}

	private suspend fun request(url: String): JSONObject {
		try {
			return webClient.httpGet(url, requestHeaders()).parseJson()
		} catch (e: ParseException) {
			throw e
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			throw ParseException(
				shortMessage = "红果短剧：请求 $url 失败，请确认 hongguo-web 服务已启动且地址正确",
				url = url,
				cause = e,
			)
		}
	}

	private suspend fun requestHeaders(): Headers = getRequestHeaders().newBuilder()
		.add("X-Api-Key", resolveApiKey())
		.add("Accept", "application/json")
		.build()

	/**
	 * 优先用设置里的密钥；留空时读一次 `/api-key`（该接口免鉴权）并在实例内缓存。
	 */
	internal suspend fun resolveApiKey(): String {
		val configured = config[apiKeyConfig].trim()
		if (configured.isNotEmpty()) {
			return configured
		}
		cachedApiKey?.let { return it }
		val url = "$serviceUrl/api-key"
		val key = try {
			webClient.httpGet(url).parseJson().optString("api_key").trim()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			throw ParseException(
				shortMessage = "红果短剧：连不上 hongguo-web 服务（$serviceUrl），" +
					"请先在本机/服务器启动服务并在此处填写地址",
				url = url,
				cause = e,
			)
		}
		if (key.isEmpty()) {
			throw ParseException("红果短剧：hongguo-web 未返回 api_key（$serviceUrl）", url)
		}
		cachedApiKey = key
		return key
	}

	internal fun parseList(items: JSONArray, url: String): List<Content> = buildList(items.length()) {
		for (index in 0 until items.length()) {
			val item = items.optJSONObject(index) ?: continue
			val seriesId = item.optString("series_id").trim()
			if (seriesId.isEmpty()) {
				continue
			}
			add(parseCard(item, seriesId))
		}
		if (isEmpty() && items.length() > 0) {
			throw ParseException("红果短剧：列表条目缺少 series_id", url)
		}
	}

	internal fun parseCard(item: JSONObject, seriesId: String): Content {
		val relativeUrl = "/series/$seriesId"
		val cover = item.optString("cover").trim().ifEmpty { null }
		val tags = parseTags(item)
		return Content(
			id = generateUid(relativeUrl),
			title = item.optString("title").trim().ifBlank { "未命名短剧" },
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = "$serviceUrl/ui",
			rating = parseRating(item.optString("score")),
			contentRating = ContentRating.SAFE,
			coverUrl = cover,
			tags = tags,
			state = parseState(item.optString("status")),
			authors = emptySet(),
			largeCoverUrl = cover,
			description = item.optString("abstract").trim()
				.ifEmpty { item.optString("series_intro").trim() }
				.takeIf(String::isNotEmpty),
			source = source,
		)
	}

	/**
	 * 分集：`episodes[]` 的 `index` 是集号（1 起），`vid` 是播放所需的视频 id。
	 * 章节 url 只存站点内部 id（`/video/{vid}`），与实际服务地址无关。
	 */
	internal fun parseChapters(episodes: JSONArray?, url: String): List<ContentChapter> {
		val result = ArrayList<ContentChapter>(episodes?.length() ?: 0)
		if (episodes != null) {
			for (index in 0 until episodes.length()) {
				val episode = episodes.optJSONObject(index) ?: continue
				val vid = episode.optString("vid").trim()
				if (vid.isEmpty()) {
					continue
				}
				val number = episode.optInt("index", 0).takeIf { it > 0 }
					?: (index + 1)
				val relativeUrl = "/video/$vid"
				result += ContentChapter(
					id = generateUid(relativeUrl),
					title = episode.optString("title").trim().ifEmpty { "第${number}集" },
					number = number.toFloat(),
					volume = 0,
					url = relativeUrl,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
		}
		if (result.isEmpty()) {
			throw ParseException("红果短剧：该剧没有可播放的分集（可能未上架或需要登录）", url)
		}
		return result.sortedBy { it.number }
	}

	private fun parseTags(json: JSONObject): Set<ContentTag> = buildSet {
		json.optJSONArray("tags")?.let { array ->
			for (index in 0 until array.length()) {
				val title = array.optString(index).trim()
				if (title.isNotEmpty()) {
					add(ContentTag(title, title, source))
				}
			}
		}
		// 列表里的 `category` 是「逆袭 / 玄幻仙侠」这样的斜杠串，详情 meta 里则是字符串数组
		when (val category = json.opt("category")) {
			is JSONArray -> for (index in 0 until category.length()) {
				val title = category.optString(index).trim()
				if (title.isNotEmpty()) {
					add(ContentTag(title, title, source))
				}
			}

			is String -> category.split('/').map(String::trim).filter(String::isNotEmpty).forEach { title ->
				add(ContentTag(title, title, source))
			}
		}
	}

	private fun parseRating(raw: String): Float {
		val value = raw.trim().toFloatOrNull() ?: return RATING_UNKNOWN
		return if (value > 1f) (value / 10f).coerceIn(0f, 1f) else value
	}

	private fun parseState(status: String): ContentState? = when (status.trim()) {
		"完结", "已完结", "已完结（全）" -> ContentState.FINISHED
		"连载", "连载中", "更新中" -> ContentState.ONGOING
		else -> null
	}

	private fun cursorOf(page: Int): Cursor = if (page <= 1) {
		Cursor(0, "")
	} else {
		cursors[page] ?: Cursor((page - 1) * pageSize, "")
	}

	private fun sortOf(order: SortOrder): String = when (order) {
		SortOrder.UPDATED -> "online_time"
		else -> "hot_score"
	}

	private fun seriesIdOf(url: String): String? = SERIES_ID_REGEX.find(url)?.groupValues?.getOrNull(1)

	private val serviceUrl: String
		get() {
			val raw = domain.trim().trimEnd('/')
			return if (raw.contains("://")) raw else "http://$raw"
		}

	private var cachedApiKey: String? = null

	/** 网关已明确没有下一页的页号；超过它直接返回空列表，不再发请求。 */
	private var endPage: Int = Int.MAX_VALUE

	private data class Cursor(val offset: Int, val session: String)

	private val cursors = HashMap<Int, Cursor>()

	private val seenIds = ArrayList<String>()

	companion object {
		private const val DEFAULT_SERVICE = "127.0.0.1:8000"

		/**
		 * 设置页「视频源域名」的候选值。两项都对应真实部署形态，用户点一下即可填入：
		 * `127.0.0.1` 是网关与 App 同机（电脑本机 / 安卓 Termux），
		 * `10.0.2.2` 是 Android 模拟器访问宿主机 localhost 的固定别名。
		 */
		private val SERVICE_PRESETS = arrayOf(DEFAULT_SERVICE, "10.0.2.2:8000")
		private const val DEFAULT_GENRE = "short_play"
		private const val LIST_PAGE_SIZE = 20

		private val GENRES: Map<String, String> = linkedMapOf(
			"short_play" to "真人短剧",
			"comic_series" to "漫剧",
			"ai_series" to "AI短剧",
		)

		private val SERIES_ID_REGEX = Regex("/series/(\\d+)")
		private val VID_REGEX = Regex("/video/(\\d+)")
	}
}
