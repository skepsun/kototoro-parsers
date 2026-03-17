package org.skepsun.kototoro.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

class AuthCheckExtension : BeforeAllCallback {

	private val loaderContext: ContentLoaderContext = ContentLoaderContextMock

	override fun beforeAll(context: ExtensionContext) {
		for (source in ContentParserSource.entries) {
			val parser = loaderContext.newParserInstance(source)
			if (parser is ContentParserAuthProvider) {
				checkAuthorization(source, parser)
			}
		}
	}

	private fun checkAuthorization(source: ContentParserSource, parser: ContentParserAuthProvider) = runTest {
		runCatchingCancellable {
			parser.getUsername()
		}.onSuccess { username ->
			println("Signed in to ${source.name} as $username")
		}.onFailure { error ->
			System.err.println("Auth failed for ${source.name}: ${error.javaClass.name}(${error.message})")
		}
	}
}
