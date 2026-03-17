package org.skepsun.kototoro.parsers.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.ContentType.MANGA
import org.skepsun.kototoro.parsers.model.ContentType.MANHUA
import org.skepsun.kototoro.parsers.model.Demographic.SEINEN
import org.skepsun.kototoro.parsers.model.search.ContentSearchQuery
import org.skepsun.kototoro.parsers.model.search.QueryCriteria.*
import org.skepsun.kototoro.parsers.model.search.SearchableField.*
import java.util.*

class ListFilterToSearchQueryConverterTest {

    @Test
    fun convertToContentSearchQueryTest() {
        val tags = setOf(buildContentTag("tag1"), buildContentTag("tag2"))
        val excludedTags = setOf(buildContentTag("exclude_tag"))
        val states = setOf(ContentState.ONGOING)
        val contentRatings = setOf(ContentRating.SAFE)
        val contentTypes = setOf(MANGA, MANHUA)
        val demographics = setOf(SEINEN)

        val filter = ContentListFilter(
            query = "title_name",
            tags = tags,
            tagsExclude = excludedTags,
            locale = Locale.ENGLISH,
            originalLocale = Locale.JAPANESE,
            states = states,
            contentRating = contentRatings,
            types = contentTypes,
            demographics = demographics,
            year = 2020,
            yearFrom = 1997,
            yearTo = 2024,
        )

        val searchQuery = convertToContentSearchQuery(0, SortOrder.NEWEST, filter)

        val expectedQuery = ContentSearchQuery.Builder()
            .offset(0)
            .order(SortOrder.NEWEST)
            .criterion(Match(TITLE_NAME, "title_name"))
            .criterion(Include(TAG, tags))
            .criterion(Exclude(TAG, excludedTags))
            .criterion(Include(LANGUAGE, setOf(Locale.ENGLISH)))
            .criterion(Include(ORIGINAL_LANGUAGE, setOf(Locale.JAPANESE)))
            .criterion(Include(STATE, states))
            .criterion(Include(CONTENT_RATING, contentRatings))
            .criterion(Include(CONTENT_TYPE, contentTypes))
            .criterion(Include(DEMOGRAPHIC, demographics))
            .criterion(Range(PUBLICATION_YEAR, 1997, 2024))
            .criterion(Match(PUBLICATION_YEAR, 2020))
            .build()

        assertEquals(expectedQuery, searchQuery)
    }

    @Test
    fun convertToContentSearchQueryWithEmptyFieldsTest() {
        val filter = ContentListFilter()

        val searchQuery = convertToContentSearchQuery(0, SortOrder.NEWEST, filter)

        assertEquals(ContentSearchQuery.Builder().offset(0).order(SortOrder.NEWEST).build(), searchQuery)
    }

    private fun buildContentTag(name: String): ContentTag {
        return ContentTag(
            key = "${name}Key",
            title = name,
            source = ContentParserSource.MANGADEX,
        )
    }
}
