package org.skepsun.kototoro.parsers.site.zh

import androidx.collection.ArrayMap
import okhttp3.Headers
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.FavoritesSyncProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.*
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies
import org.skepsun.kototoro.parsers.util.parseJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.MultipartBody
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy
import java.util.*

@ContentSourceParser("BAOZIMH", "包子漫画", "zh")
internal class Baozimh(context: ContentLoaderContext) :
	PagedContentParser(context, ContentParserSource.BAOZIMH, pageSize = 36),
	ContentParserAuthProvider,
	ContentParserCredentialsAuthProvider,
	FavoritesProvider,
	FavoritesSyncProvider {

	override val configKeyDomain = ConfigKey.Domain(
		"bzmgcn.com",
		"baozimhcn.com",
		"webmota.com",
		"kukuc.co",
		"twmanga.com",
		"dinnerku.com",
		"baozimh.com",
	)

	private val lang: String get() = "cn"

	private val baseUrl: String get() {
		val d = super.domain.removePrefix("www.")
		return if (d.startsWith("cn.") || d.startsWith("tw.")) d else "$lang.$d"
	}

	override val userAgentKey = ConfigKey.UserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Sec-CH-UA", "\"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"")
		.add("Sec-CH-UA-Mobile", "?0")
		.add("Sec-CH-UA-Platform", "\"macOS\"")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = ContentListFilterOptions(
		availableTags = tagsMap.get().values.toSet(),
		availableStates = EnumSet.of(ContentState.ONGOING, ContentState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	private val tagsMap = suspendLazy(initializer = ::parseTags)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		when {
			!filter.query.isNullOrEmpty() -> {
				if (page > 1) return emptyList()
				val url = buildString {
					append("https://")
					append(baseUrl)
					append("/search?q=")
					append(filter.query.urlEncoded())
				}
				val response = webClient.httpGet(url)
				if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
					context.requestBrowserAction(this, url)
				}
				return parseContentListSearch(response.parseHtml())
			}

			else -> {
				val url = buildString {
					append("https://")
					append(baseUrl)
					append("/api/bzmhq/amp_comic_list?filter=*&region=")

					if (filter.types.isNotEmpty()) {
						filter.types.oneOrThrowIfMany().let {
							append(
								when (it) {
									ContentType.MANGA -> "jp"
									ContentType.MANHWA -> "kr"
									ContentType.MANHUA -> "cn"
									ContentType.COMICS -> "en"
									else -> "all"
								},
							)
						}
					} else append("all")


					append("&type=")
					if (filter.tags.isNotEmpty()) {
						filter.tags.oneOrThrowIfMany()?.let {
							append(it.key)
						}
					} else append("all")

					append("&state=")
					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let {
							append(
								when (it) {
									ContentState.ONGOING -> "serial"
									ContentState.FINISHED -> "pub"
									else -> "all"
								},
							)
						}
					} else append("all")

					append("&limit=36&page=")
					append(page.toString())
				}

				val response = webClient.httpGet(url)
				if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
					context.requestBrowserAction(this, url)
				}
				return parseContentList(response.parseJson().getJSONArray("items"))
			}
		}
	}

	private fun parseContentList(json: JSONArray): List<Content> {
		return json.mapJSON { j ->
			val href = "https://$baseUrl/comic/" + j.getString("comic_id")
			val author = j.getString("author")
			Content(
				id = generateUid(href),
				url = href,
				publicUrl = href,
				coverUrl = "https://static-tw.baozimh.com/cover/" + j.getString("topic_img"),
				title = j.getString("name"),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = setOfNotNull(author),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private fun parseContentListSearch(doc: Document): List<Content> {
		return doc.select("div.comics-card").map { div ->
			val href = "https://$baseUrl" + div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Content(
				id = generateUid(href),
				url = href,
				publicUrl = href,
				coverUrl = div.selectFirst("amp-img")?.src().orEmpty(),
				title = div.selectFirst(".comics-card__title h3")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private suspend fun parseTags(): Map<String, ContentTag> {
		val doc = try {
			webClient.httpGet("https://$baseUrl/classify").parseHtml()
		} catch (e: Exception) {
			null
		}
		
		val navs = doc?.select("div.nav")
		val tagElements = if (navs != null && navs.size > 3) {
			navs[3].select("a.item:not(.active)")
		} else null

		if (tagElements.isNullOrEmpty()) {
			// fallback tags from baozi.js
			val fallbackTags = listOf(
				"恋爱" to "lianai", "纯爱" to "chunai", "古风" to "gufeng", "异能" to "yineng",
				"悬疑" to "xuanyi", "剧情" to "juqing", "科幻" to "kehuan", "奇幻" to "qihuan",
				"玄幻" to "xuanhuan", "穿越" to "chuanyue", "冒险" to "mouxian", "推理" to "tuili",
				"武侠" to "wuxia", "格斗" to "gedou", "战争" to "zhanzheng", "热血" to "rexie",
				"搞笑" to "gaoxiao", "大女主" to "danuzhu", "都市" to "dushi", "总裁" to "zongcai",
				"后宫" to "hougong", "日常" to "richang", "韩漫" to "hanman", "少年" to "shaonian",
				"其它" to "qita"
			)
			return fallbackTags.associate { (title, key) ->
				title to ContentTag(key = key, title = title, source = source)
			}
		}

		val tagMap = ArrayMap<String, ContentTag>(tagElements.size)
		for (el in tagElements) {
			val name = el.text().trim()
			if (name.isEmpty()) continue
			tagMap[name] = ContentTag(
				key = el.attr("href").substringAfter("type=").substringBefore("&"),
				title = name,
				source = source,
			)
		}
		return tagMap
	}

	override suspend fun getDetails(manga: Content): Content {
		val url = manga.url.toAbsoluteUrl(baseUrl)
		val response = webClient.httpGet(url)
		if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
			context.requestBrowserAction(this, url)
		}
		val doc = response.parseHtml()
		val state = doc.selectFirst(".tag-list span.tag")?.text()
		val tagMap = tagsMap.get()
		val selectTag = doc.select(".tag-list span.tag").drop(1)
		val tags = selectTag.mapNotNullToSet { tagMap[it.text()] }
		val chapterElements = doc.select("#chapter-items .comics-chapters a, #chapters_other_list .comics-chapters a")
		println("BAOZI DEBUG: url=$url, code=${response.code}, chapterElements size=${chapterElements.size}")
		
		val (chapters, chaptersReversed) = if (chapterElements.isNotEmpty()) {
			chapterElements to false
		} else {
			val fallback = doc.select(".comics-chapters a, .comics-chapters__item")
			println("BAOZI DEBUG: fallback size=${fallback.size}")
			if (fallback.isEmpty()) {
				println("BAOZI DEBUG: title=${doc.title()}, html snippet=${doc.body().html().take(500)}")
			}
			// fallback for newer site structure or "latest chapters" only view
			fallback to true
		}
		println("BAOZI DEBUG: final chapters size=${chapters.size}, reversed=$chaptersReversed")
		return manga.copy(
			description = doc.selectFirst(".comics-detail__desc")?.text().orEmpty(),
			state = when (state) {
				"連載中" -> ContentState.ONGOING
				"已完結" -> ContentState.FINISHED
				else -> null
			},
			tags = tags,
			chapters = chapters.mapChapters(chaptersReversed) { i, a ->
				val url = a.attrAsRelativeUrl("href").toAbsoluteUrl(baseUrl)
				ContentChapter(
					id = generateUid(url),
					title = a.selectFirst("span")?.textOrNull(),
					number = i + 1f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = 0,
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		// 使用 App 版链接获取内容，更高效且包含原图
		// 格式类似：https://appcn.baozimh.com/baozimhapp/comic/chapter/{comicId}/{section}_{chapter}.html
		val comicId = when {
			chapter.url.contains("/comic/") -> {
				chapter.url.substringAfterLast("/comic/").substringBefore("/")
			}
			else -> {
				chapter.url.substringAfter("comic_id=").substringBefore("&")
			}
		}.ifEmpty { throw ParseException("缺少 comic_id", chapter.url) }

		val sectionSlot = chapter.url.substringAfter("section_slot=", "").substringBefore("&").ifEmpty { "0" }
		val chapterSlot = chapter.url.substringAfter("chapter_slot=", "").substringBefore("&").ifEmpty {
			// 兼容旧格式：/0_{epId}.html
			chapter.url.substringAfterLast("/").substringBefore(".html").removePrefix("0_")
		}
		val epId = "${sectionSlot}_${chapterSlot}"

		val appUrl = "https://appcn.baozimh.com/baozimhapp/comic/chapter/$comicId/$epId.html"

		val response = webClient.httpGet(
			url = appUrl,
			extraHeaders = Headers.headersOf(
				"Referer", "https://$baseUrl/",
				"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
				"Sec-CH-UA", "\"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"",
				"Sec-CH-UA-Mobile", "?0",
				"Sec-CH-UA-Platform", "\"macOS\"",
				"Host", "appcn.baozimh.com"
			)
		)
		if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
			context.requestBrowserAction(this, appUrl)
		}
		val doc = response.parseHtml()
		val imageNodes = doc.select(".comic-contain .chapter-img")
		
		return imageNodes.mapNotNull { node ->
			val imgUrl = node.selectFirst(".comic-contain__item")?.attrOrNull("data-src") ?: return@mapNotNull null
			
			// 1. 替换 /w640/ 为 / 以获取原图
			var processedUrl = imgUrl.replace("/w640/", "/")
			// 2. 补全协议
			if (processedUrl.startsWith("//")) {
				processedUrl = "https:$processedUrl"
			} else if (!processedUrl.startsWith("http")) {
				processedUrl = "https://$baseUrl$processedUrl"
			}
			
			ContentPage(
				id = generateUid(processedUrl),
				url = processedUrl,
				preview = null,
				source = source,
			)
		}
	}

	override val authUrl: String = "https://$baseUrl/user/login"

	override suspend fun isAuthorized(): Boolean {
		return context.cookieJar.getCookies(baseUrl).any { it.name == "TSID" }
	}

	override suspend fun getUsername(): String {
		if (!isAuthorized()) throw AuthRequiredException(source)
		return "User"
	}

	override suspend fun login(username: String, password: String): Boolean {
		val url = "https://$baseUrl/api/bui/signin"
		val body = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("username", username)
			.addFormDataPart("password", password)
			.build()
		
		val request = Request.Builder()
			.url(url)
			.post(body)
			.headers(getRequestHeaders())
			.build()

		val response = try {
			context.httpClient.newCall(request).await()
		} catch (e: Exception) {
			return false
		}
		
		val json = response.parseJson()
		val token = json.optString("data")
		if (!token.isNullOrEmpty()) {
			context.cookieJar.insertCookies(baseUrl, "TSID=$token; Domain=$baseUrl; Path=/; HttpOnly")
			return true
		}
		return false
	}

	override suspend fun fetchFavorites(): List<Content> {
		if (!isAuthorized()) throw AuthRequiredException(source)
		val headers = Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("referer", "https://$baseUrl/")
			.build()
		val url = "https://$baseUrl/user/my_bookshelf"
		val resp = webClient.httpGet(url, headers)
		if (resp.code == 401) throw AuthRequiredException(source)
		if (!resp.isSuccessful) return emptyList()
		val doc = resp.parseHtml()
		val items = doc.select("div.bookshelf-items")
		if (items.isEmpty()) return emptyList()
		return items.mapNotNull { el ->
			val link = el.selectFirst("h4 > a") ?: return@mapNotNull null
			val href = link.attr("href")
			val id = href.substringAfterLast('/').substringBefore('?').substringBefore("#").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val title = link.text().trim()
			val infoList = el.selectFirst("div.info > ul")
			val author = infoList?.children()?.getOrNull(1)?.text()?.substringAfter("：")?.trim().orEmpty()
			val desc = infoList?.children()?.getOrNull(4)?.children()?.firstOrNull()?.text()?.trim().orEmpty()
			val cover = el.selectFirst("amp-img")?.attr("src")
				?: el.selectFirst("img")?.let { img ->
					img.attr("data-src").ifEmpty { img.attr("src") }
				}
			val absCover = cover?.let { if (it.startsWith("http")) it else "https:$it" }
			Content(
				id = generateUid(id),
				url = "/comic/$id",
				publicUrl = "https://$baseUrl/comic/$id",
				coverUrl = absCover,
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
				state = null,
				description = desc.ifEmpty { null },
				source = source,
				contentRating = ContentRating.SAFE,
			)
		}
	}

	override suspend fun addFavorite(manga: Content): Boolean {
		if (!isAuthorized()) throw AuthRequiredException(source)
		val headers = Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("referer", "https://$baseUrl/")
			.build()
		val url = "https://$baseUrl/user/operation_v2?op=set_bookmark&comic_id=${manga.url}&chapter_slot=0"
		val resp = webClient.httpPost(url.toHttpUrl(), emptyMap<String, String>(), headers)
		if (resp.code == 401) throw AuthRequiredException(source)
		return resp.isSuccessful
	}

	override suspend fun removeFavorite(manga: Content): Boolean {
		if (!isAuthorized()) throw AuthRequiredException(source)
		val headers = Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("referer", "https://$baseUrl/")
			.build()
		val url = "https://$baseUrl/user/operation_v2?op=del_bookmark&comic_id=${manga.url}"
		val resp = webClient.httpPost(url.toHttpUrl(), emptyMap<String, String>(), headers)
		if (resp.code == 401) throw AuthRequiredException(source)
		return resp.isSuccessful
	}
}
