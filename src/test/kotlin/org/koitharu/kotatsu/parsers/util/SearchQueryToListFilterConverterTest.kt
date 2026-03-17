package org.skepsun.kototoro.parsers.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType.MANGA
import org.skepsun.kototoro.parsers.model.ContentType.MANHUA
import org.skepsun.kototoro.parsers.model.Demographic.SEINEN
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.search.ContentSearchQuery
import org.skepsun.kototoro.parsers.model.search.QueryCriteria.*
import org.skepsun.kototoro.parsers.model.search.SearchableField.*
import java.util.*

class ConvertToContentListFilterTest {

    @Test
    fun convertToContentListFilterTest() {
        val tags = setOf(buildContentTag("tag1"), buildContentTag("tag2"))
        val excludedTags = setOf(buildContentTag("exclude_tag"))
        val states = setOf(ContentState.ONGOING)
        val contentRatings = setOf(ContentRating.SAFE)
        val contentTypes = setOf(MANGA, MANHUA)
        val demographics = setOf(SEINEN)

        val query = ContentSearchQuery.Builder()
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

        val listFilter = convertToContentListFilter(query)

        assertEquals(listFilter.query, "title_name")
        assertEquals(listFilter.tags, tags)
        assertEquals(listFilter.tagsExclude, excludedTags)
        assertEquals(listFilter.locale, Locale.ENGLISH)
        assertEquals(listFilter.originalLocale, Locale.JAPANESE)
        assertEquals(listFilter.states, states)
        assertEquals(listFilter.contentRating, contentRatings)
        assertEquals(listFilter.types, contentTypes)
        assertEquals(listFilter.demographics, demographics)
        assertEquals(listFilter.year, 2020)
        assertEquals(listFilter.yearFrom, 1997)
        assertEquals(listFilter.yearTo, 2024)
    }

    @Test
    fun convertToContentListFilterWithMultipleTagsIncludeTest() {
        val tags1 = setOf(buildContentTag("tag1"), buildContentTag("tag2"))
        val tags2 = setOf(buildContentTag("tag3"), buildContentTag("tag4"))

        val query = ContentSearchQuery.Builder()
            .criterion(Include(TAG, tags1))
            .criterion(Include(TAG, tags2))
            .build()

        val listFilter = convertToContentListFilter(query)

        assertEquals(listFilter.tags, tags1 union tags2)
    }

    @Test
    fun convertToContentListFilterWithUnsupportedFieldTest() {
        val query = ContentSearchQuery.Builder()
            .criterion(Include(AUTHOR, setOf(buildContentTag("author"))))
            .build()

        val exception = assertThrows<IllegalArgumentException> {
            convertToContentListFilter(query)
        }

        assert(exception.message!!.contains("Unsupported field for Include criterion: AUTHOR"))
    }

    private fun buildContentTag(name: String): ContentTag {
        return ContentTag(
            key = "${name}Key",
            title = name,
            source = ContentParserSource.MANGADEX,
        )
    }
}
