package org.skepsun.kototoro.parsers

import org.junit.jupiter.params.provider.EnumSource
import org.skepsun.kototoro.parsers.model.ContentParserSource

// Change 'names' to test specified parsers
@EnumSource(ContentParserSource::class, names = ["WNACG", "COPYMANGA"], mode = EnumSource.Mode.INCLUDE)
internal annotation class ContentSources
