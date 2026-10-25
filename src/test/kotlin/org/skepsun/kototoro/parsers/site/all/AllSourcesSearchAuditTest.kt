package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.newParser
import okhttp3.HttpUrl
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.LinkResolver
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全源搜索能力审计（联网，默认关闭）。
 *
 * 运行方式：
 * ```
 * SEARCH_AUDIT=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.all.AllSourcesSearchAuditTest"
 * ```
 * 可选环境变量：
 * - `SEARCH_AUDIT_ONLY=SHENCOU,WENKU8` 只跑指定源
 * - `SEARCH_AUDIT_CONCURRENCY=8` 并发源数量（默认 8）
 * - `SEARCH_AUDIT_TIMEOUT_SEC=150` 单个源超时秒数（默认 150）
 * - `SEARCH_AUDIT_DELAY_MS=150` 每步之间的间隔（默认 150）
 *
 * 判定方法：
 * 1. 拉取 browse 列表（空查询）作为基线；
 * 2. 用两个互不相同的垃圾查询发起搜索，真实搜索引擎应返回 0 条结果；
 * 3. 从浏览列表中取标题做真实查询，检查原条目能否被搜回（召回）；
 * 4. 在 OkHttp 应用拦截器中记录请求 URL/表单，判断查询字符串是否真的被发送；
 * 5. 组合以上信号给出结论：OK / FAKE_BROWSE / FAKE_FIXED / SUSPICIOUS 等。
 *
 * 报告输出到 `build/reports/search-audit/`（tsv + md）。
 */
@EnabledIfEnvironmentVariable(named = "SEARCH_AUDIT", matches = "1")
internal class AllSourcesSearchAuditTest {

	@Test
	fun auditAllSourcesSearch() = runBlocking {
		val only = System.getenv("SEARCH_AUDIT_ONLY")
			?.split(',')
			?.map { it.trim().uppercase() }
			?.filter { it.isNotEmpty() }
			?.toSet()
			.orEmpty()
		val concurrency = System.getenv("SEARCH_AUDIT_CONCURRENCY")?.toIntOrNull()?.coerceIn(1, 16) ?: 8
		val timeoutSec = System.getenv("SEARCH_AUDIT_TIMEOUT_SEC")?.toLongOrNull()?.coerceIn(30, 900) ?: 150L
		val requestDelayMs = System.getenv("SEARCH_AUDIT_DELAY_MS")?.toLongOrNull()?.coerceIn(0, 5_000) ?: 150L

		val sources = ContentParserSource.entries.filter { only.isEmpty() || it.name in only }
		val recorder = RequestRecorder()
		val context = SearchAuditContext(recorder)
		val semaphore = Semaphore(concurrency)
		val startedAt = System.currentTimeMillis()

		println("Search audit: ${sources.size} sources, concurrency=$concurrency, per-source timeout=${timeoutSec}s")

		val progressDir = File("build/reports/search-audit").apply { mkdirs() }
		val progressFile = File(progressDir, "progress.tsv").apply { writeText("") }
		val progressLock = Any()

		val results = coroutineScope {
			sources.map { source ->
				async(Dispatchers.IO) {
					semaphore.withPermit {
						val result = withTimeoutOrNull(timeoutSec * 1000) {
							auditSource(source, context, recorder, requestDelayMs.milliseconds)
						} ?: SearchAuditResult(
							source = source.name,
							title = source.title,
							type = source.contentType.name,
							verdict = Verdict.TIMEOUT,
							error = "audit timed out after ${timeoutSec}s",
						)
						synchronized(progressLock) {
							progressFile.appendText(
								"${result.source}\t${result.domain}\t${result.verdict.name}\t" +
									"${result.garbageACount}\t${result.garbageBCount}\t${result.realHit}\t" +
									"${(result.error ?: "").take(160)}\n",
							)
						}
						println("[audit] ${result.source} -> ${result.verdict.name}")
						result
					}
				}
			}.awaitAll()
		}.sortedBy { it.source }

		val reportDir = File("build/reports/search-audit")
		reportDir.mkdirs()
		writeTsv(File(reportDir, "search-audit.tsv"), results)
		writeMarkdown(File(reportDir, "search-audit.md"), results, sources.size, System.currentTimeMillis() - startedAt)
		printSummary(results, reportDir)
	}

