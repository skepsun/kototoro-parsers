package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.*

@ContentSourceParser("COMIX", "Comix", "en")
internal class ComixParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.COMIX, pageSize = 28) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("comix.to")

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL
        )
    )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<ContentTag> {
        return setOf(
            // Genres
            ContentTag(key = "6", title = "Action", source = source),
            ContentTag(key = "7", title = "Adventure", source = source),
            ContentTag(key = "8", title = "Boys Love", source = source),
            ContentTag(key = "9", title = "Comedy", source = source),
            ContentTag(key = "10", title = "Crime", source = source),
            ContentTag(key = "11", title = "Drama", source = source),
            ContentTag(key = "12", title = "Fantasy", source = source),
            ContentTag(key = "13", title = "Girls Love", source = source),
            ContentTag(key = "14", title = "Historical", source = source),
            ContentTag(key = "15", title = "Horror", source = source),
            ContentTag(key = "16", title = "Isekai", source = source),
            ContentTag(key = "17", title = "Magical Girls", source = source),
            ContentTag(key = "87267", title = "Mature", source = source),
            ContentTag(key = "18", title = "Mecha", source = source),
            ContentTag(key = "19", title = "Medical", source = source),
            ContentTag(key = "20", title = "Mystery", source = source),
            ContentTag(key = "21", title = "Philosophical", source = source),
            ContentTag(key = "22", title = "Psychological", source = source),
            ContentTag(key = "23", title = "Romance", source = source),
            ContentTag(key = "24", title = "Sci-Fi", source = source),
            ContentTag(key = "25", title = "Slice of Life", source = source),
            ContentTag(key = "26", title = "Sports", source = source),
            ContentTag(key = "27", title = "Superhero", source = source),
            ContentTag(key = "28", title = "Thriller", source = source),
            ContentTag(key = "29", title = "Tragedy", source = source),
            ContentTag(key = "30", title = "Wuxia", source = source),
            // Themes
            ContentTag(key = "31", title = "Aliens", source = source),
            ContentTag(key = "32", title = "Animals", source = source),
            ContentTag(key = "33", title = "Cooking", source = source),
            ContentTag(key = "34", title = "Crossdressing", source = source),
            ContentTag(key = "35", title = "Delinquents", source = source),
            ContentTag(key = "36", title = "Demons", source = source),
            ContentTag(key = "37", title = "Genderswap", source = source),
            ContentTag(key = "38", title = "Ghosts", source = source),
            ContentTag(key = "39", title = "Gyaru", source = source),
            ContentTag(key = "40", title = "Harem", source = source),
            ContentTag(key = "41", title = "Incest", source = source),
            ContentTag(key = "42", title = "Loli", source = source),
            ContentTag(key = "43", title = "Mafia", source = source),
            ContentTag(key = "44", title = "Magic", source = source),
            ContentTag(key = "45", title = "Martial Arts", source = source),
            ContentTag(key = "46", title = "Military", source = source),
            ContentTag(key = "47", title = "Monster Girls", source = source),
            ContentTag(key = "48", title = "Monsters", source = source),
            ContentTag(key = "49", title = "Music", source = source),
            ContentTag(key = "50", title = "Ninja", source = source),
            ContentTag(key = "51", title = "Office Workers", source = source),
            ContentTag(key = "52", title = "Police", source = source),
            ContentTag(key = "53", title = "Post-Apocalyptic", source = source),
            ContentTag(key = "54", title = "Reincarnation", source = source),
            ContentTag(key = "55", title = "Reverse Harem", source = source),
            ContentTag(key = "56", title = "Samurai", source = source),
            ContentTag(key = "57", title = "School Life", source = source),
            ContentTag(key = "58", title = "Shota", source = source),
            ContentTag(key = "59", title = "Supernatural", source = source),
            ContentTag(key = "60", title = "Survival", source = source),
            ContentTag(key = "61", title = "Time Travel", source = source),
            ContentTag(key = "62", title = "Traditional Games", source = source),
            ContentTag(key = "63", title = "Vampires", source = source),
            ContentTag(key = "64", title = "Video Games", source = source),
            ContentTag(key = "65", title = "Villainess", source = source),
            ContentTag(key = "66", title = "Virtual Reality", source = source),
            ContentTag(key = "67", title = "Zombies", source = source),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildString {
            append("https://comix.to/api/v2/manga?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            // Search keyword if provided
            if (!filter.query.isNullOrEmpty()) {
                addParam("keyword=${filter.query.urlEncoded()}")
            }

            // Use the provided sort order directly
            when (order) {
                SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                SortOrder.NEWEST -> addParam("order[created_at]=desc")
                SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                else -> addParam("order[chapter_updated_at]=desc")
            }

            // Handle genre filtering
            if (filter.tags.isNotEmpty()) {
                for (tag in filter.tags) {
                    addParam("genres[]=${tag.key}")
                }
            }

            // Default exclude adult content
            addParam("genres[]=-87264") // Adult
            addParam("genres[]=-87266") // Hentai
            addParam("genres[]=-87268") // Smut
            addParam("genres[]=-87265") // Ecchi
            addParam("limit=$pageSize")
            addParam("page=$page")
        }

        val response = webClient.httpGet(url).parseJson()
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseContentFromJson(item)
        }
    }

    private fun parseContentFromJson(json: JSONObject): Content {
        val hashId = json.getString("hash_id")
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.getJSONObject("poster")
        val coverUrl = poster.optString("large", "").nullIfEmpty()
        val status = json.optString("status", "")
        val year = json.optInt("year", 0)
        val rating = json.optDouble("rated_avg", 0.0)

        val state = when (status) {
            "finished" -> null
            "releasing" -> null
            "on_hiatus" -> null
            else -> null
        }

        return Content(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://comix.to/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = if (rating > 0) (rating / 10.0f).toFloat() else RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Content): Content = coroutineScope {
        val hashId = manga.url.substringAfter("/title/")
        val chaptersDeferred = async { getChapters(manga) }

        // Get detailed Content info
        val detailUrl = "https://comix.to/api/v2/manga/$hashId"
        val response = webClient.httpGet(detailUrl).parseJson()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedContent = parseContentFromJson(result)

            return@coroutineScope updatedContent.copy(
                chapters = chaptersDeferred.await(),
            )
        }

        return@coroutineScope manga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    override suspend fun getRelatedContent(seed: Content): List<Content> = emptyList()

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val chapterUrl = "https://comix.to${chapter.url}"

        // Get the chapter page HTML to extract images from the script
        val response = webClient.httpGet(chapterUrl).parseHtml()

        // Look for the images array in the JavaScript (with escaped quotes)
        val scripts = response.select("script")
        var images: JSONArray? = null

        for (script in scripts) {
            val scriptContent = script.html()

            // Look for the images array with escaped quotes in JSON
            if (scriptContent.contains("\\\"images\\\":[")) {
                try {
                    // Find the start of the images array (with escaped quotes)
                    val imagesStart = scriptContent.indexOf("\\\"images\\\":[")
                    val colonPos = scriptContent.indexOf(":", imagesStart)
                    val arrayStart = scriptContent.indexOf("[", colonPos)

                    // Find the matching closing bracket for the array
                    var bracketCount = 1 // Start with 1 since we're at the opening bracket
                    var arrayEnd = arrayStart + 1 // Start after the opening bracket
                    var inString = false
                    var escapeNext = false

                    for (i in (arrayStart + 1) until scriptContent.length) {
                        val char = scriptContent[i]

                        if (escapeNext) {
                            escapeNext = false
                            continue
                        }

                        when (char) {
                            '\\' -> escapeNext = true
                            '"' -> inString = !inString
                            '[' -> if (!inString) bracketCount++
                            ']' -> if (!inString) {
                                bracketCount--
                                if (bracketCount == 0) {
                                    arrayEnd = i + 1
                                    break
                                }
                            }
                        }
                    }

                    val imagesJsonString = scriptContent.substring(arrayStart, arrayEnd)
                    // Parse the JSON array, handling escaped quotes
                    images = JSONArray(imagesJsonString.replace("\\\"", "\""))
                    break
                } catch (e: Exception) {
                    continue
                }
            }
        }

        if (images == null) {
            throw ParseException("Unable to find chapter images", chapterUrl)
        }

        return (0 until images.length()).map { i ->
            val imageItem = images.get(i)
            val imageUrl = when (imageItem) {
                is String -> imageItem
                is JSONObject -> imageItem.getString("url")
                else -> throw ParseException("Unexpected image format", chapterUrl)
            }
            ContentPage(
                id = generateUid("$chapterId-$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun getChapters(manga: Content): List<ContentChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val allChapters = mutableListOf<JSONObject>()
        var page = 1

        while (true) {
            val time = 1L
            val path = "/manga/$hashId/chapters"
            val hashToken = ComixHash.generateHash(path, 0, time)
            val chaptersUrl = "https://comix.to/api/v2$path?order[number]=desc&limit=100&page=$page&time=$time&_=$hashToken"
            
            val response = webClient.httpGet(chaptersUrl).parseJson()
            val result = response.getJSONObject("result")
            val items = result.getJSONArray("items")

            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                allChapters.add(items.getJSONObject(i))
            }

            // Check pagination info to see if we have more pages
            val pagination = result.optJSONObject("pagination")
            if (pagination != null) {
                val currentPage = pagination.getInt("current_page")
                val lastPage = pagination.getInt("last_page")
                if (currentPage >= lastPage) break
            }

            page++
        }

        // Group chapters by scanlation team
        val chaptersByTeam = mutableMapOf<String, MutableList<JSONObject>>()
        for (chapter in allChapters) {
            val scanlationGroup = chapter.optJSONObject("scanlation_group")
            val teamName = scanlationGroup?.optString("name", null) ?: "Unknown"
            chaptersByTeam.getOrPut(teamName) { mutableListOf() }.add(chapter)
        }

        // Get all unique chapter numbers
        val allChapterNumbers = allChapters.map { it.getDouble("number").toFloat() }.toSet()

        // Build chapters with branches - each team gets complete chapter list with gaps filled
        val chaptersBuilder = java.util.ArrayList<ContentChapter>(allChapters.size * chaptersByTeam.size)

        for ((teamName, teamChapters) in chaptersByTeam) {
            // Map of chapter numbers this team has
            val teamChapterMap = teamChapters.associateBy { it.getDouble("number").toFloat() }

            for (chapterNumber in allChapterNumbers) {
                val chapterData = teamChapterMap[chapterNumber]
                    ?: allChapters.find { it.getDouble("number").toFloat() == chapterNumber }
                    ?: continue

                val chapterId = chapterData.getLong("chapter_id")
                val number = chapterData.getDouble("number").toFloat()
                val name = chapterData.optString("name", "").nullIfEmpty()
                val createdAt = chapterData.getLong("created_at")
                val scanlationGroup = chapterData.optJSONObject("scanlation_group")
                val actualTeamName = scanlationGroup?.optString("name", null) ?: "Unknown"

                val title = if (name != null) {
                    "Chapter $number: $name"
                } else {
                    "Chapter $number"
                }

                val chapter = ContentChapter(
                    id = generateUid("$teamName-$chapterId"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
                    uploadDate = createdAt * 1000L,
                    source = source,
                    scanlator = actualTeamName,
                    branch = teamName,
                )

                chaptersBuilder.add(chapter)
            }
        }

        return chaptersBuilder.reversed()
    }
}





