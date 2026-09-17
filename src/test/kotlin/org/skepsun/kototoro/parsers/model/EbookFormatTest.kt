package org.skepsun.kototoro.parsers.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EbookFormatTest {

	@Test
	fun `fromExtension 大小写与点号宽容`() {
		assertEquals(EbookFormat.PDF, EbookFormat.fromExtension("pdf"))
		assertEquals(EbookFormat.PDF, EbookFormat.fromExtension("PDF"))
		assertEquals(EbookFormat.EPUB, EbookFormat.fromExtension("Epub"))
		assertEquals(EbookFormat.DJVU, EbookFormat.fromExtension("djvu"))
		assertEquals(EbookFormat.FB2, EbookFormat.fromExtension("fb2"))
		assertEquals(EbookFormat.TXT, EbookFormat.fromExtension("txt"))
		assertEquals(EbookFormat.CBZ, EbookFormat.fromExtension("cbz"))
		// 容忍完整文件名
		assertEquals(EbookFormat.EPUB, EbookFormat.fromExtension("book.epub"))
		assertEquals(EbookFormat.PDF, EbookFormat.fromExtension("  prince.pdf  "))
	}

	@Test
	fun `fromExtension 未知或空返回 null`() {
		assertNull(EbookFormat.fromExtension(null))
		assertNull(EbookFormat.fromExtension(""))
		assertNull(EbookFormat.fromExtension("   "))
		assertNull(EbookFormat.fromExtension("exe"))
		assertNull(EbookFormat.fromExtension("."))
	}

	@Test
	fun `fromMarker 兼容解析器历史标记`() {
		assertEquals(EbookFormat.EPUB, EbookFormat.fromMarker("EPUB"))
		assertEquals(EbookFormat.PDF, EbookFormat.fromMarker("pdf"))
		assertEquals(EbookFormat.DJVU, EbookFormat.fromMarker("djvu"))
		assertEquals(EbookFormat.FB2, EbookFormat.fromMarker("fb2"))
		assertEquals(EbookFormat.TXT, EbookFormat.fromMarker("txt"))
		// 未知标记退回 UNKNOWN 而非 null（历史字段可能有不可识别值）
		assertEquals(EbookFormat.UNKNOWN, EbookFormat.fromMarker("wat"))
		assertEquals(EbookFormat.UNKNOWN, EbookFormat.fromMarker(null))
	}

	@Test
	fun `格式归入正确阅读模态`() {
		val textFormats = setOf(EbookFormat.EPUB, EbookFormat.FB2, EbookFormat.TXT, EbookFormat.MOBI, EbookFormat.AZW3, EbookFormat.DOCX)
		val pageFormats = setOf(EbookFormat.PDF, EbookFormat.DJVU, EbookFormat.CBZ, EbookFormat.CBR)
		for (fmt in EbookFormat.entries) {
			when (fmt) {
				EbookFormat.UNKNOWN -> Unit
				else -> {
					assertEquals(
						fmt in textFormats || fmt in pageFormats,
						true,
						"$fmt 应可归入文本或页面模态之一",
					)
				}
			}
		}
	}
}
