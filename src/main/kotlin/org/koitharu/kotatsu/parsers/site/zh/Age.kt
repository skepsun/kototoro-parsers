package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaState
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.YEAR_UNKNOWN
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.Base64
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet

@MangaSourceParser("AGEDM", "AGE动漫", "zh", type = ContentType.VIDEO)
internal class Age(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.AGEDM, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("www.agedm.io", "www.age.tv")

    private val filterTagBundle by lazy { buildFilterTags() }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val (tags, groups) = filterTagBundle
        return MangaListFilterOptions(
            availableTags = tags,
            tagGroups = groups,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.UPCOMING,
            ),
            availableContentTypes = EnumSet.of(ContentType.VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val document = if (!filter.query.isNullOrBlank()) {
            val url = buildSearchUrl(page, filter.query!!)
            webClient.httpGet(url).parseHtml()
        } else {
            val url = buildCatalogUrl(page, order, filter)
            webClient.httpGet(url).parseHtml()
        }
        return parseCatalog(document)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.url.toAbsoluteUrl(domain)
        val document = webClient.httpGet(url).parseHtml()
        val info = document.extractInfoMap()

        val cover = document.selectFirst(".video_detail_cover img")
            ?.attr("data-original")
            .takeIf { !it.isNullOrBlank() }
            ?: document.selectFirst(".video_detail_cover img")
                ?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val title = document.selectFirst(".video_detail_title")
            ?.text()
            ?.ifBlank { null }
            ?: document.selectFirst(".video_detail_name")
                ?.text()
            ?: manga.title

        val description = document.selectFirst(".video_detail_desc")
            ?.asMultilineText()
            ?.ifBlank { null }
            ?: info["简介"]

        val tags = buildTags(
            splitValues(info["剧情类型"]) + splitValues(info["标签"]),
        )

        val authors = buildAuthorSet(info["原作"])
        val altTitles = buildAltTitles(info["原版名称"], info["其他名称"])
        val state = parseState(info["播放状态"]) ?: manga.state
        val chapters = parseChapters(document)

        return manga.copy(
            title = title,
            coverUrl = cover?.asAbsoluteUrl(domain) ?: manga.coverUrl,
            description = description ?: manga.description,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
            altTitles = if (altTitles.isNotEmpty()) altTitles else manga.altTitles,
            state = state,
            chapters = chapters,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val playUrl = chapter.url.toAbsoluteUrl(domain)
        val document = webClient.httpGet(playUrl).parseHtml()
        val iframeSrc = document.selectFirst("#iframeForVideo")
            ?.attr("src")
            ?.takeIf { !it.isNullOrBlank() }
            ?: throw IllegalStateException("Video iframe not found")

        val absoluteIframe = iframeSrc.toAbsoluteUrl(domain)
        val resolvedVideo = try {
            resolveVideoUrl(playUrl, absoluteIframe)
        } catch (_: Exception) {
            null
        } ?: absoluteIframe

        return listOf(
            MangaPage(
                id = generateUid(resolvedVideo),
                url = resolvedVideo,
                preview = null,
                source = source,
            ),
        )
    }

    private fun parseCatalog(document: Document): List<Manga> {
        val result = ArrayList<Manga>()
        val seen = HashSet<Long>()
        val cards = document.select(".card.cata_video_item")
        for (card in cards) {
            val link = card.selectFirst(".video_cover_wrapper a[href]") ?: continue
            val href = link.attr("href").toRelativeUrl(domain)
            val id = generateUid(href)
            if (!seen.add(id)) continue

            val title = link.attr("title")
                .ifBlank { card.selectFirst("h5.card-title a")?.text().orEmpty() }
                .ifBlank { link.text() }
            if (title.isBlank()) continue

            val cover = card.selectFirst(".video_cover_wrapper img")
                ?.run {
                    attr("data-original").takeIf { it.isNotBlank() } ?: attr("src")
                }?.asAbsoluteUrl(domain)

            val info = card.extractInfoMap()
            val description = info["简介"]?.ifBlank { null }
            val tags = buildTags(splitValues(info["剧情类型"]))
            val authors = buildAuthorSet(info["原作"])
            val altTitles = buildAltTitles(info["原版名称"], info["其他名称"])
            val state = parseState(info["播放状态"])

            result += Manga(
                id = id,
                title = title,
                altTitles = altTitles,
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = tags,
                state = state,
                authors = if (authors.isNotEmpty()) authors else emptySet(),
                description = description,
                source = source,
            )
        }
        return result
    }

    private fun parseChapters(document: Document): List<MangaChapter> {
        val chapters = ArrayList<MangaChapter>()
        val buttons = document.select(".video_detail_playlist_wrapper button[data-bs-target]")
        if (buttons.isNotEmpty()) {
            for (button in buttons) {
                val branch = button.ownText().trim().ifEmpty { button.text().trim() }
                val tabId = button.attr("data-bs-target").removePrefix("#")
                val tab = document.selectFirst("#$tabId") ?: continue
                val items = tab.select(".video_detail_episode a[href]")
                for (item in items) {
                    val href = item.attr("href").toRelativeUrl(domain)
                    val title = item.text().trim()
                    chapters += MangaChapter(
                        id = generateUid("$branch|$href"),
                        title = title,
                        number = title.extractChapterNumber(),
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = 0,
                        branch = branch.ifBlank { null },
                        source = source,
                    )
                }
            }
        }

        if (chapters.isEmpty()) {
            val items = document.select(".video_detail_episode a[href]")
            for (item in items) {
                val href = item.attr("href").toRelativeUrl(domain)
                val title = item.text().trim()
                chapters += MangaChapter(
                    id = generateUid(href),
                    title = title,
                    number = title.extractChapterNumber(),
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }
        }

        return chapters
    }

    private fun buildCatalogUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val type = filter.valueFor("type")
        val letter = filter.valueFor("letter")
        val genre = filter.valueFor("genre")
        val resource = filter.valueFor("source")
        val area = filter.valueFor("area")
        val season = filter.valueFor("season")
        val status = filter.valueFor("status")
            ?: filter.states.firstNotNullOfOrNull { stateToSlug(it) }
        val year = when {
            filter.year != YEAR_UNKNOWN -> filter.year.toString()
            else -> filter.valueFor("year")
        }

        val sortSlug = when (order) {
            SortOrder.POPULARITY -> "点击量"
            else -> "time"
        }

        return buildString {
            append("https://").append(domain).append("/catalog/")
            append(type.catalogValue()).append('-')
            append(year.catalogValue()).append('-')
            append(letter.catalogValue()).append('-')
            append(genre.catalogValue()).append('-')
            append(resource.catalogValue()).append('-')
            append(sortSlug.encodeSegment()).append('-')
            append(page).append('-')
            append(area.catalogValue()).append('-')
            append(season.catalogValue()).append('-')
            append(status.catalogValue())
        }
    }

    private fun buildSearchUrl(page: Int, query: String): String {
        return buildString {
            append("https://").append(domain)
                .append("/search?query=").append(query.urlEncoded())
            if (page > 1) {
                append("&page=").append(page)
            }
        }
    }

    private suspend fun resolveVideoUrl(referer: String, iframeUrl: String): String? {
        val iframeHeaders = getRequestHeaders().newBuilder()
            .add("Referer", referer)
            .build()
        val iframeHtml = webClient.httpGet(iframeUrl, iframeHeaders).parseRaw()
        val iframeHttpUrl = iframeUrl.toHttpUrl()
        val payload = parseIframePayload(iframeHtml) ?: return null
        return when (payload) {
            is IframePayload.Direct -> finalizeVideoUrl(iframeHttpUrl, payload.url)
            is IframePayload.Api -> fetchPlayerStream(iframeUrl, iframeHttpUrl, payload)
        }
    }

    private fun parseIframePayload(html: String): IframePayload? {
        val direct = DIRECT_URL_REGEX.find(html)?.groupValues?.getOrNull(1)
        if (!direct.isNullOrBlank() && !direct.equals("about:blank", true)) {
            return IframePayload.Direct(direct)
        }

        val url = IFRAME_URL_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val tValue = IFRAME_T_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val key = IFRAME_KEY_ENCODED_REGEX.find(html)?.groupValues?.getOrNull(1)?.decodeObfuscatedKey()
            ?: IFRAME_KEY_PLAIN_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val act = IFRAME_ACT_REGEX.find(html)?.groupValues?.getOrNull(1)
        val play = IFRAME_PLAY_REGEX.find(html)?.groupValues?.getOrNull(1)
        return IframePayload.Api(url = url, t = tValue, key = key, act = act, play = play)
    }

    private suspend fun fetchPlayerStream(
        iframeUrl: String,
        iframeHttpUrl: HttpUrl,
        params: IframePayload.Api,
    ): String? {
        val apiUrl = iframeHttpUrl.resolve("api.php") ?: return null
        val payload = buildMap {
            put("url", params.url)
            put("t", params.t)
            put("key", params.key)
            put("act", params.act ?: "0")
            put("play", params.play ?: "1")
        }

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", iframeHttpUrl.origin())
            .add("Referer", iframeUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = webClient.httpPost(apiUrl, payload, headers).parseJson()
        val videoUrl = response.optString("url").takeIf { it.isNotBlank() } ?: return null
        return finalizeVideoUrl(iframeHttpUrl, videoUrl)
    }

    private fun finalizeVideoUrl(base: HttpUrl, raw: String): String {
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "${base.scheme}:$raw"
            else -> base.resolve(raw)?.toString() ?: raw
        }
    }

    private fun Element.extractInfoMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        select(".detail_imform_list li").forEach { li ->
            val label = li.selectFirst(".detail_imform_tag")?.text().cleanLabel() ?: return@forEach
            val value = li.selectFirst(".detail_imform_value")?.text()?.trim().orEmpty()
            if (value.isNotBlank()) {
                map[label] = value
            }
        }
        select(".video_detail_info").forEach { info ->
            val label = info.selectFirst("span")?.text().cleanLabel() ?: return@forEach
            val value = info.ownText().ifBlank {
                info.text().substringAfter('：', info.text()).substringAfter(':', "").trim()
            }
            if (value.isNotBlank()) {
                map.putIfAbsent(label, value)
            }
        }
        return map
    }

    private fun buildTags(values: Collection<String>): Set<MangaTag> {
        if (values.isEmpty()) return emptySet()
        val result = LinkedHashSet<MangaTag>()
        values.forEach { value ->
            if (value.isNotBlank()) {
                result += MangaTag(value, value, source)
            }
        }
        return result
    }

    private fun buildAuthorSet(raw: String?): Set<String> {
        val values = splitValues(raw)
        if (values.isEmpty()) return emptySet()
        return LinkedHashSet(values)
    }

    private fun buildAltTitles(vararg values: String?): Set<String> {
        val result = LinkedHashSet<String>()
        values.forEach { value ->
            val cleaned = value?.trim()
            if (!cleaned.isNullOrEmpty()) {
                result += cleaned
            }
        }
        return result
    }

    private fun parseState(raw: String?): MangaState? = when {
        raw.isNullOrBlank() -> null
        raw.contains("连载") -> MangaState.ONGOING
        raw.contains("完结") -> MangaState.FINISHED
        raw.contains("未播放") || raw.contains("未开播") -> MangaState.UPCOMING
        else -> null
    }

    private fun Element.asMultilineText(): String {
        val html = html().replace(BR_REGEX, "\n")
        return Jsoup.parse(html).text().trim()
    }

    private fun String.extractChapterNumber(): Float {
        val match = NUMBER_REGEX.find(this) ?: return 0f
        return match.value.toFloatOrNull() ?: 0f
    }

    private fun String?.catalogValue(): String {
        val value = this?.takeIf { it.isNotBlank() } ?: return "all"
        if (value.equals("all", true)) return "all"
        return value.encodeSegment()
    }

    private fun String.encodeSegment(): String = urlEncoded().replace("+", "%20")

    private fun String?.cleanLabel(): String? = this
        ?.replace("：", "")
        ?.replace(":", "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun String?.asAbsoluteUrl(baseDomain: String): String? =
        this?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(baseDomain)

    private fun splitValues(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(' ', '、', '，', ',', '/', '；', ';', '|', '｜')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun MangaListFilter.valueFor(prefix: String): String? {
        return tags.firstOrNull { it.key.startsWith("$prefix:") }
            ?.key
            ?.substringAfter(':')
    }

    private fun stateToSlug(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "连载"
        MangaState.FINISHED -> "完结"
        MangaState.UPCOMING -> "未播放"
        else -> null
    }

    private sealed interface IframePayload {
        data class Direct(val url: String) : IframePayload
        data class Api(
            val url: String,
            val t: String,
            val key: String,
            val act: String?,
            val play: String?,
        ) : IframePayload
    }

    private fun String.decodeObfuscatedKey(): String? = runCatching {
        val decoded = Base64.getDecoder().decode(this)
        val text = decoded.toString(Charsets.UTF_8)
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            var matched = false
            for ((token, value) in OKOK_MAP) {
                if (text.startsWith(token, index)) {
                    builder.append(value)
                    index += token.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                builder.append(text[index])
                index++
            }
        }
        builder.toString()
    }.getOrNull()

    private fun HttpUrl.origin(): String {
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        return if (port == defaultPort || port == -1) {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
    }

    private fun buildFilterTags(): Pair<Set<MangaTag>, List<MangaTagGroup>> {
        val tags = LinkedHashSet<MangaTag>()
        val groups = ArrayList<MangaTagGroup>()

        fun addGroup(name: String, entries: Iterable<Pair<String, String>>) {
            val groupTags = LinkedHashSet<MangaTag>()
            entries.forEach { (title, key) ->
                val t = MangaTag(title, key, source)
                groupTags.add(t)
                tags.add(t)
            }
            if (groupTags.isNotEmpty()) {
                groups += MangaTagGroup(name, groupTags)
            }
        }

        addGroup("类型", AGE_TYPES.map { it to "type:$it" })
        addGroup("字母", AGE_LETTERS.map { it to "letter:$it" })
        addGroup("年份", AGE_YEARS.map { it to "year:$it" })
        addGroup("标签", AGE_GENRES.map { it to "genre:$it" })
        addGroup("资源", AGE_RESOURCES.map { it to "source:$it" })
        addGroup("地区", AGE_AREAS.map { it to "area:$it" })
        addGroup("季度", AGE_SEASONS.map { (title, value) -> title to "season:$value" })
        addGroup("状态", AGE_STATUSES.map { it to "status:$it" })

        return tags to groups
    }

    private companion object {
        private val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val BR_REGEX = Regex("(?i)<br\\s*/?>")
        private val DIRECT_URL_REGEX = Regex("""(?i)(?:var|let|const)\s+Vurl\s*=\s*['"]([^'"]+)""")
        private val IFRAME_URL_REGEX = Regex("""var\s+url\s*=\s*"([^"]+)"""")
        private val IFRAME_T_REGEX = Regex("""var\s+t\s*=\s*"([^"]+)"""")
        private val IFRAME_KEY_ENCODED_REGEX = Regex("""var\s+key\s*=\s*OKOK\("([^"]+)""")
        private val IFRAME_KEY_PLAIN_REGEX = Regex("""var\s+key\s*=\s*"([^"]+)"""")
        private val IFRAME_ACT_REGEX = Regex("""var\s+act\s*=\s*"([^"]+)"""")
        private val IFRAME_PLAY_REGEX = Regex("""var\s+play\s*=\s*"([^"]+)"""")

        private val AGE_TYPES = listOf("TV", "剧场版", "OVA")
        private val AGE_LETTERS = ('A'..'Z').map { it.toString() }
        private val AGE_YEARS = (2025 downTo 2001).map { it.toString() } + "2000以前"
        private val AGE_AREAS = listOf("日本", "中国", "欧美")
        private val AGE_SEASONS = listOf("1月" to "1", "4月" to "4", "7月" to "7", "10月" to "10")
        private val AGE_STATUSES = listOf("连载", "完结", "未播放")
        private val AGE_RESOURCES = listOf("BDRIP", "AGE-RIP")
        private val AGE_GENRES = listOf(
            "搞笑", "运动", "励志", "热血", "战斗", "竞技", "校园", "青春", "爱情", "恋爱",
            "冒险", "后宫", "百合", "治愈", "萝莉", "魔法", "悬疑", "推理", "奇幻", "科幻",
            "游戏", "神魔", "恐怖", "血腥", "机战", "战争", "犯罪", "历史", "社会", "职场",
            "剧情", "伪娘", "耽美", "童年", "教育", "亲子", "真人", "歌舞", "肉番", "美少女",
            "轻小说", "吸血鬼", "女性向", "泡面番", "欢乐向",
        )

        private val OKOK_MAP = linkedMapOf(
            "0Oo0o0Oo" to 'a',
            "1O0bO001" to 'b',
            "1OoCcO1" to 'c',
            "3O0dO0O3" to 'd',
            "4OoEeO4" to 'e',
            "5O0fO0O5" to 'f',
            "6OoGgO6" to 'g',
            "7O0hO0O7" to 'h',
            "8OoIiO8" to 'i',
            "9O0jO0O9" to 'j',
            "0OoKkO0" to 'k',
            "1O0lO0O1" to 'l',
            "2OoMmO2" to 'm',
            "3O0nO0O3" to 'n',
            "4OoOoO4" to 'o',
            "5O0pO0O5" to 'p',
            "6OoQqO6" to 'q',
            "7O0rO0O7" to 'r',
            "8OoSsO8" to 's',
            "9O0tOoO9" to 't',
            "0OoUuO0" to 'u',
            "1O0vO0O1" to 'v',
            "2OoWwO2" to 'w',
            "3O0xO0O3" to 'x',
            "4OoYyO4" to 'y',
            "5O0zO0O5" to 'z',
            "0OoAAO0" to 'A',
            "1O0BBO1" to 'B',
            "2OoCCO2" to 'C',
            "3O0DDO3" to 'D',
            "4OoEEO4" to 'E',
            "5O0FFO5" to 'F',
            "6OoGGO6" to 'G',
            "7O0HHO7" to 'H',
            "8OoIIO8" to 'I',
            "9O0JJO9" to 'J',
            "0OoKKO0" to 'K',
            "1O0LLO1" to 'L',
            "2OoMMO2" to 'M',
            "3O0NNO3" to 'N',
            "4OoOOO4" to 'O',
            "5O0PPO5" to 'P',
            "6OoQQO6" to 'Q',
            "7O0RRO7" to 'R',
            "8OoSSO8" to 'S',
            "9O0TTO9" to 'T',
            "0OoUO0" to 'U',
            "1O0VVO1" to 'V',
            "2OoWWO2" to 'W',
            "3O0XXO3" to 'X',
            "4OoYYO4" to 'Y',
            "5O0ZZO5" to 'Z',
        )
    }
}
