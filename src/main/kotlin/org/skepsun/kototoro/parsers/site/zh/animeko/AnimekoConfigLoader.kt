package org.skepsun.kototoro.parsers.site.zh.animeko

/**
 * Loads the animeko sources from embedded Kotlin constants.
 * No JSON file or classpath resource needed at runtime.
 */
internal object AnimekoConfigLoader {

    @JvmStatic
    fun all(): List<AnimekoMediaSource> = AnimekoConfigs.all

    @JvmStatic
    fun byName(name: String): AnimekoMediaSource = AnimekoConfigs.byName(name)

    @JvmStatic
    fun count(): Int = AnimekoConfigs.count()
}