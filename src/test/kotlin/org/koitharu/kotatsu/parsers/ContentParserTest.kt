package org.skepsun.kototoro.parsers

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.core.SinglePageContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.search.ContentSearchQuery
import org.skepsun.kototoro.parsers.model.search.QueryCriteria
import org.skepsun.kototoro.parsers.model.search.QueryCriteria.Include
import org.skepsun.kototoro.parsers.model.search.SearchableField.*
import org.skepsun.kototoro.parsers.util.medianOrNull
import org.skepsun.kototoro.parsers.util.mimeType
import org.skepsun.kototoro.test_util.*
import kotlin.time.Duration.Companion.minutes

//@ExtendWith(AuthCheckExtension::class)
internal class ContentParserTest {

	private val context = ContentLoaderContextMock
	private val timeout = 2.minutes

	@ParameterizedTest(name = "{index}|list|{0}")
	@ContentSources
	fun list(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val list = parser.getList(ContentSearchQuery.Builder().build())
		checkContentList(list, "list")
		assert(list.all { it.source == source })
	}

	@ParameterizedTest(name = "{index}|pagination|{0}")
	@ContentSources
	fun pagination(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		if (parser is SinglePageContentParser) {
			return@runTest
		}
		val page1 = parser.getList(ContentSearchQuery.EMPTY)
		val page2 =
			parser.getList(ContentSearchQuery.Builder().offset(page1.size).build())
		if (parser is PagedContentParser) {
			assert(parser.pageSize >= page1.size) {
				"Page size is ${page1.size} but ${parser.pageSize} expected"
			}
		}
		assert(page1.isNotEmpty()) { "Page 1 is empty" }
		assert(page2.isNotEmpty()) { "Page 2 is empty" }
		assert(page1 != page2) { "Pages are equal" }
		val intersection = page1.intersect(page2.toSet())
		assert(intersection.isEmpty()) {
			"Pages are intersected by " + intersection.size
		}
	}

	@ParameterizedTest(name = "{index}|search|{0}")
	@ContentSources
	fun searchByTitleName(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val subject = parser.getList(ContentSearchQuery.EMPTY).minByOrNull {
			it.title.length
		} ?: error("No manga found")

		val query = subject.title
		check(query.isNotBlank()) { "Content title '$query' is blank" }
		val list = parser.getList(
			ContentSearchQuery.Builder()
				.order(SortOrder.RELEVANCE)
				.criterion(QueryCriteria.Match(TITLE_NAME, query))
				.build(),
		)
		assert(list.isNotEmpty()) { "Empty search results by \"$query\"" }
		assert(list.singleOrNull { it.url == subject.url && it.id == subject.id } != null) {
			"Single subject '${subject.title} (${subject.publicUrl})' not found in search results"
		}
		checkContentList(list, "search('$query')")
		assert(list.all { it.source == source })
	}

	@ParameterizedTest(name = "{index}|tags|{0}")
	@ContentSources
	fun tags(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val tags = parser.getFilterOptions().availableTags
		assert(tags.isNotEmpty()) { "No tags found" }
		val keys = tags.map { it.key }
		assert(keys.isDistinct())
		assert("" !in keys)
		val titles = tags.map { it.title }
		assert(titles.isDistinct())
		assert("" !in titles)
		assert(titles.all { it.isCapitalized() }) {
			val badTags = titles.filterNot { it.isCapitalized() }.joinToString()
			"Not all tags are capitalized: $badTags"
		}
		assert(tags.all { it.source == source })

		// 单标签源不支持 TAG 查询（与 tags_multiple 守卫一致），跳过查询段
		if (!parser.filterCapabilities.isMultipleTagsSupported) return@runTest
		val tag = tags.last()
		val list = parser.getList(
			ContentSearchQuery.Builder()
				.offset(0)
				.criterion(Include(TAG, setOf(tag)))
				.build(),
		)
		checkContentList(list, "${tag.title} (${tag.key})")
		assert(list.all { it.source == source })
	}

	@ParameterizedTest(name = "{index}|tags_multiple|{0}")
	@ContentSources
	fun tagsMultiple(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		if (!parser.filterCapabilities.isMultipleTagsSupported) return@runTest
		val tags = parser.getFilterOptions().availableTags.shuffled().take(2).toSet()

		val list = parser.getList(
			ContentSearchQuery.Builder()
				.offset(0)
				.criterion(Include(TAG, tags))
				.build(),
		)

		checkContentList(list, "${tags.joinToString { it.title }} (${tags.joinToString { it.key }})")
		assert(list.all { it.source == source })
	}

	@ParameterizedTest(name = "{index}|locale|{0}")
	@ContentSources
	fun locale(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val locales = parser.getFilterOptions().availableLocales
		if (locales.isEmpty()) {
			return@runTest
		}
		val locale = locales.random()
		val list = parser.getList(
			ContentSearchQuery.Builder()
				.criterion(Include(LANGUAGE, setOf(locale)))
				.criterion(Include(LANGUAGE, setOf(locale)))
				.criterion(Include(ORIGINAL_LANGUAGE, setOf(locales.random())))
				.build(),
		)
		checkContentList(list, locale.toString())
		assert(list.all { it.source == source })
	}