	private suspend fun auditSource(
		source: ContentParserSource,
		context: ContentLoaderContext,
		recorder: RequestRecorder,
		stepDelay: Duration,
	): SearchAuditResult {
		val parser = runCatching { context.newParserInstance(source) }.getOrElse { error ->
			return SearchAuditResult(
				source = source.name,
				title = source.title,
				type = source.contentType.name,
				verdict = Verdict.ERROR,
				error = "instantiation failed: ${error.describe()}",
			)
		}
		val order = preferredOrder(parser)
		val declaredSearch = parser.filterCapabilities.isSearchSupported
		val domain = runCatching { parser.domain }.getOrDefault("")

		suspend fun step(query: String?): Step {
			val from = recorder.size(source.name)
			val list = try {
				parser.getList(0, order, ContentListFilter(query = query))
			} catch (error: Throwable) {
				return Step(error = error.describe(), requests = recorder.since(source.name, from))
			}
			if (stepDelay > Duration.ZERO) {
				delay(stepDelay.inWholeMilliseconds)
			}
			return Step(
				items = list,
				sent = query?.let { q -> recorder.since(source.name, from).any { it.carries(q) } } ?: false,
				requests = recorder.since(source.name, from),
			)
		}

		val browse = step(null)
		if (browse.error != null) {
			return SearchAuditResult(
				source = source.name,
				title = source.title,
				type = source.contentType.name,
				domain = domain,
				declaredSearch = declaredSearch,
				verdict = Verdict.ERROR,
				error = "browse failed: ${browse.error}",
			)
		}

		val garbageA = step(queryA)
		val garbageB = step(queryB)

		val realQueries = buildRealQueries(browse.items)
		var realQuery: String? = null
		var realStep: Step? = null
		var realHit = false
		for (candidate in realQueries) {
			val step = step(candidate.query)
			realStep = step
			realQuery = candidate.query
			if (step.error == null && step.items.any { it.matches(candidate.item) }) {
				realHit = true
				break
			}
		}

		val browseRequestKeys = browse.requestKeys()
		val garbageARequestDiffers = garbageA.requestKeys().let { keys ->
			keys.isNotEmpty() && keys != browseRequestKeys
		}
		val realRequestDiffers = realStep?.requestKeys()?.let { keys ->
			keys.isNotEmpty() && keys != browseRequestKeys
		} ?: false

		val browseUrls = browse.items.mapTo(LinkedHashSet()) { it.url }
		val aUrls = garbageA.items.mapTo(LinkedHashSet()) { it.url }
		val bUrls = garbageB.items.mapTo(LinkedHashSet()) { it.url }
		val verdict = classify(
			declaredSearch = declaredSearch,
			realHit = realHit,
			a = garbageA,
			b = garbageB,
			browseUrls = browseUrls,
			aUrls = aUrls,
			bUrls = bUrls,
		)

		return SearchAuditResult(
			source = source.name,
			title = source.title,
			type = source.contentType.name,
			domain = domain,
			declaredSearch = declaredSearch,
			verdict = verdict,
			browseCount = browse.items.size,
			garbageACount = garbageA.items.size,
			garbageBCount = garbageB.items.size,
			garbageASent = garbageA.sent,
			garbageBSent = garbageB.sent,
			garbageAEqualsBrowse = aUrls.isNotEmpty() && aUrls == browseUrls,
			garbageABEqual = aUrls.isNotEmpty() && aUrls == bUrls,
			garbageRequestDiffers = garbageARequestDiffers,
			realRequestDiffers = realRequestDiffers,
			garbageATitles = garbageA.items.take(3).map { it.title },
			realQuery = realQuery,
			realCount = realStep?.items?.size ?: 0,
			realSent = realStep?.sent ?: false,
			realHit = realHit,
			error = garbageA.error ?: garbageB.error ?: realStep?.error,
			requestSamples = (garbageA.requests + garbageB.requests)
				.take(4)
				.map { it.describe() },
		)
	}

