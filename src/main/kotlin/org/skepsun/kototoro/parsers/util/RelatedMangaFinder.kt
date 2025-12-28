package org.skepsun.kototoro.parsers.util

import kotlinx.coroutines.*
import org.skepsun.kototoro.parsers.MangaParser
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

public class RelatedMangaFinder(
    private val parsers: Collection<MangaParser>,
) {

    public suspend operator fun invoke(seed: Manga): List<Manga> = withContext(Dispatchers.Default) {
        coroutineScope {
            parsers.singleOrNull()?.let { parser ->
                findRelatedImpl(this, parser, seed)
            } ?: parsers.map { parser ->
                async {
                    findRelatedImpl(this, parser, seed)
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun findRelatedImpl(scope: CoroutineScope, parser: MangaParser, seed: Manga): List<Manga> {
        val words = HashSet<String>()
        words += seed.title.splitByWhitespace()
        seed.altTitles.forEach {
            words += it.splitByWhitespace()
        }
        if (words.isEmpty()) {
            return emptyList()
        }
        
        // 日志：记录 seed 的 ID 和 URL
        println("[RelatedMangaFinder] seed.id=${seed.id} seed.url='${seed.url}' seed.title='${seed.title}'")
        
        val results = words.map { keyword ->
            scope.async {
                try {
                    val result = parser.getList(
                        0,
                        if (SortOrder.RELEVANCE in parser.availableSortOrders) {
                            SortOrder.RELEVANCE
                        } else {
                            parser.availableSortOrders.first()
                        },
                        MangaListFilter(
                            query = keyword,
                        ),
                    )
                    
                    // 日志：记录搜索结果和过滤情况
                    result.forEach { manga ->
                        val willFilter = manga.id == seed.id
                        println("[RelatedMangaFinder] result: id=${manga.id} url='${manga.url}' title='${manga.title}' willFilterAsSeed=$willFilter")
                    }
                    
                    result.filter { it.id != seed.id && it.containKeyword(keyword) }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll()
        return results.minBy { if (it.isEmpty()) Int.MAX_VALUE else it.size }
    }


    private fun Manga.containKeyword(keyword: String): Boolean {
        return title.contains(keyword, ignoreCase = true)
            || altTitles.any { it.contains(keyword, ignoreCase = true) }
    }
}
