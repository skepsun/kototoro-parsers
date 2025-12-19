package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.MangaParser

public data class MangaPage(
	/**
	 * Unique identifier for page
	 */
	@JvmField public val id: Long,
	/**
	 * Relative url to page (**without** a domain) or any other uri.
	 * Used principally in parsers.
	 * May contain link to image or html page.
	 * @see MangaParser.getPageUrl
	 */
	@JvmField public val url: String,
	/**
	 * Absolute url of the small page image if exists, null otherwise
	 */
	@JvmField public val preview: String?,
	/**
	 * Optional per-page request headers (e.g., Referer) to be applied when fetching the page/image.
	 */
	@JvmField public val headers: Map<String, String>? = null,
	@JvmField public val source: MangaSource,
)

@Deprecated("Use id instead of index", ReplaceWith("MangaPage(index.toLong(), url, previewUrl, source)"))
public fun MangaPage(index: Int, url: String, previewUrl: String?, source: MangaSource): MangaPage = MangaPage(
	id = index.toLong(),
	url = url,
	preview = previewUrl,
	source = source,
)
