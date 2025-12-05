package org.skepsun.kototoro.parsers.site.en

import org.skepsun.kototoro.parsers.MangaLoaderContext
import org.skepsun.kototoro.parsers.MangaSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedMangaParser
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.util.EnumSet

/**
 * HentaiCosplay - 成人Cosplay图片网站
 * 
 * 网站: https://hentai-cosplay-xxx.com/
 * 
 * 功能特点:
 * - 支持多语言（通过子域名切换）
 * - 图片集浏览
 * - 标签分类
 * - 排名系统（下载、收藏、点赞）
 * 
 * 页面结构:
 * - 列表页: div.image-list-item
 * - 详情页: div#main_contents
 * - 图片链接: a[href*='/upload/']
 * 
 * URL 模式:
 * - 最新更新: /search/
 * - 下载排名: /ranking-download/
 * - 详情页: /image/{slug}/
 * - 标签搜索: /search/tag/{tag}/
 * 
 * 实现状态:
 * - ✅ 列表解析
 * - ✅ 详情解析
 * - ✅ 图片提取
 * - ✅ 标签支持
 * - ✅ 排序支持
 */
@MangaSourceParser("HENTAICOSPLAY", "HentaiCosplay", type = ContentType.HENTAI)
internal class HentaiCosplay(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HENTAICOSPLAY, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("hentai-cosplay-xxx.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,      // 最新更新 (/search/)
		SortOrder.POPULARITY,   // 下载排名 (/ranking-download/)
		SortOrder.RATING,       // 收藏排名 (/ranking-bookmark/)
		SortOrder.ALPHABETICAL, // 点赞排名 (/ranking-like/)
		SortOrder.NEWEST,       // 最近更新 (/recently/)
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = false,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		// 常用标签列表
		val tags = setOf(
			// 角色/作品
			MangaTag(title = "2B", key = "2b", source = source),
			MangaTag(title = "Nier Automata", key = "nier-automata", source = source),
			MangaTag(title = "Genshin Impact", key = "genshin-impact", source = source),
			MangaTag(title = "Fate", key = "fate", source = source),
			MangaTag(title = "Fate/Grand Order", key = "fategrand-order", source = source),
			MangaTag(title = "Honkai Star Rail", key = "honkai-star-rail", source = source),
			MangaTag(title = "Vocaloid", key = "vocaloid", source = source),
			MangaTag(title = "Love Live", key = "lovelive", source = source),
			MangaTag(title = "Chainsaw Man", key = "chainsaw-man", source = source),
			MangaTag(title = "Makima", key = "makima", source = source),
			MangaTag(title = "Power", key = "power", source = source),
			MangaTag(title = "Shiranui Mai", key = "mai-shiranui", source = source),
			
			// 服装/风格
			MangaTag(title = "Bunny Girl", key = "bunny-girl", source = source),
			MangaTag(title = "Maid", key = "maid", source = source),
			MangaTag(title = "Nurse", key = "nurse", source = source),
			MangaTag(title = "School Uniform", key = "school-uniform", source = source),
			MangaTag(title = "Swimsuit", key = "swimsuit", source = source),
			MangaTag(title = "Bikini", key = "bikini", source = source),
			MangaTag(title = "Lingerie", key = "lingerie", source = source),
			MangaTag(title = "Latex", key = "latex", source = source),
			MangaTag(title = "Pantyhose", key = "pantyhose", source = source),
			MangaTag(title = "Stockings", key = "stockings", source = source),
			MangaTag(title = "Fishnets", key = "fishnets", source = source),
			MangaTag(title = "Thigh High Boots", key = "thigh-high-boots", source = source),
			
			// 特征
			MangaTag(title = "Big Breasts", key = "big-breasts", source = source),
			MangaTag(title = "Kemonomimi", key = "kemonomimi", source = source),
			MangaTag(title = "Cat Ears", key = "cat-ears", source = source),
			MangaTag(title = "Elf", key = "elf", source = source),
			MangaTag(title = "Glasses", key = "glasses", source = source),
			MangaTag(title = "Makeup", key = "makeup", source = source),
			
			// 类型
			MangaTag(title = "Solo", key = "solo", source = source),
			MangaTag(title = "Females Only", key = "females-only", source = source),
			MangaTag(title = "Nude", key = "nude", source = source),
			MangaTag(title = "Topless", key = "topless", source = source),
			
			// 地区
			MangaTag(title = "Japanese", key = "japanese", source = source),
			MangaTag(title = "Chinese", key = "chinese", source = source),
			MangaTag(title = "Korean", key = "korean", source = source),
			MangaTag(title = "Taiwan", key = "taiwan", source = source),
			MangaTag(title = "Russia", key = "ロシア", source = source),
		)
		
		return MangaListFilterOptions(
			availableTags = tags,
			availableContentTypes = EnumSet.of(ContentType.HENTAI),
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// 查找标题
		val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
		
		// 查找标签
		val tags = doc.select("a[href*=/search/tag/]").mapNotNullToSet { a ->
			val tagKey = a.attr("href").substringAfterLast("/search/tag/").substringBefore("/")
			val tagTitle = a.text().trim()
			if (tagKey.isNotEmpty() && tagTitle.isNotEmpty()) {
				MangaTag(
					key = tagKey,
					title = tagTitle.toTitleCase(),
					source = source,
				)
			} else null
		}
		
		// 创建单个章节（图集）
		val chapter = MangaChapter(
			id = generateUid("${manga.url}|gallery"),
			url = manga.url,
			title = title,
			number = 1f,
			uploadDate = 0L,
			volume = 0,
			branch = null,
			scanlator = null,
			source = source,
		)
		
		return manga.copy(
			title = title,
			altTitles = emptySet(),
			description = null,
			authors = emptySet(),
			contentRating = ContentRating.ADULT,
			tags = tags,
			state = null,
			chapters = listOf(chapter),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val mainContent = doc.selectFirstOrThrow("div#main_contents")
		
		// 查找所有图片链接
		val imageLinks = mainContent.select("a[href*=/upload/]")
		
		return imageLinks.mapIndexedNotNull { index, a ->
			val imageUrl = a.attr("href")
			if (imageUrl.isBlank()) return@mapIndexedNotNull null
			
			// 提取预览图
			val img = a.selectFirst("img")
			val preview = img?.attr("src")
			
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = preview,
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// 构建URL
		val url = buildString {
			append("https://")
			append(domain)
			
			// 检查是否有标签过滤
			val tag = filter.tags.firstOrNull()
			if (tag != null) {
				// 标签搜索
				append("/search/tag/")
				append(tag.key)
				append("/")
				if (page > 1) append("page/$page/")
			} else {
				// 根据排序方式选择路径
				when (order) {
					SortOrder.POPULARITY -> {
						// 下载排名
						append("/ranking-download/")
						if (page > 1) append("page/$page/")
					}
					SortOrder.RATING -> {
						// 收藏排名
						append("/ranking-bookmark/")
						if (page > 1) append("page/$page/")
					}
					SortOrder.ALPHABETICAL -> {
						// 点赞排名
						append("/ranking-like/")
						if (page > 1) append("page/$page/")
					}
					SortOrder.NEWEST -> {
						// 最近更新
						append("/recently/")
						if (page > 1) append("page/$page/")
					}
					else -> {
						// 默认：最新更新
						append("/search/")
						if (page > 1) append("page/$page/")
					}
				}
			}
		}
		
		val doc = webClient.httpGet(url).parseHtml()
		val items = ArrayList<Manga>(pageSize)
		
		doc.select("div.image-list-item").forEach { div ->
			val a = div.selectFirst("a") ?: return@forEach
			val href = a.attr("href")
			if (href.isBlank() || href == "#") return@forEach
			
			val img = a.selectFirst("img")
			val coverUrl = img?.attr("src") ?: ""
			val alt = img?.attr("alt") ?: ""
			
			// 提取标题
			val title = if (alt.isNotEmpty()) {
				alt
			} else {
				// 从URL提取并格式化
				href.substringAfterLast("/image/")
					.substringBefore("/")
					.replace("-", " ")
					.toTitleCase()
			}
			
			items.add(
				Manga(
					id = generateUid(href),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					coverUrl = coverUrl.toAbsoluteUrl(domain),
					title = title,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.ADULT,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					largeCoverUrl = null,
					description = null,
					chapters = null,
					source = source,
				),
			)
		}
		
		return items
	}
}
