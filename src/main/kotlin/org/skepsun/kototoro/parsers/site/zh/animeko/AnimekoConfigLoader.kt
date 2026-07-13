package org.skepsun.kototoro.parsers.site.zh.animeko

import org.json.JSONObject

/**
 * Loads the animeko sources JSON from the classpath resource.
 */
internal object AnimekoConfigLoader {

    private val sources: List<AnimekoMediaSource> by lazy {
        val stream = AnimekoConfigLoader::class.java.classLoader
            .getResourceAsStream("animeko-sources.json")
            ?: error("animeko-sources.json not found in resources")

        val json = stream.bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONObject("exportedMediaSourceDataList").getJSONArray("mediaSources")

        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).toAnimekoMediaSource()
        }
    }

    /** Get all loaded sources. */
    fun all(): List<AnimekoMediaSource> = sources

    /** Get a source by its name field. */
    fun byName(name: String): AnimekoMediaSource =
        sources.firstOrNull { it.name == name }
            ?: error("Animeko source not found: $name")

    /** Get number of loaded sources. */
    fun count(): Int = sources.size
}