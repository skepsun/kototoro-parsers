package org.skepsun.kototoro.parsers.site.all

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.EbookFormat
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import kotlinx.coroutines.runBlocking

class LibraryGenesisTest {

	private val parser = LibraryGenesis(ContentLoaderContextMock)

	@Test
	fun `parse list fixture`() {
		val result = parser.parseList(fixture("search.html"))

		assertEquals(2, result.size)
		val first = result[0]
		assertEquals("Camus, Albert-The Plague", first.title)
		assertEquals("ads.php?md5=6ac969c79073a65228aca6f4a9be9ba6", first.url)
		assertEquals("https://libgen.li/ads.php?md5=6ac969c79073a65228aca6f4a9be9ba6", first.publicUrl)
		assertEquals(setOf("Camus, Albert"), first.authors)
		assertTrue(first.description!!.contains("Format: epub"))
		assertTrue(first.description!!.contains("Size: 4 MB"))

		val second = result[1]
		assertEquals("Gödel, Escher, Bach: Una Eterna Trenza Dorada", second.title)
		assertEquals(setOf("Douglas R. Hofstadter"), second.authors)
		assertTrue(second.description!!.contains("Year: 1982"))
		assertTrue(second.description!!.contains("Publisher: Consejo Nacional De Ciencia"))
	}

	@Test
	fun `parse details fixture`() {
		val content = content()
		val result = parser.parseDetails(fixture("detail.html"), content)

		assertEquals("Gödel, Escher, Bach: Una Eterna Trenza Dorada", result.title)
		assertEquals(setOf("Douglas R. Hofstadter"), result.authors)
		assertEquals(
			"https://libgen.li/covers/1507000/b14eeb69d62589319b68a1d89a321fa5.jpg",
			result.coverUrl,
		)
		assertTrue(result.description!!.contains("Publisher: Consejo Nacional De Ciencia"))
		assertTrue(result.description!!.contains("ISBN: 9789688231180; 9688231185"))
		// 章节指向详情页（含一次性下载 key，读取时重新获取），并用 #ext 片段携带真实格式
		val chapter = result.chapters!!.single()
		assertEquals("${content.url}#ext=pdf", chapter.url)
		assertEquals("Download (PDF)", chapter.title)
		// 格式走 ContentPage.preview 通道（getPages 本地解析 #ext 片段）；宿主据此判定文本/页面模态
		val page = runBlocking { parser.getPages(chapter) }.single()
		val pdf = page.preview?.let { EbookFormat.fromMarker(it) }
		assertEquals(EbookFormat.PDF, pdf)
	}

	@Test
	fun `parse download url fixture`() {
		val url = parser.parseDownloadUrl(fixture("detail.html"), "https://libgen.li/ads.php?md5=b14eeb69d62589319b68a1d89a321fa5")
		assertEquals(
			"https://libgen.li/get.php?md5=b14eeb69d62589319b68a1d89a321fa5&key=D1UI8CAQ8RW7X1AT",
			url,
		)
		assertThrows(ParseException::class.java) {
			parser.parseDownloadUrl(Jsoup.parse("<html><body>no link</body></html>", "https://libgen.li/"), "x")
		}
	}

	@Test
	fun `build list urls`() {
		assertEquals(
			"https://libgen.li/index.php?req=fmode%3Alast&res=50",
			parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter()),
		)
		val search = ContentListFilter(query = "camus plague")
		assertEquals(
			"https://libgen.li/index.php?req=camus+plague&res=50",
			parser.buildListUrl(1, SortOrder.RELEVANCE, search),
		)
		assertTrue(
			parser.buildListUrl(2, SortOrder.NEWEST, search)
				.contains("order=time_added&ordermode=desc"),
		)
		assertTrue(parser.buildListUrl(2, SortOrder.RELEVANCE, search).endsWith("&page=2"))

		val fiction = ContentTag("Fiction", "topic:f", parser.source)
		val comics = ContentTag("Comics", "topic:c", parser.source)
		val topicUrl = parser.buildListUrl(1, SortOrder.RELEVANCE, ContentListFilter(query = "batman", tags = setOf(fiction, comics)))
		assertTrue(topicUrl.contains("topics%5B%5D=f"))
		assertTrue(topicUrl.contains("topics%5B%5D=c"))
	}

	@Test
	fun `parse authors trims libgen separators`() {
		assertEquals(
			setOf("Bochniak, Arkadiusz", "Sitarz, Andrzej", "Zalecki, Pawel"),
			parser.parseAuthors("Bochniak, Arkadiusz ;, ;Sitarz, Andrzej ;Zalecki, Pawel ;, ;, "),
		)
		assertEquals(setOf("Camus, Albert"), parser.parseAuthors("Camus, Albert "))
		assertTrue(parser.parseAuthors("").isEmpty())
	}

	private fun fixture(name: String): Document = Jsoup.parse(
		javaClass.getResourceAsStream("/fixtures/librarygenesis/$name")?.bufferedReader()?.use { it.readText() }
			?: error("fixture not found: $name"),
		"https://libgen.li/",
	)

	private fun content() = Content(
		id = 2L,
		title = "Gödel, Escher, Bach",
		altTitles = emptySet(),
		url = "ads.php?md5=b14eeb69d62589319b68a1d89a321fa5",
		publicUrl = "https://libgen.li/ads.php?md5=b14eeb69d62589319b68a1d89a321fa5",
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
