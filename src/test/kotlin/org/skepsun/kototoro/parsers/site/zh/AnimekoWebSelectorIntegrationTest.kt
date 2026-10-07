package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.assertVideoParserCatalog
import org.skepsun.kototoro.parsers.assertVideoParserPlayback

class AnimekoWebSelectorIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "FANTUAN_INTEGRATION_TEST", matches = "1")
    fun testFantuan() = runBlocking {
        val parser = Fantuan(ContentLoaderContextMock)
        assertVideoParserCatalog(parser, distinctDetailCovers = true)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "UZVOD_INTEGRATION_TEST", matches = "1")
    fun testUzvod() = runBlocking {
        val parser = Uzvod(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "UZVOD_INTEGRATION_TEST", matches = "1")
    fun testUzvodPlayback() = runBlocking {
        assertVideoParserPlayback(Uzvod(ContentLoaderContextMock))
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JIBI_INTEGRATION_TEST", matches = "1")
    fun testJibi() = runBlocking {
        val parser = Jibi(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FANQIE_INTEGRATION_TEST", matches = "1")
    fun testFanqie() = runBlocking {
        val parser = Fanqie(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SENZHIWU_INTEGRATION_TEST", matches = "1")
    fun testSenzhiwu() = runBlocking {
        val parser = Senzhiwu(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }
}
