package org.skepsun.kototoro.parsers.site.all

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MangaFireVrfSignerTest {

	@Test
	fun `generate matches known signature`() {
		assertEquals(
			"8sK3xtqdFZdD1d-yEmmI1uMCFYnFaw",
			MangaFireVrfSigner.generate("/titles?limit=2&page=1"),
		)
	}

	@Test
	fun `sign sorts parameters and indexes arrays`() {
		val url = "https://mangafire.to/api/titles".toHttpUrl().newBuilder()
			.addQueryParameter("keyword", "one piece")
			.addQueryParameter("genres_in[]", "78")
			.addQueryParameter("genres_in[]", "1")
			.build()

		val signed = MangaFireVrfSigner.sign(url)
		val expectedInput = "/titles?genres_in[0]=78&genres_in[1]=1&keyword=one piece"

		assertEquals(listOf("78", "1"), signed.queryParameterValues("genres_in[]"))
		assertEquals("one piece", signed.queryParameter("keyword"))
		assertEquals(MangaFireVrfSigner.generate(expectedInput), signed.queryParameter("vrf"))
	}
}
