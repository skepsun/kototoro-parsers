@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 腾讯动漫（ac.qq.com）——正版漫画，免费章可读，付费/V 券章显式报错。
 *
 * 列表与详情都是服务端渲染的桌面站 DOM：
 * `/Comic/all[/theme/{id}][/finish/{1|2}][/vip/{1|2}]/search/{hot|time}/page/{page}`，
 * 搜索走 `/Comic/searchList/search/{keyword}/page/{page}`，详情是 `/Comic/comicInfo/id/{comicId}`。
 *
 * 阅读页把整章 JSON 塞进 `var DATA = '<base64>'`，但 base64 里按
 * `window[...] = <拼接表达式>` 给出的 (偏移, 噪声长度) 对插入了噪声字符。该表达式只是字符串字面量与
 * `(+eval("算术")).toString()` 的拼接，求值得到 nonce；`nonce.match(/\d+[a-zA-Z]+/g)` 的每个 token 是
 * 「偏移量 + 噪声」，倒序从字符数组里按偏移剥掉等长噪声即可还原 base64。
 *
 * 付费章：详情页用 `i.ui-icon-pay` 标记，正文 JSON 里 `chapter.canRead == false`。这类章节保留在章节
 * 列表里（标题前加锁标记）但不做任何绕过，读取时抛 [ParseException]。
 */
@ContentSourceParser("TENCENT_COMICS", "腾讯动漫", "zh")
internal class TencentComicsParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.TENCENT_COMICS, pageSize = 12) {

	override val configKeyDomain = ConfigKey.Domain("ac.qq.com")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

