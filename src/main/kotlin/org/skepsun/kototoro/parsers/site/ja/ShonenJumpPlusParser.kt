@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseJsonObject
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import kotlin.random.Random

/**
 * 少年ジャンプ＋ (shonenjumpplus.com) - GraphQL API
 * 参考 venera-configs/shonen_jump_plus.js
 */
@Broken()
@ContentSourceParser("SHONEN_JUMP_PLUS", "少年ジャンプ＋", "ja")
internal class ShonenJumpPlusParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.SHONEN_JUMP_PLUS, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("shonenjumpplus.com")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    private val apiBase = "https://${config[configKeyDomain]}/api/v1"
    private var bearerToken: String? = null
    private var tokenExpiry: Long = 0L
    private var deviceId: String = generateDeviceId()
    private val appVersion = "4.0.24"

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        ContentListFilterOptions(availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE))

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Origin", "https://${config[configKeyDomain]}")
        .add("Referer", "https://${config[configKeyDomain]}/")
        .add("X-Giga-Device-Id", deviceId)
        .add("User-Agent", "ShonenJumpPlus-Android/$appVersion")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        if (page > 1) return emptyList()
        ensureAuth()
        val res = graphqlRequest("HomeCacheable", JSONObject())
        val sections = res.optJSONObject("data")
            ?.optJSONArray("homeSections")
            ?: return emptyList()

        // Extract daily ranking items
        val rankingItems = mutableListOf<JSONObject>()
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            if (sec.optString("__typename") != "DailyRankingSection") continue
            val dailyRankings = sec.optJSONArray("dailyRankings") ?: continue
            for (j in 0 until dailyRankings.length()) {
                val ranking = dailyRankings.optJSONObject(j)
                    ?.optJSONObject("ranking") ?: continue
                val items = ranking.optJSONObject("items")
                    ?.optJSONArray("edges") ?: continue
                for (k in 0 until items.length()) {
                    val edge = items.optJSONObject(k) ?: continue
                    val node = edge.optJSONObject("node") ?: continue
                    if (node.optString("__typename") == "DailyRankingValidItem") {
                        rankingItems.add(node)
                    }
                }
            }
        }

        val seen = HashSet<String>()
        val comics = mutableListOf<Content>()
        rankingItems.forEach { item ->
            val rank = item.optJSONObject("label")?.optInt("rank")
            val viewCount = item.optJSONObject("label")?.optString("viewCount")
            val product = item.optJSONObject("product") ?: return@forEach
            val series = product.optJSONObject("series") ?: return@forEach
            val id = series.optString("databaseId")
            val title = series.optString("title")
            if (id.isEmpty() || title.isEmpty() || !seen.add(id)) return@forEach
            val coverTemplate = series.optString("squareThumbnailUriTemplate")
                .ifEmpty { series.optString("horizontalThumbnailUriTemplate") }
            val cover = replaceCoverUrl(coverTemplate, 500)
            comics.add(
                Content(
                    id = generateUid(id),
                    url = id,
                    publicUrl = "",
                    coverUrl = cover,
                    title = title,
                    altTitles = emptySet(),
                    rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE,
                    description = "Ranking: ${rank ?: "-"} · Views: ${viewCount ?: "-"}",
                )
            )
        }
        return comics
    }

    private suspend fun search(keyword: String, page: Int): List<Content> {
        ensureAuth()
        val payload = JSONObject().apply {
            put("after", JSONObject.NULL)
            put("keyword", keyword)
        }
        val res = graphqlRequest("SearchResult", payload)
        val edges = res.optJSONObject("data")
            ?.optJSONObject("search")
            ?.optJSONArray("edges") ?: return emptyList()
        val result = mutableListOf<Content>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val type = node.optString("__typename")
            val id = node.optString("databaseId")
            val title = node.optString("title")
            if (id.isEmpty() || title.isEmpty()) continue
            val coverTemplate = when (type) {
                "Series" -> node.optString("thumbnailUriTemplate")
                "MagazineLabel" -> node.optJSONObject("latestIssue")?.optString("thumbnailUriTemplate")
                    ?: node.optString("thumbnailUriTemplate")
                else -> ""
            }
            val cover = replaceCoverUrl(coverTemplate, 1500)
            val authors = node.optJSONObject("author")?.optString("name")
                ?.split(Regex("\\s*/\\s*"))?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            result.add(
                Content(
                    id = generateUid(id),
                    url = id,
                    publicUrl = "",
                    coverUrl = cover,
                    title = title,
                    altTitles = emptySet(),
                    rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                    tags = authors.map { ContentTag(it, it, source) }.toSet(),
                    authors = authors,
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE,
                    description = node.optString("description"),
                )
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Content): Content {
        ensureAuth()
        val series = fetchSeriesDetail(manga.url) ?: return manga
        val episodes = fetchEpisodes(manga.url)
        val chapters = episodes.mapIndexedNotNull { index, ep ->
            val epId = ep.optString("databaseId")
            val title = ep.optString("title")
            if (epId.isEmpty()) null else ContentChapter(
                id = generateUid("$epId-${manga.id}"),
                url = "${manga.url}@$epId",
                title = title.ifEmpty { "Ch ${index + 1}" },
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        val authors = series.optJSONObject("author")?.optString("name")
            ?.split(Regex("\\s*/\\s*"))?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val cover = replaceCoverUrl(series.optString("thumbnailUriTemplate"), 1500)
        val description = series.optString("description")
        val tags = authors.map { ContentTag(it, it, source) }.toSet()

        val openAt = series.optString("openAt")
        val latestPublishAt = episodes.maxOfOrNull { it.optString("publishedAt") ?: "" } ?: ""
        val updateDate = listOf(openAt, latestPublishAt).filter { it.isNotEmpty() }.maxOrNull().orEmpty()

        return manga.copy(
            title = series.optString("title").ifEmpty { manga.title },
            coverUrl = cover.ifEmpty { manga.coverUrl },
            description = if (description.isNotEmpty()) description else manga.description,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        ).let { updated ->
            if (updateDate.isNotEmpty()) {
                val withTag = updated.tags + ContentTag("Update $updateDate", "Update $updateDate", source)
                updated.copy(tags = withTag)
            } else updated
        }
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        ensureAuth()
        val parts = chapter.url.split("@")
        if (parts.size < 2) return emptyList()
        val episodeId = parts[1]
        val episodeData = fetchEpisodePages(episodeId) ?: return emptyList()
        val purchaseInfo = episodeData.optJSONObject("purchaseInfo")
        if (!isEpisodeAccessible(purchaseInfo)) {
            val purchasableFree = purchaseInfo?.optBoolean("purchasableViaOnetimeFree") == true
            val rentable = purchaseInfo?.optBoolean("rentable") == true
            val unitPrice = purchaseInfo?.optInt("unitPrice") ?: 0
            if (purchasableFree) {
                consumeOnetimeFree(episodeId)
                return getPages(chapter)
            } else if (rentable) {
                rentChapter(episodeId, unitPrice)
                return getPages(chapter)
            }
            return emptyList()
        }

        val token = episodeData.optString("pageImageToken")
        val edges = episodeData.optJSONObject("pageImages")
            ?.optJSONArray("edges") ?: return emptyList()
        val pages = mutableListOf<ContentPage>()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val src = edge.optJSONObject("node")?.optString("src").orEmpty()
            if (src.isNotEmpty() && token.isNotEmpty()) {
                val url = "$src?token=$token"
                pages.add(
                    ContentPage(
                        id = generateUid("$url-$i"),
                        url = url,
                        preview = url,
                        source = source,
                    )
                )
            }
        }
        return pages
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private fun replaceCoverUrl(template: String?, size: Int): String {
        if (template.isNullOrEmpty()) return ""
        return template.replace("{height}", size.toString()).replace("{width}", size.toString())
    }

    private fun isEpisodeAccessible(purchaseInfo: JSONObject?): Boolean {
        purchaseInfo ?: return true
        return purchaseInfo.optBoolean("isFree")
            || purchaseInfo.optBoolean("hasPurchased")
            || purchaseInfo.optBoolean("hasRented")
    }

    private suspend fun consumeOnetimeFree(episodeId: String) {
        val input = JSONObject().apply { put("id", episodeId) }
        val payload = JSONObject().apply { put("input", input) }
        graphqlRequest("ConsumeOnetimeFree", payload)
    }

    private suspend fun rentChapter(episodeId: String, unitPrice: Int) {
        val input = JSONObject().apply {
            put("id", episodeId)
            put("unitPrice", unitPrice)
        }
        val payload = JSONObject().apply { put("input", input) }
        graphqlRequest("Rent", payload)
    }

    private suspend fun fetchSeriesDetail(id: String): JSONObject? {
        val payload = JSONObject().apply { put("id", id) }
        val res = graphqlRequest("SeriesDetail", payload)
        return res.optJSONObject("data")?.optJSONObject("series")
    }

    private suspend fun fetchEpisodes(id: String): List<JSONObject> {
        val payload = JSONObject().apply {
            put("id", id)
            put("episodeOffset", 0)
            put("episodeFirst", 1500)
            put("episodeSort", "NUMBER_ASC")
        }
        val res = graphqlRequest("SeriesDetailEpisodeList", payload)
        val edges = res.optJSONObject("data")
            ?.optJSONObject("series")
            ?.optJSONObject("episodes")
            ?.optJSONArray("edges") ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until edges.length()) {
            edges.optJSONObject(i)?.optJSONObject("node")?.let { list.add(it) }
        }
        return list
    }

    private suspend fun fetchEpisodePages(episodeId: String): JSONObject? {
        val payload = JSONObject().apply { put("episodeID", episodeId) }
        val res = graphqlRequest("EpisodeViewerConditionallyCacheable", payload)
        return res.optJSONObject("data")?.optJSONObject("episode")
    }

    private suspend fun graphqlRequest(operationName: String, variables: JSONObject): JSONObject {
        ensureAuth()
        val payload = JSONObject().apply {
            put("operationName", operationName)
            put("variables", variables)
            put("query", GRAPHQL_QUERIES[operationName])
        }
        val headers = getRequestHeaders().newBuilder()
            .add("Authorization", "Bearer ${bearerToken.orEmpty()}")
            .add("Accept", "application/json")
            .add("X-APOLLO-OPERATION-NAME", operationName)
            .add("Content-Type", "application/json")
            .build()

        val resp = webClient.httpPost("${apiBase}/graphql?opname=$operationName".toHttpUrl(), payload, headers)
        return resp.parseJsonObject()
    }

    private suspend fun ensureAuth() {
        if (bearerToken.isNullOrEmpty() || System.currentTimeMillis() >= tokenExpiry) {
            fetchBearerToken()
        }
    }

    private suspend fun fetchBearerToken() {
        val resp = webClient.httpPost("$apiBase/user_account/access_token".toHttpUrl(), "", getRequestHeaders())
        val body = resp.body?.string().orEmpty()
        val obj = JSONObject(body)
        bearerToken = obj.optString("access_token")
        tokenExpiry = System.currentTimeMillis() + TOKEN_TTL_MS
    }

    private fun generateDeviceId(): String {
        val chars = "0123456789abcdef"
        return buildString {
            repeat(16) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }

    companion object {
        private const val TOKEN_TTL_MS = 60 * 60 * 1000L

        private val GRAPHQL_QUERIES = mapOf(
            "SearchResult" to """
                query SearchResult(${'$'}after: String, ${'$'}keyword: String!) {
                    search(after: ${'$'}after, first: 50, keyword: ${'$'}keyword, types: [SERIES,MAGAZINE_LABEL]) {
                        pageInfo { hasNextPage endCursor }
                        edges {
                            node {
                                __typename
                                ... on Series { id databaseId title thumbnailUriTemplate author { name } description }
                                ... on MagazineLabel { id databaseId title thumbnailUriTemplate latestIssue { thumbnailUriTemplate } }
                            }
                        }
                    }
                }
            """.trimIndent(),
            "SeriesDetail" to """
                query SeriesDetail(${'$'}id: String!) {
                    series(databaseId: ${'$'}id) {
                        id databaseId title thumbnailUriTemplate
                        author { name }
                        description
                        hashtags serialUpdateScheduleLabel
                        openAt
                        publisherId
                    }
                }
            """.trimIndent(),
            "SeriesDetailEpisodeList" to """
                query SeriesDetailEpisodeList(${'$'}id: String!, ${'$'}episodeOffset: Int, ${'$'}episodeFirst: Int, ${'$'}episodeSort: ReadableProductSorting) {
                    series(databaseId: ${'$'}id) {
                        episodes: readableProducts(types: [EPISODE], first: ${'$'}episodeFirst, offset: ${'$'}episodeOffset, sort: ${'$'}episodeSort) {
                            edges { node { databaseId title publishedAt } }
                        }
                    }
                }
            """.trimIndent(),
            "EpisodeViewerConditionallyCacheable" to """
                query EpisodeViewerConditionallyCacheable(${'$'}episodeID: String!) {
                    episode(databaseId: ${'$'}episodeID) {
                        id pageImages { edges { node { src } } } pageImageToken
                        purchaseInfo {
                            isFree hasPurchased hasRented
                            purchasableViaOnetimeFree rentable unitPrice
                        }
                    }
                }
            """.trimIndent(),
            "ConsumeOnetimeFree" to """
                mutation ConsumeOnetimeFree(${'$'}input: ConsumeOnetimeFreeInput!) {
                    consumeOnetimeFree(input: ${'$'}input) { isSuccess }
                }
            """.trimIndent(),
            "Rent" to """
                mutation Rent(${'$'}input: RentInput!) {
                    rent(input: ${'$'}input) {
                        userAccount { databaseId }
                    }
                }
            """.trimIndent(),
            "HomeCacheable" to """
                query HomeCacheable {
                    homeSections {
                        __typename
                        ...DailyRankingSection
                    }
                }
                fragment DesignSectionImage on DesignSectionImage {
                    imageUrl width height
                }
                fragment SerialInfoIcon on SerialInfo {
                    isOriginal isIndies
                }
                fragment DailyRankingSeries on Series {
                    id databaseId publisherId title
                    horizontalThumbnailUriTemplate: subThumbnailUri(type: HORIZONTAL_WITH_LOGO)
                    squareThumbnailUriTemplate: subThumbnailUri(type: SQUARE_WITHOUT_LOGO)
                    isNewOngoing supportsOnetimeFree
                    serialInfo {
                        __typename ...SerialInfoIcon
                        status isTrial
                    }
                    jamEpisodeWorkType
                }
                fragment DailyRankingItem on DailyRankingItem {
                    __typename
                    ... on DailyRankingValidItem {
                        product {
                            __typename
                            ... on Episode {
                                id databaseId publisherId commentCount
                                series {
                                    __typename ...DailyRankingSeries
                                }
                            }
                            ... on SpecialContent {
                                publisherId linkUrl
                                series {
                                    __typename ...DailyRankingSeries
                                }
                            }
                        }
                        badge { name label }
                        label rank viewCount
                    }
                    ... on DailyRankingInvalidItem {
                        publisherWorkId
                    }
                }
                fragment DailyRanking on DailyRanking {
                    date firstPositionSeriesId
                    items {
                        edges {
                            node {
                                __typename ...DailyRankingItem
                            }
                        }
                    }
                }
                fragment DailyRankingSection on DailyRankingSection {
                    title
                    titleImage {
                        __typename ...DesignSectionImage
                    }
                    dailyRankings {
                        ranking {
                            __typename ...DailyRanking
                        }
                    }
                }
            """.trimIndent(),
        )
    }
}
