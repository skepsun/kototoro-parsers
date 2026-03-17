package org.skepsun.kototoro.parsers.site.en

import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
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
@ContentSourceParser("HENTAICOSPLAY", "HentaiCosplay", type = ContentType.HENTAI_MANGA)
internal class HentaiCosplay(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.HENTAICOSPLAY, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("hentai-cosplay-xxx.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,      // 最新更新 (/search/)
		SortOrder.POPULARITY,   // 下载排名 (/ranking-download/)
		SortOrder.RATING,       // 收藏排名 (/ranking-bookmark/)
		SortOrder.ALPHABETICAL, // 点赞排名 (/ranking-like/)
		SortOrder.NEWEST,       // 最近更新 (/recently/)
	)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isSearchSupported = false,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions(): ContentListFilterOptions {
		// 常用标签列表
		val tags = setOf(
			// 角色/作品
			ContentTag(title = "2B", key = "2b", source = source),
			ContentTag(title = "Nier Automata", key = "nier-automata", source = source),
			ContentTag(title = "Genshin Impact", key = "genshin-impact", source = source),
			ContentTag(title = "Fate", key = "fate", source = source),
			ContentTag(title = "Fate/Grand Order", key = "fategrand-order", source = source),
			ContentTag(title = "Honkai Star Rail", key = "honkai-star-rail", source = source),
			ContentTag(title = "Vocaloid", key = "vocaloid", source = source),
			ContentTag(title = "Love Live", key = "lovelive", source = source),
			ContentTag(title = "Chainsaw Man", key = "chainsaw-man", source = source),
			ContentTag(title = "Makima", key = "makima", source = source),
			ContentTag(title = "Power", key = "power", source = source),
			ContentTag(title = "Shiranui Mai", key = "mai-shiranui", source = source),
			
			// 服装/风格
			ContentTag(title = "Bunny Girl", key = "bunny-girl", source = source),
			ContentTag(title = "Maid", key = "maid", source = source),
			ContentTag(title = "Nurse", key = "nurse", source = source),
			ContentTag(title = "School Uniform", key = "school-uniform", source = source),
			ContentTag(title = "Swimsuit", key = "swimsuit", source = source),
			ContentTag(title = "Bikini", key = "bikini", source = source),
			ContentTag(title = "Lingerie", key = "lingerie", source = source),
			ContentTag(title = "Latex", key = "latex", source = source),
			ContentTag(title = "Pantyhose", key = "pantyhose", source = source),
			ContentTag(title = "Stockings", key = "stockings", source = source),
			ContentTag(title = "Fishnets", key = "fishnets", source = source),
			ContentTag(title = "Thigh High Boots", key = "thigh-high-boots", source = source),
			
			// 特征
			ContentTag(title = "Big Breasts", key = "big-breasts", source = source),
			ContentTag(title = "Kemonomimi", key = "kemonomimi", source = source),
			ContentTag(title = "Cat Ears", key = "cat-ears", source = source),
			ContentTag(title = "Elf", key = "elf", source = source),
			ContentTag(title = "Glasses", key = "glasses", source = source),
			ContentTag(title = "Makeup", key = "makeup", source = source),
			
			// 类型
			ContentTag(title = "Solo", key = "solo", source = source),
			ContentTag(title = "Females Only", key = "females-only", source = source),
			ContentTag(title = "Nude", key = "nude", source = source),
			ContentTag(title = "Topless", key = "topless", source = source),
			
			// 地区
			ContentTag(title = "Japanese", key = "japanese", source = source),
			ContentTag(title = "Chinese", key = "chinese", source = source),
			ContentTag(title = "Korean", key = "korean", source = source),
			ContentTag(title = "Taiwan", key = "taiwan", source = source),
			ContentTag(title = "Russia", key = "ロシア", source = source),
		)
		
		return ContentListFilterOptions(
			availableTags = tags,
			availableContentTypes = EnumSet.of(ContentType.HENTAI_MANGA),
		)
	}

	override suspend fun getDetails(manga: Content): Content {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// 查找标题
		val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
		
		// 查找标签
		val tags = doc.select("a[href*=/search/tag/]").mapNotNullToSet { a ->
			val tagKey = a.attr("href").substringAfterLast("/search/tag/").substringBefore("/")
			val tagTitle = a.text().trim()
			if (tagKey.isNotEmpty() && tagTitle.isNotEmpty()) {
				ContentTag(
					key = tagKey,
					title = tagTitle.toTitleCase(),
					source = source,
				)
			} else null
		}
		
		// 创建单个章节（图集）
		val chapter = ContentChapter(
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

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		// Use /story/ URL instead of /image/ to get the AMP page with all images
		val storyUrl = chapter.url.replace("/image/", "/story/").toAbsoluteUrl(domain)
		val doc = webClient.httpGet(storyUrl).parseHtml()
		
		// Select amp-img elements with upload path, excluding related thumbnails
		val images = doc.select("amp-img[src*=upload]:not(.related-thumbnail)")
		
		return images.mapIndexedNotNull { index, element ->
			val imageUrl = element.attr("src")
			if (imageUrl.isBlank()) return@mapIndexedNotNull null
			
			ContentPage(
				id = generateUid(imageUrl),
				url = imageUrl.replace("http://", "https://"),
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
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
		val items = ArrayList<Content>(pageSize)
		
		// Try desktop layout first
		val desktopItems = doc.select("div.image-list-item:has(a[href*=/image/])")
		
		if (desktopItems.isNotEmpty()) {
			// Desktop layout
			desktopItems.forEach { div ->
				val a = div.selectFirst("a") ?: return@forEach
				val href = a.attr("href")
				if (href.isBlank() || href == "#") return@forEach
				
				val img = div.selectFirst("img")
				val coverUrl = img?.attr("src")?.replace("http://", "https://") ?: ""
				
				// Get title from dedicated element or fallback to alt/URL
				val title = div.selectFirst(".image-list-item-title")?.text()?.trim()
					?: img?.attr("alt")?.trim()
					?: href.substringAfterLast("/image/")
						.substringBefore("/")
						.replace("-", " ")
						.toTitleCase()
				
				items.add(
					Content(
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
		} else {
			// Mobile layout fallback
			doc.select("#entry_list > li > a[href*=/image/]").forEach { a ->
				val href = a.attr("href")
				if (href.isBlank() || href == "#") return@forEach
				
				val img = a.selectFirst("img")
				val coverUrl = img?.attr("src")?.replace("http://", "https://") ?: ""
				val title = a.selectFirst("span:not(.posted)")?.text()?.trim()
					?: href.substringAfterLast("/image/")
						.substringBefore("/")
						.replace("-", " ")
						.toTitleCase()
				
				items.add(
					Content(
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
		}
		
		return items
	}
}
