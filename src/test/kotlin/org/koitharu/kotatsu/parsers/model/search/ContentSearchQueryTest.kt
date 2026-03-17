package org.skepsun.kototoro.parsers.model.search

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.search.QueryCriteria.*
import org.skepsun.kototoro.parsers.model.search.SearchableField.*
import java.util.*

class ContentSearchQueryCapabilitiesTest {

    private val capabilities = ContentSearchQueryCapabilities(
        capabilities = setOf(
            SearchCapability(TITLE_NAME, setOf(Match::class), isMultiple = false, isExclusive = true),
            SearchCapability(TAG, setOf(Include::class, Exclude::class), isMultiple = true, isExclusive = false),
            SearchCapability(PUBLICATION_YEAR, setOf(Range::class), isMultiple = false, isExclusive = false),
            SearchCapability(STATE, setOf(Include::class), isMultiple = false, isExclusive = false),
        ),
    )

    @Test
    fun validateValidSingleCriterionQuery() {
        val query = ContentSearchQuery.Builder()
            .criterion(Match(TITLE_NAME, "title"))
            .build()

        assertDoesNotThrow { capabilities.validate(query) }
    }

    @Test
    fun validateUnsupportedFieldThrowsException() {
        val query = ContentSearchQuery.Builder()
            .criterion(Include(ORIGINAL_LANGUAGE, setOf(Locale.ENGLISH)))
            .build()

        assertThrows(IllegalArgumentException::class.java) { capabilities.validate(query) }
    }

    @Test
    fun validateUnsupportedMultiValueThrowsException() {
        val query = ContentSearchQuery.Builder()
            .criterion(Include(STATE, setOf(ContentState.ONGOING, ContentState.FINISHED)))
            .build()

        assertThrows(IllegalArgumentException::class.java) { capabilities.validate(query) }
    }

    @Test
    fun validateMultipleCriteriaWithOtherCriteriaAllowed() {
        val query = ContentSearchQuery.Builder()
            .criterion(Include(TAG, setOf(buildTag("tag1"), buildTag("tag2"))))
            .criterion(Exclude(TAG, setOf(buildTag("tag3"))))
            .build()

        assertDoesNotThrow { capabilities.validate(query) }
    }

    @Test
    fun validateMultipleCriteriaWithStrictCapabilityThrowsException() {
        val query = ContentSearchQuery.Builder()
            .criterion(Match(TITLE_NAME, "title"))
            .criterion(Range(PUBLICATION_YEAR, 1990, 2000))
            .build()

        assertThrows(IllegalArgumentException::class.java) { capabilities.validate(query) }
    }

    private fun buildTag(name: String) = ContentTag(title = name, key = "${name}Key", source = ContentParserSource.MANGADEX)
}
