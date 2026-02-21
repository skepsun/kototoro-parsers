package org.skepsun.kototoro.parsers

import org.skepsun.kototoro.parsers.model.Manga

public interface CategorizedFavoritesProvider : FavoritesProvider {

    public suspend fun fetchFavoriteFolders(): List<MangaFavoriteFolder>

    public suspend fun fetchFavorites(folderId: String): List<Manga>

    override suspend fun fetchFavorites(): List<Manga> {
        return fetchFavoriteFolders().flatMap { fetchFavorites(it.id) }.distinctBy { it.url }
    }
}

public data class MangaFavoriteFolder(
    val id: String,
    val title: String,
    val count: Int? = null,
)