	private fun classify(
		declaredSearch: Boolean,
		realHit: Boolean,
		a: Step,
		b: Step,
		browseUrls: Set<String>,
		aUrls: Set<String>,
		bUrls: Set<String>,
	): Verdict {
		if (a.error != null && b.error != null) {
			return if (a.error.contains("Auth", ignoreCase = true) || b.error.contains("Auth", ignoreCase = true)) {
				Verdict.ERROR
			} else {
				Verdict.ERROR
			}
		}
		if (a.error != null || b.error != null) {
			return Verdict.PARTIAL_ERROR
		}
		val queryNeverSent = !a.sent && !b.sent
		if (aUrls.isEmpty() && bUrls.isEmpty()) {
			// 垃圾查询均为空：若真实标题能命中，说明检索链路（含客户端过滤）有效，
			// 即使请求里检测不到关键字（本地过滤、POST body 等）也判定为正常。
			return when {
				realHit -> Verdict.OK
				queryNeverSent -> Verdict.EMPTY_WITHOUT_REQUEST
				!declaredSearch -> Verdict.OK_UNDECLARED
				else -> Verdict.OK
			}
		}
		if (aUrls == bUrls && aUrls.isNotEmpty()) {
			return when {
				// 未声明支持搜索的源本来就不会附带关键字，结果与查询无关属于预期，不算假搜索
				!declaredSearch && queryNeverSent -> Verdict.OK_UNDECLARED
				queryNeverSent -> Verdict.FAKE_NOT_SENT
				aUrls == browseUrls -> Verdict.FAKE_BROWSE
				else -> Verdict.FAKE_FIXED
			}
		}
		if (aUrls.isEmpty() || bUrls.isEmpty()) {
			return Verdict.SUSPICIOUS_PARTIAL
		}
		val overlap = aUrls.intersect(bUrls).size.toDouble() / minOf(aUrls.size, bUrls.size)
		val browseOverlap = aUrls.intersect(browseUrls).size.toDouble() / aUrls.size
		return when {
			queryNeverSent -> Verdict.FAKE_NOT_SENT
			browseOverlap >= 0.9 -> Verdict.FAKE_BROWSE
			overlap >= 0.8 -> Verdict.SUSPICIOUS_VARYING
			else -> Verdict.SUSPICIOUS_VARYING
		}
	}

	private fun buildRealQueries(items: List<Content>): List<RealQuery> {
		val candidates = items
			.distinctBy { it.url }
			.sortedBy { it.title.length }
			.take(2)
			.flatMap { item ->
				val full = item.title.trim()
					.substringBefore('(')
					.substringBefore('（')
					.trim()
				listOfNotNull(
					full.takeIf { it.length >= 2 }?.let { RealQuery(it, item) },
					full.take(8).trim().takeIf { it.length >= 2 && it != full }?.let { RealQuery(it, item) },
					full.split(' ', '-', '_', ':')
						.maxByOrNull { it.length }
						?.takeIf { it.length >= 3 && it != full }
						?.let { RealQuery(it, item) },
				)
			}
		return candidates.distinctBy { it.query }.take(4)
	}

	private fun writeTsv(file: File, results: List<SearchAuditResult>) {
		file.bufferedWriter().use { writer ->
			writer.appendLine(
				listOf(
					"source", "title", "type", "domain", "declared_search", "verdict",
					"browse", "garbage_a", "garbage_b", "a_sent", "b_sent",
					"a_equals_browse", "a_equals_b", "req_differs", "real_req_differs", "real_query", "real_count", "real_sent", "real_hit",
					"error", "requests",
				).joinToString("\t"),
			)
			for (r in results) {
				writer.appendLine(
					listOf(
						r.source, r.title, r.type, r.domain, r.declaredSearch.toString(), r.verdict.name,
						r.browseCount.toString(), r.garbageACount.toString(), r.garbageBCount.toString(),
						r.garbageASent.toString(), r.garbageBSent.toString(),
						r.garbageAEqualsBrowse.toString(), r.garbageABEqual.toString(),
						r.garbageRequestDiffers.toString(), r.realRequestDiffers.toString(),
						r.realQuery.orEmpty(), r.realCount.toString(), r.realSent.toString(), r.realHit.toString(),
						(r.error ?: "").replace('\t', ' ').take(200),
						r.requestSamples.joinToString(" | ").replace('\t', ' ').take(400),
					).joinToString("\t"),
				)
			}
		}
	}

