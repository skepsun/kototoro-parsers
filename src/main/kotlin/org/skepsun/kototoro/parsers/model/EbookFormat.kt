package org.skepsun.kototoro.parsers.model

/**
 * 电子书文件格式（下载型书源的可选下载载体）。
 *
 * 同一个内容条目可能提供多种格式文件（如 Z-Library / archive.org 的
 * epub+pdf+djvu 齐备），由 [ContentChapter.ebookFormats] 以候选列表表达；
 * libgen 类源每条记录固定一种格式，列表即单元素。
 *
 * 宿主按格式选择阅读模态：
 * - 文本模态（EPUB/FB2/TXT）：下载后解析正文，展开为内部文本章节
 * - 页面模态（PDF/DJVU/CBZ）：下载后保存文件，按页渲染（如 PdfRenderer）逐页阅读
 */
public enum class EbookFormat(
	/**
	 * 文件扩展名（小写，不含点）。
	 */
	val extension: String,
	/**
	 * 面向用户的展示名。
	 */
	val displayName: String,
) {
	/** EPUB：标准电子书容器（XHTML+CSS+分包），文本模态 */
	EPUB("epub", "EPUB"),
	/** PDF：页面模态 */
	PDF("pdf", "PDF"),
	/** DjVu：扫描文档，页面模态 */
	DJVU("djvu", "DJVU"),
	/** FictionBook：XML 文本电子书，文本模态 */
	FB2("fb2", "FB2"),
	/** 纯文本，文本模态 */
	TXT("txt", "TXT"),
	/** Kindle 旧格式（部分加密），按文本模态处理 */
	MOBI("mobi", "MOBI"),
	/** Kindle 8 格式，按文本模态处理 */
	AZW3("azw3", "AZW3"),
	/** Comic book ZIP：页面模态（图片页） */
	CBZ("cbz", "CBZ"),
	/** Comic book RAR：页面模态（图片页） */
	CBR("cbr", "CBR"),
	/** DOCX：Word 文档，按文本模态处理 */
	DOCX("docx", "DOCX"),
	/** 未知/不支持格式 */
	UNKNOWN("", "Unknown");

	companion object {
		/**
		 * 按扩展名解析格式；大小写不敏感，去掉前导点。
		 *
		 * @param extension 扩展名或携带点号的完整文件名（如 "pdf" / "book.epub"）
		 * @return 匹配的格式，无法识别返回 null（调用方可决定用 UNKNOWN 兜底）
		 */
		@JvmStatic
		public fun fromExtension(extension: String?): EbookFormat? {
			val raw = extension?.trim()?.lowercase()
				?.substringAfterLast('.') // 容忍传入完整文件名
				?.takeIf { it.isNotEmpty() && it != "." }
				?: return null
			return entries.firstOrNull { it.extension == raw }
		}

		/**
		 * 解析解析器侧历史标记字符串（旧实现把格式塞进 ContentPage.preview，如 "EPUB"/"PDF"）。
		 *
		 * @param marker 格式标记（不区分大小写，容忍 "djvu"、" EPUB " 等）
		 * @return 匹配的格式，无法识别返回 UNKNOWN
		 */
		@JvmStatic
		public fun fromMarker(marker: String?): EbookFormat {
			return fromExtension(marker) ?: UNKNOWN
		}
	}
}
