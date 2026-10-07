package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.assertVideoParserCatalog
import org.skepsun.kototoro.parsers.assertVideoParserPlayback

class NewVideoParserIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "ANIWORLD_INTEGRATION_TEST", matches = "1")
    fun testAniWorld() = runBlocking {
        val parser = AniWorld(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HENTAICLOUD_INTEGRATION_TEST", matches = "1")
    fun testHentaiCloud() = runBlocking {
        val parser = HentaiCloud(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HENTAIPLAY_INTEGRATION_TEST", matches = "1")
    fun testHentaiPlay() = runBlocking {
        val parser = HentaiPlay(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HANIME_INTEGRATION_TEST", matches = "1")
    fun testHanime() = runBlocking {
        val parser = Hanime(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PIMPBUNNY_INTEGRATION_TEST", matches = "1")
    fun testPimpBunny() = runBlocking {
        val parser = PimpBunny(ContentLoaderContextMock)
        assertVideoParserCatalog(parser)
        assertVideoParserPlayback(parser)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HANIME_INTEGRATION_TEST", matches = "1")
    fun testHanimeCatalog() = runBlocking {
        assertVideoParserCatalog(Hanime(ContentLoaderContextMock))
    }
}