	private fun writeMarkdown(file: File, results: List<SearchAuditResult>, total: Int, elapsedMs: Long) {
		file.bufferedWriter().use { writer ->
			writer.appendLine("# 全源搜索审计报告")
			writer.appendLine()
			writer.appendLine("- 生成时间：${java.time.LocalDateTime.now()}")
			writer.appendLine("- 源数量：$total")
			writer.appendLine("- 耗时：${elapsedMs / 1000}s")
			writer.appendLine()
			writer.appendLine("## 结论统计")
			writer.appendLine()
			writer.appendLine("| 结论 | 数量 | 说明 |")
			writer.appendLine("| --- | --- | --- |")
			for (verdict in Verdict.entries) {
				val count = results.count { it.verdict == verdict }
				if (count == 0) continue
				writer.appendLine("| ${verdict.name} | $count | ${verdict.description} |")
			}
			writer.appendLine()

			writer.appendLine("## 明细")
			writer.appendLine()
			writer.appendLine("| source | title | type | 域名 | 声明搜索 | 结论 | browse | 垃圾A | 垃圾B | A=浏览 | A=B | 请求不同 | 真实查询 | 命中 | 错误 |")
			writer.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
			for (r in results.sortedWith(compareBy({ it.verdict.ordinal }, { it.source }))) {
				writer.appendLine(
					"| ${r.source} | ${r.title.escapeMd()} | ${r.type} | ${r.domain} | ${r.declaredSearch.yesNo()} | " +
						"**${r.verdict.name}** | ${r.browseCount} | ${r.garbageACount} | ${r.garbageBCount} | " +
						"${r.garbageAEqualsBrowse.yesNo()} | ${r.garbageABEqual.yesNo()} | ${r.garbageRequestDiffers.yesNo()} | " +
						"${r.realQuery.orEmpty().escapeMd()} | ${r.realHit.yesNo()} | ${(r.error ?: "").escapeMd().take(120)} |",
				)
			}
			writer.appendLine()

			val fake = results.filter { it.verdict.isFake }
			if (fake.isNotEmpty()) {
				writer.appendLine("## 返回固定无关内容的源（疑似假搜索）")
				writer.appendLine()
				for (r in fake) {
					writer.appendLine("- **${r.source}**（${r.title}）：${r.verdict.name}，垃圾查询返回 ${r.garbageACount}/${r.garbageBCount} 条，" +
						"示例：${r.garbageATitles.joinToString(" / ").escapeMd()}")
				}
			}
		}
	}

	private fun printSummary(results: List<SearchAuditResult>, reportDir: File) {
		println()
		println("=== 搜索审计汇总（${results.size} 个源） ===")
		for (verdict in Verdict.entries) {
			val group = results.filter { it.verdict == verdict }
			if (group.isEmpty()) continue
			println("${verdict.name} (${group.size}): ${group.joinToString(", ") { it.source }}")
		}
		println()
		println("报告：${reportDir.absolutePath}")
	}

	private fun preferredOrder(parser: ContentParser): SortOrder {
		return parser.availableSortOrders.firstOrNull { it == SortOrder.RELEVANCE }
			?: parser.availableSortOrders.firstOrNull { it == SortOrder.UPDATED }
			?: parser.availableSortOrders.first()
	}

	private fun Throwable.describe(): String = "${javaClass.simpleName}: ${message.orEmpty()}".take(300)

	private fun Boolean.yesNo(): String = if (this) "是" else ""

	private fun String.escapeMd(): String = replace("|", "\\|").replace("\n", " ")

	internal class Step(
		val items: List<Content> = emptyList(),
		val sent: Boolean = false,
		val requests: List<RecordedRequest> = emptyList(),
		val error: String? = null,
	) {
		fun requestKeys(): Set<String> = requests.mapTo(LinkedHashSet()) { "${it.method} ${it.url}" }
	}

	internal data class RealQuery(val query: String, val item: Content)

	internal data class SearchAuditResult(
		val source: String,
		val title: String,
		val type: String,
		val domain: String = "",
		val declaredSearch: Boolean = false,
		val verdict: Verdict,
		val browseCount: Int = 0,
		val garbageACount: Int = 0,
		val garbageBCount: Int = 0,
		val garbageASent: Boolean = false,
		val garbageBSent: Boolean = false,
		val garbageAEqualsBrowse: Boolean = false,
		val garbageABEqual: Boolean = false,
		val garbageRequestDiffers: Boolean = false,
		val realRequestDiffers: Boolean = false,
		val garbageATitles: List<String> = emptyList(),
		val realQuery: String? = null,
		val realCount: Int = 0,
		val realSent: Boolean = false,
		val realHit: Boolean = false,
		val error: String? = null,
		val requestSamples: List<String> = emptyList(),
	)

