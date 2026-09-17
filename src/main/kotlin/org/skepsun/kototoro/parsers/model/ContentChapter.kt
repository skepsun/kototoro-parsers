package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.util.formatSimple
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty

public data class ContentChapter(
	/**
	 * An unique id of chapter
	 */
	@JvmField public val id: Long,
	/**
	 * User-readable name of chapter if provided by parser or null instead
	 * Do not pass manga title or chapter number here
	 */
	@JvmField public val title: String?,
	/**
	 * Chapter number starting from 1, 0 if unknown
	 */
	@JvmField public val number: Float,
	/**
	 * Volume number starting from 1, 0 if unknown
	 */
	@JvmField public val volume: Int,
	/**
	 * Relative url to chapter (**without** a domain) or any other uri.
	 * Used principally in parsers
	 */
	@JvmField public val url: String,
	/**
	 * User-readable name of scanlator (releaser) or null if unknown
	 */
	@JvmField public val scanlator: String?,
	/**
	 * Chapter upload date in milliseconds
	 */
	@JvmField public val uploadDate: Long,
	/**
	 * User-readable name of branch.
	 * A branch is a group of chapters that overlap (e.g. different languages)
	 */
	@JvmField public val branch: String?,
	@JvmField public val source: ContentSource,
	/**
	 * 该章节可下载的电子书格式候选。空 = 普通在线章节（图片/文本页）。
	 * 下载型书源（libgen/Z-Library/archive.org...）填充此字段，宿主按格式
	 * 选择阅读模态（文本模态 EPUB/FB2/TXT 展开内部章节；页面模态 PDF/DJVU 按页渲染）。
	 */
	@JvmField public val ebookFormats: List<EbookFormat> = emptyList(),
) {

	@Deprecated("Use title instead of name", ReplaceWith("ContentChapter(id, title, number, volume, url, scanlator, uploadDate, branch, source)"))
	public constructor(
		id: Long,
		name: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
		@Suppress("UNUSED_PARAMETER") dummy: Boolean = false,
	) : this(
		id = id,
		title = name,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
	)

	@Deprecated("Use title instead", ReplaceWith("title"))
	val name: String
		get() = title.ifNullOrEmpty {
			buildString {
				if (volume > 0) append("Vol ").append(volume).append(' ')
				if (number > 0) append("Chapter ").append(number.formatSimple()) else append("Unnamed")
			}
		}

	public fun numberString(): String? = if (number > 0f) {
		number.formatSimple()
	} else {
		null
	}

	public fun volumeString(): String? = if (volume > 0) {
		volume.toString()
	} else {
		null
	}
}
