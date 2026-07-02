package org.skepsun.kototoro.parsers

import org.junit.jupiter.params.provider.EnumSource
import org.skepsun.kototoro.parsers.model.ContentParserSource

// Change 'names' to test specified parsers
@EnumSource(ContentParserSource::class, names = [
    "WNACG", "COPYMANGA",
    // Yealico rule parsers — a representative sample per category
    "YEALICO_YANDE_RE_POST",   // booru NSFW, Japanese
    "YEALICO_KONACHAN_POST",   // booru NSFW, Japanese
    "YEALICO_E_SHUUSHUU",      // SFW gallery, Japanese
    "YEALICO_WALLHAVEN",       // SFW wallpaper, English
    "YEALICO_ZEROCHAN",        // anime image board, Japanese
    "YEALICO_UNSPLASH",        // API-driven gallery, English
], mode = EnumSource.Mode.INCLUDE)
internal annotation class ContentSources
