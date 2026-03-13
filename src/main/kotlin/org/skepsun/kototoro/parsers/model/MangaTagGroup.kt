package org.skepsun.kototoro.parsers.model

/**
 * Group of tags for UI presentation.
 * Clients may fall back to [MangaListFilterOptions.availableTags] if not supported.
 */
public data class MangaTagGroup(
    @JvmField val title: String,
    @JvmField val tags: Set<MangaTag>,
    @JvmField val isExclusive: Boolean = false,
)