	@ParameterizedTest(name = "{index}|details|{0}")
	@ContentSources
	fun details(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val list = parser.getList(ContentSearchQuery.EMPTY)

		val manga = list.random()
		parser.getDetails(manga).apply {
			assert(!chapters.isNullOrEmpty()) { "Chapters are null or empty" }
			assert(publicUrl.isUrlAbsolute()) { "Content public url is not absolute: '$publicUrl'" }
			assert(description != null) { "Detailed description is null: '$publicUrl'" }
			assert(title.startsWith(manga.title)) {
				"Titles are mismatch: '$title' and '${manga.title}' for $publicUrl"
			}
			assert(this.source == source)
			val c = checkNotNull(chapters)
			assert(c.isDistinctBy { it.id }) {
				"Chapters are not distinct by id: ${c.maxDuplicates { it.id }} for $publicUrl"
			}
			assert(c.isDistinctByNotNull { it.key() }) {
				val dup = c.mapNotNull { it.key() }.maxDuplicates { it }
				"Chapters are not distinct by branch/volume/number: $dup for $publicUrl"
			}
			assert(c.all { it.source == source })
			checkImageRequest(coverUrl, source)
			largeCoverUrl?.let {
				checkImageRequest(it, source)
			}
		}
	}

	@ParameterizedTest(name = "{index}|pages|{0}")
	@ContentSources
	fun pages(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val list = parser.getList(ContentSearchQuery.EMPTY)
		val manga = list.first()
		val chapter = parser.getDetails(manga).chapters?.firstOrNull() ?: error("Chapter is null at ${manga.publicUrl}")
		val pages = parser.getPages(chapter)

		assert(pages.isNotEmpty())
		assert(pages.isDistinctBy { it.id })
		assert(pages.all { it.source == source })

		arrayOf(
			pages.first(),
			pages.medianOrNull() ?: error("No page"),
		).forEach { page ->
			val pageUrl = parser.getPageUrl(page)
			assert(pageUrl.isNotEmpty())
			assert(pageUrl.isUrlAbsolute())
			checkImageRequest(pageUrl, page.source)
		}
	}

	@ParameterizedTest(name = "{index}|favicon|{0}")
	@ContentSources
	fun favicon(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val favicons = parser.getFavicons()
		val types = setOf("png", "svg", "ico", "gif", "jpg", "jpeg", "webp", "avif")
		assert(favicons.isNotEmpty())
		favicons.forEach {
			assert(it.url.isUrlAbsolute()) { "Favicon url is not absolute: ${it.url}" }
			assert(it.type in types) { "Unknown icon type: ${it.type}" }
		}
		val favicon = favicons.find(24)
		checkNotNull(favicon)
		checkImageRequest(favicon.url, source)
	}

	@ParameterizedTest(name = "{index}|domain|{0}")
	@ContentSources
	fun domain(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val defaultDomain = parser.domain
		val url = HttpUrl.Builder().host(defaultDomain).scheme("https").toString()
		val response = context.doRequest(url, source)
		val realUrl = response.request.url
		val realDomain = realUrl.topPrivateDomain()
		val realHost = realUrl.host
		assert(defaultDomain == realHost || defaultDomain == realDomain) {
			"Domain mismatch:\nRequired:\t\t\t$defaultDomain\nActual:\t\t\t$realDomain\nHost:\t\t\t$realHost"
		}
	}

	@ParameterizedTest(name = "{index}|link|{0}")
	@ContentSources
	fun link(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val manga = parser.getList(ContentSearchQuery.Builder().build()).first()
		val resolved = context.newLinkResolver(manga.publicUrl).getContent()
		Assertions.assertNotNull(resolved)
		resolved ?: return@runTest
		Assertions.assertEquals(manga.id, resolved.id)
		Assertions.assertEquals(manga.publicUrl, resolved.publicUrl)
		Assertions.assertEquals(manga.url, resolved.url)
		Assertions.assertEquals(manga.title, resolved.title)
	}

	@ParameterizedTest(name = "{index}|authorization|{0}")
	@ContentSources
	@Disabled
	fun authorization(source: ContentParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		if (parser is ContentParserAuthProvider) {
			val username = parser.getUsername()
			assert(username.isNotBlank()) { "Username is blank" }
			println("Signed in to ${source.name} as $username")
		}
	}

	private suspend fun checkContentList(list: List<Content>, cause: String) {
		assert(list.isNotEmpty()) { "Content list for '$cause' is empty" }
		assert(list.isDistinctBy { it.id }) { "Content list for '$cause' contains duplicated ids" }
		for (item in list) {
			assert(item.url.isNotEmpty()) { "Url is empty" }
			assert(!item.url.isUrlAbsolute()) { "Url looks like absolute: ${item.url}" }
			item.coverUrl?.let {
				assert(it.isUrlAbsolute()) { "Cover url is not absolute: ${item.coverUrl}" }
			}
			assert(item.title.isNotEmpty()) { "Title for ${item.publicUrl} is empty" }
			assert(item.publicUrl.isUrlAbsolute())
		}
		val testItem = list.random()
		checkImageRequest(testItem.coverUrl, testItem.source)
	}

	private suspend fun checkImageRequest(url: String?, source: ContentSource) {
		if (url == null) {
			return
		}
		context.doRequest(url, source).use {
			assert(it.isSuccessful) { "Request failed: ${it.code}(${it.message}): $url" }
			assert(it.mimeType?.startsWith("image/") == true) {
				"Wrong response mime type: ${it.mimeType}"
			}
		}
	}

	private fun String.isCapitalized(): Boolean {
		return !first().isLowerCase()
	}

	private fun ContentChapter.key(): Any? = when {
		number > 0f && volume > 0 -> Triple(branch, volume, number)
		number > 0f -> Pair(branch, number)
		else -> null
	}
}
