package org.skepsun.kototoro.parsers.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.core.AbstractContentParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy

public class LinkResolver internal constructor(
	private val context: ContentLoaderContext,
	public val link: HttpUrl,
) {

	private val source = suspendLazy(Dispatchers.Default, ::resolveSource)

	public suspend fun getSource(): ContentParserSource? = source.get()

	public suspend fun getContent(): Content? {
		val parser = context.newParserInstance(source.get() ?: return null)
		return parser.resolveLink(this, link) ?: resolveContent(parser)
	}

	private suspend fun resolveSource(): ContentParserSource? = runInterruptible(Dispatchers.Default) {
		val domains = setOfNotNull(link.host, link.topPrivateDomain())
		for (s in ContentParserSource.entries) {
			val parser = context.newParserInstance(s)
			for (d in parser.configKeyDomain.presetValues) {
				if (d in domains) {
					return@runInterruptible s
				}
			}
		}
		null
	}

	internal suspend fun resolveContent(
		parser: ContentParser,
		url: String = link.toString().toRelativeUrl(link.host),
		id: Long = parser.generateUid(url),
		title: String = STUB_TITLE,
	): Content? = resolveBySeed(
		parser,
		Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = link.toString(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = "",
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = parser.source,
		),
	)

	private suspend fun resolveBySeed(parser: ContentParser, s: Content): Content? {
		val seed = parser.getDetails(s)
		if (!parser.filterCapabilities.isSearchSupported) {
			return seed.takeUnless { it.chapters.isNullOrEmpty() }
		}
		val query = when {
			seed.title != STUB_TITLE && seed.title.isNotEmpty() -> seed.title
			seed.altTitles.isNotEmpty() -> seed.altTitles.first()
			seed.authors.isNotEmpty() -> seed.authors.first()
			else -> return seed // unfortunately we do not know a real manga title so unable to find it
		}
		val resolved = runCatchingCancellable {
			val list = parser.getList(0, parser.bestSortOrder(), ContentListFilter(query = query))
			list.singleOrNull { manga -> isSameUrl(manga.publicUrl) }
		}.getOrNull()
		if (resolved == null) {
			return seed
		}
		return runCatchingCancellable {
			parser.getDetails(resolved)
		}.getOrElse {
			resolved.copy(
				chapters = seed.chapters ?: resolved.chapters,
				description = seed.description ?: resolved.description,
				authors = seed.authors.ifEmpty { resolved.authors },
				tags = seed.tags + resolved.tags,
				state = seed.state ?: resolved.state,
				coverUrl = seed.coverUrl ?: resolved.coverUrl,
				largeCoverUrl = seed.largeCoverUrl ?: resolved.largeCoverUrl,
				altTitles = seed.altTitles + resolved.altTitles,
			)
		}
	}

	private fun isSameUrl(publicUrl: String): Boolean {
		if (publicUrl == link.toString()) {
			return true
		}
		val httpUrl = publicUrl.toHttpUrlOrNull() ?: return false
		return link.host == httpUrl.host
			&& link.encodedPath == httpUrl.encodedPath
	}

	private fun ContentParser.bestSortOrder(): SortOrder {
		val supported = availableSortOrders
		if (SortOrder.RELEVANCE in supported) {
			return SortOrder.RELEVANCE
		}
		if (this is AbstractContentParser) {
			return defaultSortOrder
		}
		return SortOrder.entries.first { it in supported }
	}

	private companion object {

		const val STUB_TITLE = "Unknown manga"
	}
}
