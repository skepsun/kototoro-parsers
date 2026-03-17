package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.YEAR_UNKNOWN
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.FavoritesSyncProvider
import org.skepsun.kototoro.parsers.util.attrOrThrow
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.json.asTypedList
import org.skepsun.kototoro.parsers.util.mapChapters
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.oneOrThrowIfMany
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.selectFirstOrThrow
import org.skepsun.kototoro.parsers.util.selectOrThrow
import org.skepsun.kototoro.parsers.util.src
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.skepsun.kototoro.parsers.util.getCookies
import java.util.EnumSet
import java.util.Locale

@ContentSourceParser("MANHUAGUI", "漫画柜", "zh")
internal class ManhuaguiParser(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.MANHUAGUI, pageSize = 42),
	ContentParserAuthProvider,
	ContentParserCredentialsAuthProvider,
    FavoritesProvider,
    FavoritesSyncProvider {

	override val configKeyDomain = ConfigKey.Domain("www.manhuagui.com")
    	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	val configKeyImgServer = ConfigKey.PreferredImageServer(
		presetValues = arrayOf("us", "us2", "us3", "eu", "eu2", "eu3").associateWith { it },
		defaultValue = "us",
	)

	val imgServer = "${config[configKeyImgServer]}.hamreus.com"

    	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(configKeyImgServer)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain")
		.add("Accept-Encoding", "identity")
		.build()

    	override val defaultSortOrder: SortOrder
		get() = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
			SortOrder.NEWEST, // 最新发布
			SortOrder.UPDATED, // 最新更新
			SortOrder.POPULARITY, // 人气最旺
			SortOrder.RATING, // 评分最高
		)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
			isYearSupported = true,
			isOriginalLocaleSupported = true,
		)

    	private val fetchedTags = suspendLazy(initializer = ::fetchAvailableTags)

    	override suspend fun getFilterOptions() = ContentListFilterOptions(
		availableLocales = setOf(
			Locale.JAPAN, Locale.TRADITIONAL_CHINESE, Locale.ROOT,
			Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.KOREA,
		),
		availableTags = fetchedTags.get(),
		availableDemographics = EnumSet.complementOf(EnumSet.of(Demographic.JOSEI)),
		availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
	)

	private val listUrl = "/list"
	private val searchUrl = "/s"
	private val ratingUrl = "/tools/vote.ashx"
	private val sectionChaptersSelector = ".chapter-list"

	private fun Any?.toQueryParam(): String? = when (this) {

		is String -> urlEncoded() // Title

		is Locale -> when (this) {
			Locale.JAPAN -> "japan" // 日本
			Locale.TRADITIONAL_CHINESE -> "hongkong" // 港台
			Locale.ROOT -> "other" // 其他 (I do not know if it is sure to use it)
			Locale.US -> "europe" // 欧美
			Locale.SIMPLIFIED_CHINESE -> "china" // 内地
			Locale.KOREA -> "korea" // 韩国
			else -> null
		}

		is ContentTag -> key

		is Demographic -> when (this) {
			Demographic.SHOUJO -> "shaonv" // 少女
			Demographic.SHOUNEN -> "shaonian" // 少年
			Demographic.SEINEN -> "qingnian" // 青年
			Demographic.KODOMO -> "ertong" // 儿童
			Demographic.NONE -> "tongyong" // 通用
			else -> null
		}

		is Int -> when { // Year
			this >= 2010 && this <= 2025 -> toString()
			this >= 2000 && this < 2010 -> "200x"
			this >= 1990 && this < 2000 -> "199x"
			this >= 1980 && this < 1990 -> "198x"
			this == YEAR_UNKNOWN -> null
			this < 1980 -> "197x"
			else -> null
		}

		is ContentState -> when (this) {
			ContentState.ONGOING -> "lianzai" // 连载
			ContentState.FINISHED -> "wanjie" // 完结
			else -> null
		}

		else -> null
	}

	private fun String.addQueryParameters(params: JSONObject): String {
		val builder = this.toHttpUrl().newBuilder()
		val keys = params.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			val value = params.get(key).toString()
			builder.addQueryParameter(key, value)
		}
		return builder.build().toString()
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: ContentListFilter,
	): List<Content> {
        
        // Flag of whether there is title query param
		var flagHasTitleQuery = false 

		val url = buildString {
			var queryFull: String?
			var orderStr: String?
			var pageStr: String?
			if (filter.query == null) {
				append(listUrl.toAbsoluteUrl(domain))
				queryFull = listOfNotNull(
					filter.locale,
					filter.tags.oneOrThrowIfMany(),
					filter.demographics.oneOrThrowIfMany(),
					filter.year.takeUnless { it == YEAR_UNKNOWN },
					filter.states.oneOrThrowIfMany(),
				).joinToString("_") { it.toQueryParam().toString() }
				orderStr = when (order) {
					SortOrder.UPDATED -> "update"
					SortOrder.POPULARITY -> "view"
					SortOrder.RATING -> "rate"
					else -> "index"
				}
				pageStr = "/${orderStr}_p${page}.html"
			} else {
				flagHasTitleQuery = true
				append(searchUrl.toAbsoluteUrl(domain))
				queryFull = filter.query
				orderStr = when (order) {
					SortOrder.POPULARITY -> "_o1"
					SortOrder.NEWEST -> "_o2"
					SortOrder.RATING -> "_o3"
					else -> ""
				}
				pageStr = "${orderStr}_p${page}.html"
			}

			append("/${queryFull}${pageStr}")
		}

		val doc = webClient.httpGet(url.toHttpUrl()).parseHtml()

		return doc.select(if (flagHasTitleQuery) "div.book-result > ul > li" else "div.book-list > ul#contList > li")
			.map { li ->
				val a = li.selectFirstOrThrow("a")
				val em =
					li.selectFirst(if (flagHasTitleQuery) "div.book-score > p:first-child > strong" else "span.updateon > em")
				val href = a.attrOrThrow("href")
				val rating = em?.text()?.toFloat()?.div(10) ?: RATING_UNKNOWN
				Content(
					id = generateUid(href),
					title = a.attr("title"),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = rating,
					contentRating = null,
					coverUrl = a.selectFirst("img")?.src(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
	}

	override suspend fun getDetails(manga: Content): Content {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()

		val altTitles = doc.select(".book-title h2").eachText().toSet()
		val contentRating: ContentRating = doc.selectFirst("input#__VIEWSTATE").let {
			when (it) {
				null -> ContentRating.SAFE
				else -> ContentRating.ADULT
			}
		}
		val tags = doc.select("ul.detail-list > li:nth-child(2) > span:first-child > a").mapToSet { e ->
			ContentTag(
				title = e.text(),
				key = e.attr("href").removePrefix(listUrl).removeSurrounding("/"),
				source = source,
			)
		}
		val state = doc.selectFirst("li.status > span > span")?.className()?.let { className ->
			when (className) {
				"red" -> ContentState.ONGOING
				"dgreen" -> ContentState.FINISHED
				else -> null
			}
		}
		val authors = doc.select("a[href^=\"/author\"]").eachText().toSet()
		val description = doc.selectFirst("div.book-intro > #intro-all > p")?.text()
		val chapters = parseChapters(doc)

		return manga.copy(
			altTitles = altTitles,
			contentRating = contentRating,
			tags = tags,
			state = state,
			authors = authors,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val chapUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapUrl).parseHtml()
		val regex = Regex("""^.*\}\('(.*)',(\d*),(\d*),'([\w\+/=]*)'.*${'$'}""", RegexOption.MULTILINE)
		val result = regex.find(doc.html()) ?: throw ParseException("Cannot find chapter metadata", chapUrl)
		val metadataRaw = decompressLZStringFromBase64(result.groupValues[4]) ?: throw ParseException(
			"Cannot decompress chapter metadata",
			chapUrl,
		)
		val json = unpack(result.groupValues[1], metadataRaw.split("|"))

		val files = json.getJSONArray("files")
		val semiFullUrl = json.getString("path").toAbsoluteUrl(imgServer)
		val signature = json.getJSONObject("sl")

		return files.asTypedList<String>().map { it ->
			val fullUrl = (semiFullUrl + it).addQueryParameters(signature)
			ContentPage(
				id = generateUid(fullUrl),
				url = fullUrl,
				preview = fullUrl,
				source = source,
			)
		}
	}

    	// private funs

    	private suspend fun fetchAvailableTags(): Set<ContentTag> {
		val doc = webClient.httpGet(listUrl.toAbsoluteUrl(domain)).parseHtml()
		val tags = doc.selectOrThrow("div.filter-nav > .filter.genre > ul > li > a").drop(1)
		return tags.mapToSet { a ->
			val title = a.text()
			val key = a.attr("href").removePrefix(listUrl).removeSurrounding("/")
			ContentTag(
				title = title,
				key = key,
				source = source,
			)
		}
	}

	private fun parseChapters(doc: Document, url: String? = null): List<ContentChapter> {
		val (sectionTitles, sectionChapters) = doc.selectFirst("#__VIEWSTATE").let {
			if (it != null) {
				val viewStateStr = decompressLZStringFromBase64(it.attrOrThrow("value"))
					?: throw ParseException("Cannot decompress __VIEWSTATE", url.ifNullOrEmpty { "" })
				val doc1 = Jsoup.parse(viewStateStr)
				Pair(
					doc1.select("h4 span"),
					doc1.select(sectionChaptersSelector),
				)
			} else {
				Pair(
					doc.select(".chapter h4 span"),
					doc.select(sectionChaptersSelector),
				)
			}
		}

		// Parse chapters from each section
		val chapters = sectionTitles
			.zip(sectionChapters)
			.flatMapIndexed { index, (title, section) ->
				val chaps = section.select("ul").flatMap {
					it.select("li a").asReversed()
				}
				chaps.mapChapters { chapIdx, chap ->
					ContentChapter(
						url = chap.attrOrThrow("href"),
						id = generateUid(chap.attrOrThrow("href")),
						title = chap.attrOrThrow("title"),
						number = (chapIdx + 1).toFloat(),
						volume = index + 1,
						scanlator = null,
						uploadDate = 0,
						branch = title.text(),
						source = source,
					)
				}
			}

		return chapters
	}

    	private fun decompressLZStringFromBase64(input: String): String? {
	        if (input.isBlank()) return null
	
	        data class Data(var value: Char = '0', var position: Int = 0, var index: Int = 1)

			fun Int.power() = 1 shl this
			fun Int.string() = this.toChar().toString()
	
	        val keyStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
	        val getNextValue = { it: Int -> keyStr.indexOf(input[it]).toChar() }
	        val builder = StringBuilder()
	        val dictionary = mutableListOf(0.string(), 1.string(), 2.string())
	        val data = Data(getNextValue(0), 32, 1)
	        var (next, bits, numBits, enlargeIn, dictSize) = listOf(0, 0, 3, 4, 4)
	        var (c, w, entry) = listOf("", "", "")
	        
	        fun doPower(initBits: Int, initPower: Int, initMaxPower: Int, mode: Int = 0) {
	            bits = initBits
	            var power = initPower
	            val maxpower = initMaxPower.power()
	            while (power != maxpower) {
	                val resb = data.value.code and data.position
	                data.position = data.position shr 1
	                if (data.position == 0) {
	                    data.position = 32
	                    data.value = getNextValue(data.index++)
	                }
	                bits = bits or ((if (resb > 0) 1 else 0) * power)
	                power = power shl 1
	            }
	            when (mode) {
	                1 -> c = bits.string()
	                2 -> dictionary.add(dictSize++.also {
						enlargeIn--
						next = dictSize - 1
					}, bits.string())
	            }
	        }
	
	        fun checkEnlargeIn() {
	            if (enlargeIn == 0) {
	                enlargeIn = numBits.power()
	                numBits++
	            }
	        }
	
	        doPower(bits, 1, 2)
			next = bits
	        when (next) {
	            0 -> doPower(0, 1, 8, 1)
	            1 -> doPower(0, 1, 16, 1)
	            2 -> return ""
	        }
	        
	        dictionary.add(3, c)
	        w = c
	        builder.append(w)
	        
	        while (true) {
	            if (data.index > input.length) return ""
	            doPower(0, 1, numBits)
				next = bits
	            when (next) {
	                0 -> doPower(0, 1, 8, 2).also { checkEnlargeIn() }
	                1 -> doPower(0, 1, 16, 2).also { checkEnlargeIn() }
	                2 -> return builder.toString()
	            }
	            entry = when {
	                dictionary.size > next -> dictionary[next]
	                next == dictSize -> w + w[0]
	                else -> return null
	            }
	            builder.append(entry)
	            dictionary.add(dictSize++, w + entry[0]).also { enlargeIn-- }
	            w = entry
	            checkEnlargeIn()
        	}
    	}

	private fun unpack(src: String, syms: List<String>): JSONObject {
	        fun base62(n: Int) = when {
	            n < 10 -> n.toString()
	            n < 36 -> ('a' + (n - 10)).toString()
	            else -> ('A' + (n - 36)).toString()
	        }
	        
	        fun encode62(num: Int): String = if (num >= 62) encode62(num / 62) + base62(num % 62) else base62(num)
	
	        val working = syms.foldRightIndexed(src) { idx, replacement, acc ->
	            if (replacement.isNotEmpty()) {
	                val token = encode62(idx)
	                Regex("\\b${Regex.escape(token)}\\b").replace(acc, replacement)
	            } else acc
	        }
	
	        return JSONObject(Regex("""\((\{.+\})\)""", RegexOption.DOT_MATCHES_ALL)
	            .find(working)?.groupValues?.get(1)
	            ?: throw IllegalArgumentException("JSON payload not found after unpacking."))
	    }

	override val authUrl: String = "https://www.manhuagui.com/login.html"

	override suspend fun isAuthorized(): Boolean {
		return context.cookieJar.getCookies("www.manhuagui.com").any { it.name == "my" }
	}

	override suspend fun getUsername(): String {
		val cookies = context.cookieJar.getCookies("www.manhuagui.com")
		val userInfo = cookies.find { it.name == "my" }?.value ?: throw AuthRequiredException(source)
		// Extract nickname or username from cookie value if possible, otherwise return a placeholder
		return userInfo.substringAfter("n=").substringBefore("&").ifEmpty { "User" }
	}

	override suspend fun login(username: String, password: String): Boolean {
		val url = "https://www.manhuagui.com/tools/submit_ajax.ashx?action=user_login"
		val body = mapOf(
			"txtUserName" to username,
			"txtPassword" to password,
			"chkb_rem" to "1",
		)

		val response = try {
			webClient.httpPost(
				url.toHttpUrl(),
				body,
				extraHeaders = getRequestHeaders().newBuilder()
					.add("Referer", "https://www.manhuagui.com/login.html")
					.add("X-Requested-With", "XMLHttpRequest")
					.build(),
			)
		} catch (e: Exception) {
			return false
		}
		return isAuthorized()
	}

	override suspend fun fetchFavorites(): List<Content> {
		if (!isAuthorized()) throw AuthRequiredException(source)
		val headers = getRequestHeaders().newBuilder()
			.add("Cookie", context.cookieJar.getCookies(domain).joinToString("; ") { "${it.name}=${it.value}" })
			.build()
		val result = mutableListOf<Content>()
		var page = 1
		while (true) {
			val url = "https://$domain/user/book/shelf/$page"
			val resp = webClient.httpGet(url, headers)
			if (resp.code == 401) throw AuthRequiredException(source)
			if (!resp.isSuccessful) break
			val doc = resp.parseHtml()
			val items = doc.select(".dy_content_li")
			if (items.isEmpty()) break
			for (el in items) {
				val a = el.selectFirst(".dy_img a") ?: continue
				val href = a.attr("href")
				val id = href.substringAfterLast("/comic/").substringBefore("/")
				val img = a.selectFirst("img")
				var cover = img?.attr("src").orEmpty().ifEmpty { img?.attr("data-src").orEmpty() }
				if (cover.isNotEmpty() && !cover.startsWith("http")) cover = "https:$cover"
				val title = el.selectFirst(".dy_r h3")?.text().orEmpty()
				val hrefRel = "/comic/$id/"
				if (id.isNotEmpty()) {
					result.add(
						Content(
							id = generateUid(hrefRel),
							url = hrefRel,
							publicUrl = "https://$domain$hrefRel",
							coverUrl = cover,
							title = title,
							altTitles = emptySet(),
							rating = RATING_UNKNOWN,
							tags = emptySet(),
							authors = emptySet(),
							state = null,
							source = source,
							contentRating = ContentRating.SAFE,
						)
					)
				}
			}
			val next = doc.selectFirst("a.next")
			if (next == null) break
			page++
		}
		return result
	}

	override suspend fun addFavorite(manga: Content): Boolean {
		if (!isAuthorized()) throw AuthRequiredException(source)
		val headers = getRequestHeaders().newBuilder()
			.add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
			.add("X-Requested-With", "XMLHttpRequest")
			.add("Cookie", context.cookieJar.getCookies(domain).joinToString("; ") { "${it.name}=${it.value}" })
			.add("Referer", "https://$domain/comic/${manga.url.substringAfterLast('/')}/")
			.build()
		val id = manga.url.substringAfterLast('/').ifEmpty { manga.url }
		val body = "book_id=$id"
		val url = "https://$domain/tools/submit_ajax.ashx?action=user_book_shelf_add"
		val resp = webClient.httpPost(url.toHttpUrl(), body, headers)
		if (resp.code == 401) throw AuthRequiredException(source)
		return resp.isSuccessful
	}

	override suspend fun removeFavorite(manga: Content): Boolean {
		throw ParseException("漫画柜暂不支持取消收藏", "https://$domain/")
	}
}