	private val genreTags: List<ContentTag> = GENRES.map { (title, id) ->
		ContentTag(title, THEME_PREFIX + id, source)
	}
	private val progressTags: List<ContentTag> = listOf(
		ContentTag("连载中", FINISH_PREFIX + "1", source),
		ContentTag("已完结", FINISH_PREFIX + "2", source),
	)
	private val priceTags: List<ContentTag> = listOf(
		ContentTag("免费", VIP_PREFIX + "1", source),
		ContentTag("付费", VIP_PREFIX + "2", source),
	)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
		availableTags = (genreTags + progressTags + priceTags).toSet(),
		tagGroups = listOf(
			ContentTagGroup("题材", genreTags.toSet()),
			ContentTagGroup("进度", progressTags.toSet()),
			ContentTagGroup("属性", priceTags.toSet()),
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "$rootUrl/")
		.add("Accept-Language", "zh-CN,zh;q=0.9")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val isSearch = !filter.query.isNullOrBlank()
		val url = buildListUrl(page, order, filter)
		val response = webClient.httpGet(url, getRequestHeaders())
		if (!response.isSuccessful) {
			return emptyList()
		}
		val document = response.parseHtml()
		return if (isSearch) parseSearchList(document) else parseList(document)
	}

	override suspend fun getDetails(manga: Content): Content {
		val url = "$rootUrl/Comic/comicInfo/id/${manga.url}"
		val response = webClient.httpGet(url, getRequestHeaders())
		if (!response.isSuccessful) {
			throw ParseException("腾讯动漫：详情页请求失败 ${response.code}", url)
		}
		return parseDetails(manga, response.parseHtml())
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val response = webClient.httpGet(url, getRequestHeaders())
		if (!response.isSuccessful) {
			throw ParseException("腾讯动漫：阅读页请求失败 ${response.code}", url)
		}
		val payload = decodeReaderPayload(response.parseRaw(), url)
		val chapterJson = payload.optJSONObject("chapter")
		if (chapterJson != null && !chapterJson.optBoolean("canRead", true)) {
			throw ParseException(
				shortMessage = "腾讯动漫：该章节是付费或 V 券章节，无法免费读取",
				url = url,
			)
		}
		val pictures = payload.optJSONArray("picture") ?: JSONArray()
		val headers = mapOf("Referer" to "$rootUrl/")
		return (0 until pictures.length()).mapNotNull { index ->
			val picture = pictures.optJSONObject(index) ?: return@mapNotNull null
			val source = picture.optString("url").trim()
			if (source.isEmpty()) return@mapNotNull null
			ContentPage(
				id = generateUid("$url#$index"),
				url = source.toAbsoluteUrl(domain),
				preview = null,
				headers = headers,
				source = this.source,
			)
		}
	}

	internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
		val query = filter.query?.trim()
		if (!query.isNullOrEmpty()) {
			return "$rootUrl/Comic/searchList/search/${query.urlEncoded()}/page/$page"
		}
		val sort = if (order == SortOrder.UPDATED) "time" else "hot"
		return buildString {
			append(rootUrl)
			append("/Comic/all")
			filter.tags.firstOrNull { it.key.startsWith(THEME_PREFIX) }?.let { tag ->
				append("/theme/").append(tag.key.removePrefix(THEME_PREFIX))
			}
			filter.tags.firstOrNull { it.key.startsWith(FINISH_PREFIX) }?.let { tag ->
				append("/finish/").append(tag.key.removePrefix(FINISH_PREFIX))
			}
			filter.tags.firstOrNull { it.key.startsWith(VIP_PREFIX) }?.let { tag ->
				append("/vip/").append(tag.key.removePrefix(VIP_PREFIX))
			}
			append("/search/").append(sort)
			append("/page/").append(page)
		}
	}

	internal fun parseList(document: Document): List<Content> =
		document.select("li.ret-search-item").mapNotNull { item ->
			val cover = item.selectFirst("div.ret-works-cover")
			val anchor = cover?.selectFirst("a[href*=/Comic/comicInfo/id/]")
				?: item.selectFirst("h3.ret-works-title a[href*=/Comic/comicInfo/id/]")
				?: return@mapNotNull null
			val comicId = COMIC_ID_REGEX.find(anchor.attr("href"))?.groupValues?.getOrNull(1)
				?: return@mapNotNull null
			val title = anchor.attr("title").trim().ifEmpty {
				item.selectFirst("h3.ret-works-title a")?.text()?.trim().orEmpty()
			}
			if (title.isEmpty()) return@mapNotNull null
			val tags = item.select("p.ret-works-tags span[href*=/theme/]").mapNotNullTo(linkedSetOf()) { span ->
				val name = span.text().trim()
				if (name.isEmpty()) null else ContentTag(name, span.attr("href"), source)
			}
			Content(
				id = generateUid(comicId),
				title = title,
				altTitles = emptySet(),
				url = comicId,
				publicUrl = "$rootUrl/Comic/comicInfo/id/$comicId",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				coverUrl = coverUrl(cover?.selectFirst("img") ?: item.selectFirst("h3 + p img")),
				tags = tags,
				state = null,
				authors = item.selectFirst("p.ret-works-author")?.text()?.trim()?.takeIf(String::isNotEmpty)
					?.let { setOf(it) } ?: emptySet(),
				description = item.selectFirst("p.ret-works-decs")?.text()?.trim()?.takeIf(String::isNotEmpty),
				source = source,
			)
		}.distinctBy(Content::id)

	internal fun parseSearchList(document: Document): List<Content> =
		document.select("ul.mod_book_list > li").mapNotNull { item ->
			val anchor = item.selectFirst("a[href*=/Comic/comicInfo/id/]") ?: return@mapNotNull null
			val comicId = COMIC_ID_REGEX.find(anchor.attr("href"))?.groupValues?.getOrNull(1)
				?: return@mapNotNull null
			val title = item.selectFirst("h4.mod_book_name a")?.text()?.trim()?.ifEmpty { null }
				?: anchor.attr("title").trim()
			if (title.isEmpty()) return@mapNotNull null
			Content(
				id = generateUid(comicId),
				title = title,
				altTitles = emptySet(),
				url = comicId,
				publicUrl = "$rootUrl/Comic/comicInfo/id/$comicId",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				coverUrl = coverUrl(item.selectFirst("a.mod_book_cover img")),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Content::id)

	internal fun parseDetails(content: Content, document: Document): Content {
		val title = document.selectFirst("h1.works-intro-title strong")?.text()?.trim()
			?.takeIf(String::isNotEmpty) ?: content.title
		val authors = document.selectFirst("p.works-intro-digi span.first em")?.text()?.trim()
			?.takeIf(String::isNotEmpty)
			?.let { setOf(it) } ?: content.authors
		val state = when (document.selectFirst("label.works-intro-status")?.text()?.trim()) {
			"连载中", "連載中" -> ContentState.ONGOING
			"已完结", "已完結" -> ContentState.FINISHED
			else -> content.state
		}
		val chapters = document.select(".chapter-page-all .works-chapter-item")
			.mapIndexedNotNull { index, element ->
				val anchor = element.selectFirst("a[href*=/ComicView/index/id/]")
					?: return@mapIndexedNotNull null
				val href = anchor.attr("href").trim()
				if (href.isEmpty()) return@mapIndexedNotNull null
				val name = anchor.text().trim()
				if (name.isEmpty()) return@mapIndexedNotNull null
				val locked = element.selectFirst("i.ui-icon-pay") != null
				ContentChapter(
					id = generateUid(href),
					title = if (locked) LOCK_PREFIX + name else name,
					number = (index + 1).toFloat(),
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
		return content.copy(
			title = title,
			coverUrl = document.selectFirst("div.works-cover img")?.let(::coverUrl) ?: content.coverUrl,
			description = document.selectFirst("p.works-intro-short")?.text()?.trim()
				?.takeIf(String::isNotEmpty) ?: content.description,
			authors = authors,
			state = state,
			chapters = chapters.ifEmpty { null },
		)
	}

	/**
	 * 还原阅读页的 `var DATA`。
	 *
	 * 站点每次请求都会随机换一种 nonce 写法（纯字面量、算术、三元、位运算、`'1'.charCodeAt()`、
	 * DOM 探测……），因此把所有 `window[...] = <表达式>` 候选从后往前逐个尝试：先求值出 nonce，
	 * 再按 `\d+[a-zA-Z]+` 给出的 (偏移, 噪声长度) 对倒序剥噪声，最后用 base64 + JSON 校验。
	 * 第一个能解出合法 JSON 的候选即命中；全部失败才报错并带上最后一次原因。
	 */
	internal fun decodeReaderPayload(html: String, url: String): JSONObject {
		val dataMatch = DATA_REGEX.find(html)
			?: throw ParseException(describeMissingPayload(html), url)
		val raw = dataMatch.groupValues[1]
		val head = html.substring(0, dataMatch.range.first)
		val candidates = NONCE_ASSIGNMENT_REGEX.findAll(head).map { it.groupValues[1].trim() }.toList()
		if (candidates.isEmpty()) {
			throw ParseException("腾讯动漫：阅读页缺少 nonce 定义", url)
		}
		var cause: Exception? = null
		for (expression in candidates.asReversed()) {
			try {
				return decodeWithNonce(raw, TencentNonceEvaluator.evaluate(expression), url)
			} catch (e: ParseException) {
				cause = e
			} catch (e: IllegalArgumentException) {
				cause = e
			}
		}
		throw ParseException(
			"腾讯动漫：章节数据解码失败（已尝试 ${candidates.size} 个 nonce 候选，最后一次错误：${cause?.message}）",
			url,
			cause,
		)
	}

	/** 阅读页拿不到 `var DATA` 时给出可诊断的原因：错误页、改版、还是需要登录。 */
	private fun describeMissingPayload(html: String): String = when {
		html.contains("该漫画不存在或章节已被删除") -> "腾讯动漫：该漫画不存在或章节已被删除"
		html.contains("var DATA") -> "腾讯动漫：阅读页的章节数据格式无法识别（站点可能已改版）"
		else -> "腾讯动漫：阅读页没有章节数据（该章节可能已下架或需要登录）"
	}

	private fun decodeWithNonce(raw: String, nonce: String, url: String): JSONObject {
		val chars = raw.toMutableList()
		val tokens = NOISE_TOKEN_REGEX.findAll(nonce).map { it.value }.toList()
		for (token in tokens.asReversed()) {
			val offset = (token.takeWhile(Char::isDigit).toLongOrNull() ?: 0L).toInt() and 0xFF
			val noiseLength = token.count(Char::isLetter)
			repeat(noiseLength) {
				if (offset < chars.size) {
					chars.removeAt(offset)
				}
			}
		}
		val encoded = String(chars.toCharArray()).trim()
		val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
		val decoded = try {
			String(context.decodeBase64(padded), Charsets.UTF_8)
		} catch (e: IllegalArgumentException) {
			throw ParseException("腾讯动漫：章节数据不是有效 base64", url, e)
		}
		return try {
			JSONObject(decoded)
		} catch (e: org.json.JSONException) {
			throw ParseException("腾讯动漫：章节数据不是有效 JSON", url, e)
		}
	}

	private fun coverUrl(img: Element?): String? {
		if (img == null) return null
		val raw = img.attr("data-original").trim().ifEmpty { img.attr("src").trim() }
		if (raw.isEmpty() || raw.endsWith("blank.gif")) return null
		return raw.toAbsoluteUrl(domain)
	}

	private val rootUrl: String
		get() = "https://$domain"

	private companion object {
		private const val THEME_PREFIX = "theme/"
		private const val FINISH_PREFIX = "finish/"
		private const val VIP_PREFIX = "vip/"
		private const val LOCK_PREFIX = "\uD83D\uDD12 "

		private val COMIC_ID_REGEX = Regex("/Comic/comicInfo/id/(\\d+)")
		private val DATA_REGEX = Regex("var DATA = '([^']*)'")
		private val NOISE_TOKEN_REGEX = Regex("\\d+[a-zA-Z]+")
		private val NONCE_ASSIGNMENT_REGEX = Regex("""window\[[^\]]{0,80}\]\s*=\s*([^;]{1,2000});""")

		private val GENRES = listOf(
			"恋爱" to "105",
			"玄幻" to "101",
			"异能" to "103",
			"恐怖" to "110",
			"剧情" to "106",
			"科幻" to "108",
			"悬疑" to "112",
			"奇幻" to "102",
			"冒险" to "104",
			"犯罪" to "111",
			"动作" to "109",
			"日常" to "113",
			"竞技" to "114",
			"武侠" to "115",
			"历史" to "116",
			"战争" to "117",
		)
	}
}

/**
 * 求值腾讯动漫 nonce 表达式的最小 JavaScript 子集求值器。
 *
 * 站点用来混淆的表达式形如
 * `"62264" + (+eval('1'.charCodeAt() - 45)).toString() + "7f" + (+eval("0<=3?2:1")).toString() + ...`，
 * 实测出现过的片段包括：数字/字符串字面量、`+ - * / %`、`!`（含 `!!`、`!!!`）、`~`（含 `~~`）、
 * `& | ^ << >>`、比较、三元、`Math.pow/round/...`、`'1'.charCodeAt()`、`'123'.substring(2)`，
 * 以及 `document.getElementsByTagName('html')`、`window.Array` 这类 DOM 探测（按真值对象处理）。
 *
 * 只做求值，不执行任何真实 JS；遇到不支持的语法抛 [IllegalArgumentException]，由调用方换下一个候选。
 * 独立出来是为了能被离线夹具直接测试。
 */
internal object TencentNonceEvaluator {

	fun evaluate(expression: String): String {
		require(expression.isNotBlank()) { "nonce 表达式为空" }
		return when (val value = NonceExpressionParser(expression).parse()) {
			is JsValue.Num -> formatNumber(value.value)
			is JsValue.Str -> value.value
			else -> throw IllegalArgumentException("nonce 表达式结果不是字符串或数字: ${value.toJsString()}")
		}
	}

	/**
	 * JS `Math.round` 等价于 `floor(x + 0.5)`（`.5` 一律向正无穷进位），
	 * 而 Kotlin 的 `round` 是银行家舍入，例如 `round(0.5) == 0.0`、`round(2.5) == 2.0`，直接用会算错 nonce。
	 */
	private fun jsRound(value: Double): Double = when {
		value.isNaN() || value.isInfinite() -> value
		else -> floor(value + 0.5)
	}

	private fun formatNumber(value: Double): String = when {
		value.isNaN() -> "NaN"
		value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
		value == floor(value) && abs(value) < 1e15 -> value.toLong().toString()
		else -> value.toString()
	}

	private sealed class JsValue {
		abstract val isTruthy: Boolean
		open fun toNumber(): Double = Double.NaN
		open fun toJsString(): String = ""

		class Num(val value: Double) : JsValue() {
			override val isTruthy: Boolean get() = value != 0.0 && !value.isNaN()
			override fun toNumber(): Double = value
			override fun toJsString(): String = formatNumber(value)
		}

		class Str(val value: String) : JsValue() {
			override val isTruthy: Boolean get() = value.isNotEmpty()
			override fun toNumber(): Double = when {
				value.isBlank() -> 0.0
				else -> value.trim().toDoubleOrNull() ?: Double.NaN
			}
			override fun toJsString(): String = value
		}

		/** 未知全局对象（document/window/...），JS 里恒为真值。 */
		class Obj(val name: String = "") : JsValue() {
			override val isTruthy: Boolean get() = true
			override fun toJsString(): String = "[object ${name.ifEmpty { "Object" }}]"
		}

		/** null / undefined。 */
		object Null : JsValue() {
			override val isTruthy: Boolean get() = false
			override fun toNumber(): Double = 0.0
			override fun toJsString(): String = "null"
		}
	}

	private class NonceExpressionParser(private val source: String) {
		private var position = 0

		fun parse(): JsValue {
			val value = parseTernary()
			skipSpaces()
			if (peek() == ';') {
				position++
			}
			skipSpaces()
			require(position == source.length) {
				"nonce 表达式无法解析: ${source.substring(position)}"
			}
			return value
		}

		private fun parseTernary(): JsValue {
			val condition = parseLogicalOr()
			skipSpaces()
			if (peek() != '?') {
				return condition
			}
			position++
			val whenTrue = parseTernary()
			skipSpaces()
			require(peek() == ':') { "三元表达式缺少 ':'" }
			position++
			val whenFalse = parseTernary()
			return if (condition.isTruthy) whenTrue else whenFalse
		}

		private fun parseLogicalOr(): JsValue {
			var value = parseLogicalAnd()
			while (true) {
				skipSpaces()
				if (!consume("||")) return value
				val right = parseLogicalAnd()
				value = if (value.isTruthy) value else right
			}
		}

		private fun parseLogicalAnd(): JsValue {
			var value = parseBitOr()
			while (true) {
				skipSpaces()
				if (!consume("&&")) return value
				val right = parseBitOr()
				value = if (!value.isTruthy) value else right
			}
		}

		private fun parseBitOr(): JsValue {
			var value = parseBitXor()
			while (true) {
				skipSpaces()
				if (peek() != '|' || startsWith("||")) return value
				position++
				value = JsValue.Num((toInt32(value) or toInt32(parseBitXor())).toDouble())
			}
		}

		private fun parseBitXor(): JsValue {
			var value = parseBitAnd()
			while (true) {
				skipSpaces()
				if (peek() != '^') return value
				position++
				value = JsValue.Num((toInt32(value) xor toInt32(parseBitAnd())).toDouble())
			}
		}

		private fun parseBitAnd(): JsValue {
			var value = parseEquality()
			while (true) {
				skipSpaces()
				if (peek() != '&' || startsWith("&&")) return value
				position++
				value = JsValue.Num((toInt32(value) and toInt32(parseEquality())).toDouble())
			}
		}

		private fun parseEquality(): JsValue {
			var value = parseRelational()
			while (true) {
				skipSpaces()
				value = when {
					consume("===") || consume("==") -> JsValue.Num(
						if (looseEquals(value, parseRelational())) 1.0 else 0.0,
					)
					consume("!==") || consume("!=") -> JsValue.Num(
						if (looseEquals(value, parseRelational())) 0.0 else 1.0,
					)
					else -> return value
				}
			}
		}

		private fun parseRelational(): JsValue {
			var value = parseShift()
			while (true) {
				skipSpaces()
				val result: Boolean = when {
					consume("<=") -> value.toNumber() <= parseShift().toNumber()
					consume(">=") -> value.toNumber() >= parseShift().toNumber()
					peek() == '<' && !startsWith("<<") -> {
						position++
						value.toNumber() < parseShift().toNumber()
					}
					peek() == '>' && !startsWith(">>") -> {
						position++
						value.toNumber() > parseShift().toNumber()
					}
					else -> return value
				}
				value = JsValue.Num(if (result) 1.0 else 0.0)
			}
		}

		private fun parseShift(): JsValue {
			var value = parseAdditive()
			while (true) {
				skipSpaces()
				value = when {
					consume(">>>") -> JsValue.Num(
						(toInt32(value) ushr toInt32(parseAdditive())).toDouble(),
					)
					consume(">>") -> JsValue.Num(
						(toInt32(value) shr toInt32(parseAdditive())).toDouble(),
					)
					consume("<<") -> JsValue.Num(
						(toInt32(value) shl toInt32(parseAdditive())).toDouble(),
					)
					else -> return value
				}
			}
		}

		private fun parseAdditive(): JsValue {
			var value = parseMultiplicative()
			while (true) {
				skipSpaces()
				value = when {
					peek() == '+' -> {
						position++
						val right = parseMultiplicative()
						if (value is JsValue.Str || right is JsValue.Str) {
							JsValue.Str(value.toJsString() + right.toJsString())
						} else {
							JsValue.Num(value.toNumber() + right.toNumber())
						}
					}
					peek() == '-' -> {
						position++
						JsValue.Num(value.toNumber() - parseMultiplicative().toNumber())
					}
					else -> return value
				}
			}
		}

		private fun parseMultiplicative(): JsValue {
			var value = parseUnary()
			while (true) {
				skipSpaces()
				value = when (peek()) {
					'*' -> {
						position++
						JsValue.Num(value.toNumber() * parseUnary().toNumber())
					}
					'/' -> {
						position++
						JsValue.Num(value.toNumber() / parseUnary().toNumber())
					}
					'%' -> {
						position++
						JsValue.Num(value.toNumber() % parseUnary().toNumber())
					}
					else -> return value
				}
			}
		}

		private fun parseUnary(): JsValue {
			skipSpaces()
			return when (peek()) {
				'!' -> {
					position++
					JsValue.Num(if (parseUnary().isTruthy) 0.0 else 1.0)
				}
				'~' -> {
					position++
					JsValue.Num((toInt32(parseUnary()).inv()).toDouble())
				}
				'+' -> {
					position++
					JsValue.Num(parseUnary().toNumber())
				}
				'-' -> {
					position++
					JsValue.Num(-parseUnary().toNumber())
				}
				else -> parsePostfix()
			}
		}

		private fun parsePostfix(): JsValue {
			var value = parsePrimary()
			while (true) {
				skipSpaces()
				when (peek()) {
					'.' -> {
						position++
						skipSpaces()
						val name = readIdentifier()
						skipSpaces()
						value = if (peek() == '(') {
							position++
							val args = parseArguments()
							callMethod(value, name, args)
						} else {
							member(value, name)
						}
					}
					'(' -> {
						position++
						parseArguments()
						value = JsValue.Obj("Function")
					}
					'[' -> {
						position++
						parseTernary()
						skipSpaces()
						require(peek() == ']') { "缺少 ']'" }
						position++
						value = JsValue.Obj("Element")
					}
					else -> return value
				}
			}
		}

		private fun parseArguments(): List<JsValue> {
			val args = mutableListOf<JsValue>()
			skipSpaces()
			if (peek() == ')') {
				position++
				return args
			}
			while (true) {
				args += parseTernary()
				skipSpaces()
				when (peek()) {
					',' -> {
						position++
						skipSpaces()
					}
					')' -> {
						position++
						return args
					}
					else -> throw IllegalArgumentException("函数参数缺少 ')' 或 ',': $source")
				}
			}
		}

		private fun parsePrimary(): JsValue {
			skipSpaces()
			val char = peek() ?: throw IllegalArgumentException("nonce 表达式意外结束: $source")
			return when {
				char == '(' -> {
					position++
					val value = parseTernary()
					skipSpaces()
					require(peek() == ')') { "缺少右括号: $source" }
					position++
					value
				}
				char == '"' || char == '\'' -> JsValue.Str(readString(char))
				char.isDigit() || char == '.' -> readNumber()
				char.isLetter() || char == '_' || char == '$' -> readIdentifierValue()
				else -> throw IllegalArgumentException("无法识别的字符 '$char': $source")
			}
		}

		private fun readString(quote: Char): String {
			position++
			val builder = StringBuilder()
			while (position < source.length) {
				val char = source[position++]
				when {
					char == quote -> return builder.toString()
					char == '\\' && position < source.length -> builder.append(
						when (val escaped = source[position++]) {
							'n' -> '\n'
							't' -> '\t'
							'r' -> '\r'
							else -> escaped
						},
					)
					else -> builder.append(char)
				}
			}
			throw IllegalArgumentException("字符串未闭合: $source")
		}

		private fun readNumber(): JsValue {
			val start = position
			if (startsWith("0x") || startsWith("0X")) {
				position += 2
				while (position < source.length && source[position].isLetterOrDigit()) {
					position++
				}
				val hex = source.substring(start + 2, position)
				return JsValue.Num(hex.toLongOrNull(16)?.toDouble() ?: Double.NaN)
			}
			while (position < source.length && (source[position].isDigit() || source[position] == '.')) {
				position++
			}
			if (position < source.length && (source[position] == 'e' || source[position] == 'E')) {
				val mark = position
				position++
				if (position < source.length && (source[position] == '+' || source[position] == '-')) {
					position++
				}
				try {
					while (position < source.length && source[position].isDigit()) {
						position++
					}
					source.substring(start, position).toDouble()
					return JsValue.Num(source.substring(start, position).toDouble())
				} catch (e: NumberFormatException) {
					position = mark
				}
			}
			val literal = source.substring(start, position)
			return JsValue.Num(
				literal.toDoubleOrNull() ?: throw IllegalArgumentException("无法解析数字 '$literal'"),
			)
		}

		private fun readIdentifier(): String {
			skipSpaces()
			val start = position
			while (position < source.length) {
				val char = source[position]
				if (!char.isLetterOrDigit() && char != '_' && char != '$') break
				position++
			}
			require(position > start) { "缺少标识符: $source" }
			return source.substring(start, position)
		}

		private fun readIdentifierValue(): JsValue = when (val name = readIdentifier()) {
			"true", "!0" -> JsValue.Num(1.0)
			"false" -> JsValue.Num(0.0)
			"null", "undefined" -> JsValue.Null
			"NaN" -> JsValue.Num(Double.NaN)
			"Infinity" -> JsValue.Num(Double.POSITIVE_INFINITY)
			else -> {
				skipSpaces()
				if (peek() != '(') {
					JsValue.Obj(name)
				} else {
					position++
					val args = parseArguments()
					when (name) {
						// eval 的实参本身就是被混淆的表达式，递归求值
						"eval" -> evaluateNested(args.firstOrNull()?.toJsString().orEmpty())
						"Number" -> JsValue.Num(args.firstOrNull()?.toNumber() ?: Double.NaN)
						"String" -> JsValue.Str(args.firstOrNull()?.toJsString().orEmpty())
						"parseInt" -> JsValue.Num(parseIntArg(args))
						"parseFloat" -> JsValue.Num(
							args.firstOrNull()?.toJsString()?.trim()?.toDoubleOrNull() ?: Double.NaN,
						)
						else -> JsValue.Obj("Function")
					}
				}
			}
		}

		private fun evaluateNested(expression: String): JsValue {
			require(expression.isNotBlank()) { "eval 的参数为空" }
			return NonceExpressionParser(expression).parse()
		}

		/** JS `parseInt`：先按 JS 规则转字符串，再取开头的整数前缀（支持可选进制）。 */
		private fun parseIntArg(args: List<JsValue>): Double {
			val text = args.firstOrNull()?.toJsString().orEmpty().trim()
			val radix = args.getOrNull(1)?.toNumber()?.toInt()?.takeIf { it in 2..36 } ?: 10
			val body = text.removePrefix("-").removePrefix("+")
			val digits = body.takeWhile { char ->
				when {
					char.isDigit() -> true
					radix > 10 -> char.lowercaseChar() in 'a'..('a' + radix - 11)
					else -> false
				}
			}
			if (digits.isEmpty()) return Double.NaN
			val value = digits.toLongOrNull(radix)?.toDouble() ?: Double.NaN
			return if (text.startsWith("-")) -value else value
		}

		private fun member(value: JsValue, name: String): JsValue = when (value) {
			is JsValue.Str -> if (name == "length") JsValue.Num(value.value.length.toDouble()) else JsValue.Obj(name)
			else -> JsValue.Obj(name)
		}

		private fun callMethod(value: JsValue, name: String, args: List<JsValue>): JsValue {
			val objectName = (value as? JsValue.Obj)?.name.orEmpty()
			if (objectName == "Math") {
				return callMath(name, args)
			}
			return when (value) {
				is JsValue.Str -> callStringMethod(value.value, name, args)
				is JsValue.Num -> when (name) {
					"toString" -> JsValue.Str(formatNumber(value.value))
					"toFixed" -> {
						val digits = args.firstOrNull()?.toNumber()?.toInt() ?: 0
						JsValue.Str(String.format(java.util.Locale.ROOT, "%.${digits}f", value.value))
					}
					else -> throw IllegalArgumentException("不支持的数值方法 $name")
				}
				else -> when (name) {
					"toString" -> JsValue.Str(value.toJsString())
					else -> JsValue.Obj(name)
				}
			}
		}

		private fun callStringMethod(text: String, name: String, args: List<JsValue>): JsValue = when (name) {
			"toString", "trim", "trimStart", "trimEnd" -> JsValue.Str(
				when (name) {
					"trim" -> text.trim()
					"trimStart" -> text.trimStart()
					"trimEnd" -> text.trimEnd()
					else -> text
				},
			)
			"charCodeAt" -> {
				val index = args.firstOrNull()?.toNumber()?.toInt() ?: 0
				JsValue.Num(if (index in text.indices) text[index].code.toDouble() else Double.NaN)
			}
			"charAt" -> {
				val index = args.firstOrNull()?.toNumber()?.toInt() ?: 0
				JsValue.Str(if (index in text.indices) text[index].toString() else "")
			}
			"substring", "substr", "slice" -> JsValue.Str(sliceString(text, name, args))
			"toUpperCase" -> JsValue.Str(text.uppercase())
			"toLowerCase" -> JsValue.Str(text.lowercase())
			"indexOf" -> JsValue.Num(text.indexOf(args.firstOrNull()?.toJsString().orEmpty()).toDouble())
			"replace" -> {
				val target = args.firstOrNull()?.toJsString().orEmpty()
				val replacement = args.getOrNull(1)?.toJsString().orEmpty()
				JsValue.Str(if (target.isEmpty()) text else text.replaceFirst(target, replacement))
			}
			else -> throw IllegalArgumentException("不支持的字符串方法 $name")
		}

		private fun sliceString(text: String, name: String, args: List<JsValue>): String {
			val length = text.length
			fun clamp(index: Int) = index.coerceIn(0, length)
			val first = args.firstOrNull()?.toNumber()?.toInt() ?: 0
			return when (name) {
				"substring" -> {
					val start = clamp(first)
					val end = clamp(args.getOrNull(1)?.toNumber()?.toInt() ?: length)
					text.substring(minOf(start, end), maxOf(start, end))
				}
				"slice" -> {
					val start = clamp(if (first < 0) length + first else first)
					val rawEnd = args.getOrNull(1)?.toNumber()?.toInt() ?: length
					val end = clamp(if (rawEnd < 0) length + rawEnd else rawEnd)
					if (end <= start) "" else text.substring(start, end)
				}
				else -> {
					val start = clamp(first)
					val count = args.getOrNull(1)?.toNumber()?.toInt() ?: (length - start)
					text.substring(start, clamp(start + count.coerceAtLeast(0)))
				}
			}
		}

		private fun callMath(name: String, args: List<JsValue>): JsValue {
			val first = args.firstOrNull()?.toNumber() ?: Double.NaN
			val second = args.getOrNull(1)?.toNumber() ?: Double.NaN
			return when (name) {
				"pow" -> JsValue.Num(Math.pow(first, second))
				"floor" -> JsValue.Num(floor(first))
				"ceil" -> JsValue.Num(ceil(first))
				// JS Math.round 是四舍五入（.5 进位），Kotlin 的 round 是银行家舍入，二者不同
				"round" -> JsValue.Num(jsRound(first))
				"abs" -> JsValue.Num(abs(first))
				"sqrt" -> JsValue.Num(sqrt(first))
				"trunc" -> JsValue.Num(if (first < 0) ceil(first) else floor(first))
				"sign" -> JsValue.Num(Math.signum(first))
				"max" -> JsValue.Num(maxOf(first, second))
				"min" -> JsValue.Num(minOf(first, second))
				"random" -> JsValue.Num(0.5)
				else -> throw IllegalArgumentException("不支持的 Math 方法 $name")
			}
		}

		private fun looseEquals(left: JsValue, right: JsValue): Boolean = when {
			left is JsValue.Str && right is JsValue.Str -> left.value == right.value
			left is JsValue.Null && right is JsValue.Null -> true
			left is JsValue.Obj || right is JsValue.Obj -> left === right
			else -> left.toNumber() == right.toNumber()
		}

		private fun toInt32(value: JsValue): Int {
			val number = value.toNumber()
			return if (number.isNaN() || number.isInfinite()) 0 else number.toLong().toInt()
		}

		private fun startsWith(token: String): Boolean = source.startsWith(token, position)

		private fun consume(token: String): Boolean {
			if (!startsWith(token)) return false
			position += token.length
			return true
		}

		private fun peek(): Char? = if (position < source.length) source[position] else null

		private fun skipSpaces() {
			while (position < source.length && source[position].isWhitespace()) {
				position++
			}
		}
	}
}
