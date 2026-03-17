package org.skepsun.kototoro.parsers.util

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentParserSource
import kotlin.time.Duration.Companion.minutes

internal class LinkResolverTest {

	private val context = ContentLoaderContextMock

	@Test
	fun supportedSource() = runTest(timeout = 2.minutes) {
		val resolver = context.newLinkResolver("REDACTED" /* do not publish links to manga on GitHub */)
		Assertions.assertEquals(ContentParserSource.MANGADEX, resolver.getSource())
		val manga = resolver.getContent()
		Assertions.assertEquals(resolver.link.toString(), manga?.publicUrl)
	}

	@Test
	fun unsupportedSource2() = runTest(timeout = 2.minutes) {
		val resolver = context.newLinkResolver("REDACTED" /* do not publish links to manga on GitHub */)
		Assertions.assertEquals(ContentParserSource.BATOTO, resolver.getSource())
		val manga = resolver.getContent()
		Assertions.assertEquals(resolver.link.toString(), manga?.publicUrl)
	}
}
