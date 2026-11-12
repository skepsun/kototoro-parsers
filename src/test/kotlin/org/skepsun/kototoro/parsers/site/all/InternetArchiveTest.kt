package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.EbookFormat
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class InternetArchiveTest {

	private val parser = InternetArchive(ContentLoaderContextMock)

	@Test
	fun `parse list fixture`() {
		val items = parser.parseList(fixture("search.json"))

		assertEquals(2, items.size)
		val first = items[0]
		assertEquals("Newton's Principia : the mathematical principles of natural philosophy", first.title)
		assertEquals("details/newtonspmathema00newtrich", first.url)
		assertEquals("https://archive.org/details/newtonspmathema00newtrich", first.publicUrl)
		assertEquals("https://archive.org/services/img/newtonspmathema00newtrich", first.coverUrl)
		assertEquals(setOf("Newton, Isaac, Sir, 1642-1727", "Motte, Andrew, d. 1730"), first.authors)
		assertEquals(2, first.tags.size)
		assertTrue(first.description!!.contains("Year: 1846"))
		assertTrue(first.description!!.contains("Language: eng"))
		assertTrue(first.description!!.contains("Downloads: 511318"))
	}

	@Test
	fun `parse list accepts scalar and array fields`() {
		val second = parser.parseList(fixture("search.json"))[1]

		// creator 是字符串 / language 是数组 / downloads 是字符串，都要能吃下
		assertEquals(setOf("Newton, Isaac, 1642-1727"), second.authors)
		assertTrue(second.description!!.contains("Language: lat"))
		assertTrue(second.description!!.contains("Downloads: 1203"))
		assertTrue(second.tags.isEmpty())
	}

	@Test
	fun `parse details builds chapter per download format`() {
		val result = parser.parseDetails(fixture("metadata.json"), content())

		assertEquals("Newton's Principia : the mathematical principles of natural philosophy", result.title)
		assertEquals(
			setOf("Newton, Isaac, Sir, 1642-1727", "Chittenden, N. W. Life of Sir Isaac Newton", "Motte, Andrew, d. 1730"),
			result.authors,
		)
		assertEquals(
			"https://archive.org/services/img/newtonspmathema00newtrich",
			result.coverUrl,
		)
		// 同一份 PDF 有两个版本（Text PDF / Additional Text PDF），只留主文件；格式顺序 EPUB → PDF → TXT
		val chapters = result.chapters!!
		assertEquals(listOf("EPUB (11.2 MB)", "PDF (60.5 MB)", "TXT (1.8 MB)"), chapters.map { it.title })
		assertEquals(1f, chapters[0].number)
		assertEquals(3f, chapters[2].number)
		assertEquals(
			"https://archive.org/download/newtonspmathema00newtrich/newtonspmathema00newtrich.epub",
			chapters[0].url,
		)
		// 目录信息来自 metadata 接口
		assertTrue(result.description!!.contains("Translation of: Philosophiae naturalis principia"))
		assertTrue(result.description!!.contains("Publisher: New-York : Published by Daniel Adee"))
		assertTrue(result.description!!.contains("Pages: 600"))
		assertTrue(result.description!!.contains("Collection: cdl, americana"))
		assertTrue(result.description!!.contains("Copyright: NOT_IN_COPYRIGHT"))
	}

	@Test
	fun `private and junk files are skipped`() {
		// 私有文件在夹具里排在首位且 format 同为 EPUB，过滤失效时 EPUB 章节会指向它
		val chapters = parser.parseDetails(fixture("metadata.json"), content()).chapters!!

		assertTrue(chapters.none { it.url.contains("_restricted") })
		assertTrue(chapters.none { it.url.contains("_hocr") })
		assertTrue(chapters.none { it.url.contains("scandata") })
		assertTrue(chapters.none { it.url.contains("__ia_thumb") })
		assertTrue(chapters.none { it.url.contains("_bw.pdf") })
	}

	@Test
	fun `chapter page carries ebook format marker`() {
		val chapters = parser.parseDetails(fixture("metadata.json"), content()).chapters!!
		val pages = chapters.map { runBlocking { parser.getPages(it) }.single() }

		assertEquals(
			listOf(EbookFormat.EPUB, EbookFormat.PDF, EbookFormat.TXT),
			pages.map { EbookFormat.fromMarker(it.preview) },
		)
		assertEquals(pages[1].url, runBlocking { parser.getPageUrl(pages[1]) })
	}

	@Test
	fun `build query keeps free downloads by default`() {
		assertEquals(
			"mediatype:texts AND NOT collection:inlibrary",
			parser.buildQuery(ContentListFilter()),
		)
		assertEquals(
			"mediatype:texts AND NOT collection:inlibrary AND (principia)",
			parser.buildQuery(ContentListFilter(query = "principia")),
		)
		val chinese = ContentTag("中文", "lang:chi", parser.source)
		val english = ContentTag("英文", "lang:eng", parser.source)
		assertEquals(
			"mediatype:texts AND NOT collection:inlibrary AND language:(chi OR eng)",
			parser.buildQuery(ContentListFilter(tags = setOf(chinese, english))),
		)
		// 主动选了借阅库才放开 NOT 条件
		val lending = ContentTag("可借阅", "collection:inlibrary", parser.source)
		assertEquals(
			"mediatype:texts AND collection:inlibrary",
			parser.buildQuery(ContentListFilter(tags = setOf(lending))),
		)
		val gutenberg = ContentTag("Project Gutenberg", "collection:gutenberg", parser.source)
		assertEquals(
			"mediatype:texts AND NOT collection:inlibrary AND collection:gutenberg",
			parser.buildQuery(ContentListFilter(tags = setOf(gutenberg))),
		)
	}

	@Test
	fun `build list urls`() {
		val base = parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter())
		assertTrue(base.startsWith("https://archive.org/advancedsearch.php?q=mediatype%3Atexts+AND+NOT+collection%3Ainlibrary"))
		assertTrue(base.contains("&fl%5B%5D=identifier"))
		assertTrue(base.contains("&fl%5B%5D=downloads"))
		assertTrue(base.contains("&rows=50&page=1"))
		assertTrue(base.endsWith("&output=json"))

		// 空关键词浏览默认按入库时间倒序，避免全库噪音排在最前
		assertTrue(parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter()).contains("&sort%5B%5D=date+desc"))
		// 有关键词的相关度搜索不传 sort，交给站点排序
		val search = ContentListFilter(query = "principia")
		assertTrue(!parser.buildListUrl(1, SortOrder.RELEVANCE, search).contains("&sort%5B%5D="))
		assertTrue(parser.buildListUrl(1, SortOrder.POPULARITY, search).contains("&sort%5B%5D=downloads+desc"))
		assertTrue(parser.buildListUrl(1, SortOrder.NEWEST, search).contains("&sort%5B%5D=date+desc"))
		assertTrue(parser.buildListUrl(1, SortOrder.NEWEST_ASC, search).contains("&sort%5B%5D=date+asc"))
		assertTrue(parser.buildListUrl(1, SortOrder.ALPHABETICAL, search).contains("&sort%5B%5D=titleSorter+asc"))
		assertTrue(parser.buildListUrl(3, SortOrder.RELEVANCE, search).contains("&page=3"))
	}

	private fun fixture(name: String): JSONObject = JSONObject(
		javaClass.getResourceAsStream("/fixtures/internetarchive/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
	)

	private fun content() = Content(
		id = 1L,
		title = "Newton's Principia",
		altTitles = emptySet(),
		url = "details/newtonspmathema00newtrich",
		publicUrl = "https://archive.org/details/newtonspmathema00newtrich",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = parser.source,
	)
}
