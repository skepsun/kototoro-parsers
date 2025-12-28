@file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.zh

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Manga
import org.skepsun.kototoro.parsers.model.MangaChapter
import org.skepsun.kototoro.parsers.model.MangaListFilter
import org.skepsun.kototoro.parsers.model.MangaListFilterCapabilities
import org.skepsun.kototoro.parsers.model.MangaListFilterOptions
import org.skepsun.kototoro.parsers.model.MangaPage
import org.skepsun.kototoro.parsers.model.MangaParserSource
import org.skepsun.kototoro.parsers.model.MangaTag
import org.skepsun.kototoro.parsers.model.MangaTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.urlEncoded
import java.util.EnumSet
import java.util.Locale

/**
 * 优酷漫画 (ykmh.net)
 */
@MangaSourceParser("YKMH", "优酷漫画", "zh")
internal class YkmhParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.YKMH, pageSize = 20) {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("www.ykmh.net")
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.UPDATED, SortOrder.POPULARITY)

	private val categoryMap = linkedMapOf(
		"全部" to "",
		"爱情" to "aiqing",
		"剧情" to "juqing",
		"欢乐向" to "huanlexiang",
		"格斗" to "gedou",
		"科幻" to "kehuan",
		"伪娘" to "weiniang",
		"节操" to "jiecao",
		"恐怖" to "kongbu",
		"悬疑" to "xuanyi",
		"冒险" to "maoxian",
		"校园" to "xiaoyuan",
		"治愈" to "zhiyu",
		"恋爱" to "lianai",
		"奇幻" to "qihuan",
		"热血" to "rexue",
		"限制级" to "xianzhiji",
		"魔法" to "mofa",
		"后宫" to "hougong",
		"魔幻" to "mohuan",
		"轻小说" to "qingxiaoshuo",
		"震撼" to "zhenhan",
		"纯爱" to "chunai",
		"少女" to "shaonv",
		"战争" to "zhanzheng",
		"武侠" to "wuxia",
		"搞笑" to "gaoxiao",
		"神鬼" to "shengui",
		"竞技" to "jingji",
		"幻想" to "huanxiang",
		"神魔" to "shenmo",
		"灵异" to "lingyi",
		"百合" to "baihe",
		"运动" to "yundong",
		"体育" to "tiyu",
		"惊悚" to "jingsong",
		"日常" to "richang",
		"绅士" to "shenshi",
		"颜艺" to "yanyi",
		"生活" to "shenghuo",
		"四格" to "sige",
		"萌系" to "mengxi",
		"都市" to "dushi",
		"同人" to "tongren",
		"推理" to "tuili",
		"耽美" to "danmei",
		"卖肉" to "mairou",
		"职场" to "zhichang",
		"侦探" to "zhentan",
		"战斗" to "zhandou",
		"爆笑" to "baoxiao",
		"总裁" to "zongcai",
		"美食" to "meishi",
		"性转换" to "xingzhuanhuan",
		"励志" to "lizhi",
		"西方魔幻" to "xifangmohuan",
		"改编" to "gaibian",
		"其他" to "qita",
		"宅系" to "zhaixi",
		"机战" to "jizhan",
		"乙女" to "yinv",
		"秀吉" to "xiuji",
		"舰娘" to "jianniang",
		"历史" to "lishi",
		"猎奇" to "lieqi",
		"社会" to "shehui",
		"青春" to "qingchun",
		"高清单行" to "gaoqingdanxing",
		"东方" to "dongfang",
		"彩虹" to "caihong",
		"少年" to "shaonian",
		"泡泡" to "paopao",
		"宫斗" to "gongdou",
		"动作" to "dongzuo",
		"青年" to "qingnian",
		"虐心" to "nuexin",
		"泛爱" to "fanai",
		"机甲" to "jijia",
		"装逼" to "zhuangbi",
		"#愛情" to "aiqing2",
		"#長條" to "zhangtiao",
		"#穿越" to "chuanyue",
		"#生活" to "shenghuo2",
		"TS" to "TS",
		"#耽美" to "danmei2",
		"#后宫" to "hougong2",
		"#节操" to "jiecao2",
		"#轻小说" to "qingxiaoshuo2",
		"#奇幻" to "qihuan2",
		"#悬疑" to "xuanyi2",
		"#校园" to "xiaoyuan2",
		"#爱情" to "aiqing3",
		"#百合" to "baihe2",
		"#长条" to "changtiao",
		"#冒险" to "maoxian2",
		"#搞笑" to "gaoxiao2",
		"#欢乐向" to "huanlexiang2",
		"#职场" to "zhichang2",
		"#神鬼" to "shengui2",
		"#生存" to "shengcun",
		"#治愈" to "zhiyu2",
		"#竞技" to "jingji2",
		"#美食" to "meishi2",
		"#其他" to "qita2",
		"#机战" to "jizhan2",
		"#战争" to "zhanzheng2",
		"#科幻" to "kehuan2",
		"#四格" to "sige2",
		"#武侠" to "wuxia2",
		"#重生" to "zhongsheng",
		"#性转换" to "xingzhuanhuan2",
		"#热血" to "rexue2",
		"#伪娘" to "weiniang2",
		"#异世界" to "yishijie",
		"#萌系" to "mengxi2",
		"#格斗" to "gedou2",
		"#励志" to "lizhi2",
		"#都市" to "dushi2",
		"#惊悚" to "jingsong2",
		"#侦探" to "zhentan2",
		"#舰娘" to "jianniang2",
		"#音乐舞蹈" to "yinyuewudao2",
		"#TL" to "TL",
		"#AA" to "AA",
		"#转生" to "zhuansheng",
		"#魔幻" to "mohuan2",
		"---" to "unknown2",
		"#彩色" to "caise",
		"福瑞" to "furui",
		"#FATE" to "FATE",
		"西幻" to "xihuan",
		"#C99" to "C99",
		"#C101" to "C101",
		"#历史" to "lishi2",
		"#C102" to "C102",
		"#无修正" to "wuxiuzheng",
		"#C103" to "C103",
		"#东方" to "dongfang2",
		"栏目" to "lanmu",
		"异世界" to "yishijie2",
		"恶搞" to "egao",
		"霸总" to "bazong",
		"古风" to "gufeng",
		"穿越" to "chuanyue2",
		"玄幻" to "xuanhuan",
		"日更" to "rigeng",
		"吸血" to "xixie",
		"萝莉" to "luoli",
		"漫改" to "mangai",
		"唯美" to "weimei",
		"宅男腐女" to "zhainanfunv",
		"老师" to "laoshi",
		"诱惑" to "youhuo",
		"杂志" to "zazhi",
		"脑洞" to "naodong",
		"#恐怖" to "kongbu2",
		"#C105" to "C105",
		"权谋" to "quanmou",
		"大陆" to "dalu",
		"日本" to "riben",
		"香港" to "hongkong",
		"台湾" to "taiwan",
		"欧美" to "oumei",
		"韩国" to "hanguo",
		"儿童漫画" to "ertong",
		"少年漫画" to "shaonian",
		"少女漫画" to "shaonv",
		"青年漫画" to "qingnian",
		"已完结" to "wanjie",
		"连载中" to "lianzai",
	)

	private val tagGroups: List<MangaTagGroup> by lazy {
		val allTags = categoryMap.map { MangaTag(it.key, it.value, source) }.toSet()
		val mainTags = categoryMap.filterKeys { !it.startsWith("#") }.map { MangaTag(it.key, it.value, source) }.toSet()
		val hashTags = categoryMap.filterKeys { it.startsWith("#") }.map { MangaTag(it.key, it.value, source) }.toSet()
		buildList {
			if (mainTags.isNotEmpty()) add(MangaTagGroup("分类", mainTags))
			if (hashTags.isNotEmpty()) add(MangaTagGroup("话题", hashTags))
			if (isEmpty() && allTags.isNotEmpty()) add(MangaTagGroup("分类", allTags))
		}
	}

	override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
			availableTags = categoryMap.map { MangaTag(it.key, it.value, source) }.toSet(),
			tagGroups = tagGroups,
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
        )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .add("Referer", "https://${domain}/")
        .build()

	private fun baseUrl(): String = "https://${domain}"
	private fun mobileBaseUrl(): String = "https://m.ykmh.net"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query!!, page)
        }
        val category = filter.tags.firstOrNull()?.key.orEmpty()
        val sortKey = when (order) {
            SortOrder.POPULARITY -> "click"
            SortOrder.NEWEST -> "update"
            else -> "post"
        }
		val url = if (category.isEmpty()) {
			"${baseUrl()}/list/$sortKey/?page=$page"
		} else {
			"${baseUrl()}/list/$category/$sortKey/$page/"
		}
        val resp = webClient.httpGet(url, getRequestHeaders())
        if (!resp.isSuccessful) return emptyList()
        val doc = resp.parseHtml()
		val items = doc.select("li.list-comic, ul.ebook-ol > li, ul.ebook-list > li, .ebooks ul.list-unstyled > li")
        if (items.isNotEmpty()) {
            return parseList(items)
        }
        return parseLatest(doc)
	}

	private suspend fun search(keyword: String, page: Int): List<Manga> {
		val resp = webClient.httpGet(
			"${baseUrl()}/search/?keywords=${keyword.urlEncoded()}&page=$page",
			getRequestHeaders()
		)
		if (!resp.isSuccessful) return emptyList()
		val doc = resp.parseHtml()
		val items = doc.select("li.list-comic, div.ebook-seealso div.ebook-ol > li, ul.ebook-ol > li")
		return parseList(items)
	}

	private fun parseLatest(doc: Document): List<Manga> {
		val items = doc.select(
			".ebooks ul.list-unstyled > li," +
				"ul.ebook-ol > li," +
				"ul.ebook-list > li," +
				".mod-book-block li," +
				".cy_list li," +
				"div.ebook-seealso li," +
				"li.list-comic"
		)
		if (items.isNotEmpty()) {
			return parseList(items)
		}
		// Fallback to carousel keywords if no items
		val list = mutableListOf<Manga>()
		val regex = Regex("<li data-key=\"(\\d+)\"><a href=\"(https://www\\.ykmh\\.net/manhua/[^\"]+)\"[^>]*>([^<]+)</a></li>")
		regex.findAll(doc.html()).take(10).forEach { m ->
			val href = m.groupValues[2]
			val title = m.groupValues[3]
			// 规范化 URL：移除域名前缀，确保使用相对路径
			val relUrl = normalizeMangaUrl(href.removePrefix(baseUrl()).removePrefix(mobileBaseUrl()))
			val generatedId = generateUid(relUrl)
			println("[YkmhParser] parseLatest: title='$title' href='$href' relUrl='$relUrl' generatedId=$generatedId")
			list.add(
				Manga(
					id = generatedId,
					url = relUrl,
					publicUrl = href,
					coverUrl = "${baseUrl()}/images/default/cover.png",
					title = title,
					altTitles = emptySet(),
					rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
					tags = setOf(MangaTag("热门推荐", "热门推荐", source)),
					authors = emptySet(),
					state = null,
					source = source,
					contentRating = ContentRating.SAFE,
					description = "",
				)
			)
		}
		return list
	}

	private fun parseList(items: Iterable<org.jsoup.nodes.Element>): List<Manga> {
		val list = mutableListOf<Manga>()
		items.forEach { item ->
			val link = item.selectFirst("a[href^=/manhua/]")
				?: item.selectFirst("a[href^=/book/]")
				?: item.selectFirst("a.image-link")
				?: item.selectFirst("a.comic_img")
				?: return@forEach
			val href = link.attr("href")
			val title = item.selectFirst("h3 a")?.text()?.trim()
				?.ifEmpty { link.attr("title") }
				?.ifEmpty { item.selectFirst("p a")?.text()?.trim() }
				?.ifEmpty { link.text().trim() }
				?: link.attr("title").ifEmpty { link.text().trim() }
			val coverEl = item.selectFirst("img")
			val cover = coverEl?.attr("data-original")
				?.ifEmpty { coverEl.attr("data-src") }
				?.ifEmpty { coverEl.attr("data-echo") }
				?.ifEmpty { coverEl.attr("src") }
			if (href.isNotEmpty() && title.isNotEmpty()) {
				val absolute = when {
					href.startsWith("http") -> href
					href.startsWith("/") -> baseUrl() + href
					else -> "${baseUrl()}/$href"
				}
				val rawRelUrl = absolute.removePrefix(baseUrl()).removePrefix(mobileBaseUrl())
				// 规范化 URL：移除章节后缀（如 /299812.html），只保留漫画路径
				val relUrl = normalizeMangaUrl(rawRelUrl)
				val uidSource = relUrl.ifEmpty { href }
				val generatedId = generateUid(uidSource)
				
				// 详细日志：追踪 ID 生成
				println("[YkmhParser] parseList: title='$title' href='$href' absolute='$absolute' rawRelUrl='$rawRelUrl' relUrl='$relUrl' uidSource='$uidSource' generatedId=$generatedId")
				
				val coverAbs = cover?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else baseUrl() + it }
				list.add(
					Manga(
						id = generatedId,
						url = relUrl.ifEmpty { href },
						publicUrl = absolute,
						coverUrl = coverAbs,
						title = title,
						altTitles = emptySet(),
						rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
						tags = emptySet(),
						authors = emptySet(),
                        state = null,
                        source = source,
                        contentRating = ContentRating.SAFE,
                    )

                )
            }
        }
        return list
    }


    override suspend fun getDetails(manga: Manga): Manga {
		val href = if (manga.url.startsWith("http")) manga.url else baseUrl() + manga.url
		val mobileHref = when {
			href.startsWith("${baseUrl()}/") -> href.replace(baseUrl(), mobileBaseUrl())
			href.startsWith("https://") -> href
			else -> mobileBaseUrl() + href
		}.let { if (it.endsWith("/")) it else "$it/" }

		val resp = webClient.httpGet(
			mobileHref,
			getRequestHeaders().newBuilder()
				.set("User-Agent", UserAgents.CHROME_MOBILE)
				.build()
		)
		if (!resp.isSuccessful) return manga
		val doc = resp.parseHtml()

		val title = doc.selectFirst("#comicName, .BarTit")?.text()?.trim()
			?.ifEmpty { doc.selectFirst("title")?.text()?.substringBefore("-")?.trim() }
			?.ifEmpty { manga.title }
			?: manga.title

		val coverEl = doc.selectFirst("#Cover mip-img, #Cover img, mip-img, .pic mip-img, .pic img")
		val cover = coverEl?.attr("src")
			?.ifEmpty { coverEl.attr("data-src") }
			?.ifEmpty { coverEl.attr("data-original") }
			?.let { if (it.startsWith("http")) it else mobileBaseUrl() + it }
			?: manga.coverUrl

		val author = doc.select("#Cover .txtItme a, .txtItme a").firstOrNull()?.text()?.trim()
		val statusTag = doc.select("p.txtItme a").firstOrNull { it.text().contains("完结") || it.text().contains("连载") }?.text()?.trim()
		val categoryTags = doc.select("p.txtItme a[href*=/list/] , .comic__tags a")
			.mapNotNull { a ->
				val t = a.text().trim()
				if (t.isNotEmpty()) MangaTag(t, t.lowercase(Locale.ROOT), source) else null
			}
			.toSet()

		val desc = doc.select("mip-showmore#showmore-des, #showmore-des").text().trim()

		val chapterLinks = doc.select("ul[id^=chapter-list] li a, .comic-chapters li a, .chapter-content a")
		val chapters = chapterLinks.reversed().mapIndexedNotNull { index, a ->
			val chHref = a.attr("href")
			val name = a.text().trim().ifEmpty { a.selectFirst("span")?.text()?.trim().orEmpty() }
			if (chHref.isEmpty()) null else MangaChapter(
				id = generateUid("$chHref-${manga.id}"),
				url = chHref,
				title = name.ifEmpty { "Ch ${index + 1}" },
				number = (index + 1).toFloat(),
				volume = 0,
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}

		val tags = buildSet {
			if (!statusTag.isNullOrEmpty()) add(MangaTag(statusTag, statusTag, source))
			addAll(categoryTags)
		}

		return manga.copy(
			title = title,
			coverUrl = cover,
			description = desc.ifEmpty { manga.description },
			tags = if (tags.isNotEmpty()) tags else manga.tags,
			authors = if (!author.isNullOrEmpty()) setOf(author) else manga.authors,
			chapters = chapters,
			contentRating = manga.contentRating ?: ContentRating.SAFE,
		)
	}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = if (chapter.url.startsWith("http")) {
			chapter.url.replace(baseUrl(), mobileBaseUrl())
		} else {
			mobileBaseUrl() + chapter.url
		}
        val resp = webClient.httpGet(chapterUrl, getRequestHeaders().newBuilder().set("User-Agent", UserAgents.CHROME_MOBILE).build())
        if (!resp.isSuccessful) return emptyList()
		val doc = resp.parseHtml()
		val images = mutableListOf<String>()

		val scriptMatch = Regex("var\\s+chapterImages\\s*=\\s*(\\[.*?])").find(doc.html())
		if (scriptMatch != null) {
			val arrayText = scriptMatch.groupValues[1]
			Regex("\"(.*?)\"").findAll(arrayText).forEach { match ->
				val raw = match.groupValues[1]
				if (raw.isNotEmpty()) {
					images.add(if (raw.startsWith("http")) raw else mobileBaseUrl() + raw)
				}
			}
		}

		if (images.isEmpty()) {
			doc.select("div#images img, .chapter-content img, img").forEach { img ->
				val src = img.attr("data-src")
					.ifEmpty { img.attr("data-original") }
					.ifEmpty { img.attr("data-echo") }
					.ifEmpty { img.attr("src") }
				if (src.isNotEmpty()) {
					images.add(if (src.startsWith("http")) src else mobileBaseUrl() + src)
				}
			}
		}

		return images.mapIndexed { index, url ->
			MangaPage(
				id = generateUid("$url-$index"),
				url = url,
				preview = url,
				source = source,
			)
		}
	}

    override suspend fun getPageUrl(page: MangaPage): String = page.url
    
    /**
     * 规范化漫画 URL：移除章节后缀，只保留漫画路径
     * 例如：/manhua/wotaitaishinvzigaozhongsheng/299812.html -> /manhua/wotaitaishinvzigaozhongsheng/
     */
    private fun normalizeMangaUrl(url: String): String {
        // 匹配 /manhua/名称/ 或 /book/名称/ 模式，移除后面的章节部分
        val mangaPathRegex = Regex("^(/(?:manhua|book)/[^/]+)(?:/.*)?$")
        val match = mangaPathRegex.find(url)
        return if (match != null) {
            "${match.groupValues[1]}/"  // 确保以 / 结尾
        } else {
            // 如果不匹配，返回原始 URL（去除末尾 .html 后缀）
            url.replace(Regex("\\.html$"), "").let { 
                if (!it.endsWith("/")) "$it/" else it 
            }
        }
    }
}