	internal enum class Verdict(val description: String, val isFake: Boolean) {
		OK("垃圾查询返回空结果，搜索真实生效", false),
		OK_UNDECLARED("未声明搜索，但查询仍被正确处理", false),
		EMPTY_WITHOUT_REQUEST("查询未发出且结果为空（需人工确认）", false),
		FAKE_BROWSE("忽略查询，返回与浏览列表完全相同的内容", true),
		FAKE_FIXED("忽略查询，返回固定的无关内容", true),
		FAKE_NOT_SENT("查询字符串根本没被发出，结果与查询无关", true),
		SUSPICIOUS_VARYING("结果随查询变化，但垃圾查询也有结果（疑似站点兜底/模糊匹配）", false),
		SUSPICIOUS_PARTIAL("两次垃圾查询结果不一致（一次空、一次非空）", false),
		PARTIAL_ERROR("部分搜索请求异常", false),
		ERROR("浏览/搜索请求失败（网络、鉴权或站点结构变化）", false),
		TIMEOUT("审计超时", false),
	}

	private fun Content.matches(other: Content): Boolean = id == other.id || url == other.url

	private val queryA = "qzxvbnmkjhgfdsprt"
	private val queryB = "wplkdhtyqvmzbxnrg"

	internal class RecordedRequest(
		val method: String,
		val url: String,
		val body: String?,
	) {
		fun carries(query: String): Boolean {
			val haystack = buildString {
				append(url.urlDecoded())
				append(' ')
				append(body.orEmpty().urlDecoded())
			}
			if (haystack.contains(query, ignoreCase = true)) {
				return true
			}
			for (charset in listOf("UTF-8", "GBK")) {
				val encoded = runCatching { URLEncoder.encode(query, charset) }.getOrNull() ?: continue
				if (haystack.contains(encoded, ignoreCase = true)) {
					return true
				}
			}
			return false
		}

		fun describe(): String {
			val shortBody = body?.takeIf { it.isNotBlank() }?.let { " body=${it.take(120).replace('\n', ' ')}" }.orEmpty()
			return "$method ${url.take(220)}$shortBody"
		}

		private fun String.urlDecoded(): String {
			return runCatching { URLDecoder.decode(replace("+", "%2B"), "UTF-8") }.getOrDefault(this)
		}
	}

	internal class RequestRecorder : Interceptor {

		private val records = ConcurrentHashMap<String, MutableList<RecordedRequest>>()

		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val source = request.tag(ContentSource::class.java)?.name
			if (source != null) {
				val body = runCatching {
					request.body?.let { requestBody ->
						Buffer().also { buffer -> requestBody.writeTo(buffer) }.readUtf8()
					}
				}.getOrNull()
				records.computeIfAbsent(source) { Collections.synchronizedList(mutableListOf()) }
					.add(RecordedRequest(request.method, request.url.toString(), body))
			}
			return chain.proceed(request)
		}

		fun size(source: String): Int {
			val list = records[source] ?: return 0
			synchronized(list) { return list.size }
		}

		fun since(source: String, fromIndex: Int): List<RecordedRequest> {
			val list = records[source] ?: return emptyList()
			synchronized(list) { return list.drop(fromIndex).toList() }
		}
	}

	internal class SearchAuditContext(
		private val recorder: RequestRecorder,
	) : ContentLoaderContext() {

		override val cookieJar: CookieJar = ContentLoaderContextMock.cookieJar

		override val httpClient: OkHttpClient = ContentLoaderContextMock.httpClient.newBuilder()
			.addInterceptor(recorder)
			.build()

		override fun newParserInstance(source: ContentSource): ContentParser {
			val parserSource = requireNotNull(source as? ContentParserSource) {
				"Not a content parser source: ${source.name}"
			}
			return parserSource.newParser(this)
		}

		override fun newLinkResolver(link: HttpUrl): LinkResolver =
			ContentLoaderContextMock.newLinkResolver(link)

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? =
			ContentLoaderContextMock.evaluateJs(script)

		override suspend fun evaluateJs(baseUrl: String, script: String): String? =
			ContentLoaderContextMock.evaluateJs(baseUrl, script)

		override fun getConfig(source: ContentSource): ContentSourceConfig =
			ContentLoaderContextMock.getConfig(source)

		override fun getDefaultUserAgent(): String = ContentLoaderContextMock.getDefaultUserAgent()

		override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response =
			ContentLoaderContextMock.redrawImageResponse(response, redraw)

		override fun createBitmap(width: Int, height: Int): Bitmap =
			ContentLoaderContextMock.createBitmap(width, height)
	}
}
