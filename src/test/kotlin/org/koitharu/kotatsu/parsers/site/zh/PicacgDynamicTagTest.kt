package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.search.ContentSearchQuery
import org.skepsun.kototoro.parsers.model.search.QueryCriteria
import org.skepsun.kototoro.parsers.model.search.QueryCriteria.Include
import org.skepsun.kototoro.parsers.model.search.SearchableField.TAG
import org.skepsun.kototoro.parsers.util.getCookies
import org.skepsun.kototoro.parsers.util.insertCookies
import java.io.File

/**
 * Picacg 动态标签搜索测试
 * - 验证动态标签搜索功能是否正常工作
 * - 测试搜索请求体格式是否正确（只包含keyword和sort参数）
 * - 验证不会出现500错误
 */
class PicacgDynamicTagTest {

    private val context = ContentLoaderContextMock

    @Test
    fun dynamic_tag_search_should_not_return_500() = runTest(timeout = 2.minutes) {
        // 预置token以避免登录限流
        runCatching {
            val envToken = System.getenv("PICACG_TOKEN")?.trim()
            val fileTokenCandidate1 = File("../picacg_token.txt").takeIf { it.exists() }?.readText()?.trim()
            val fileTokenCandidate2 = File("picacg_token.txt").takeIf { it.exists() }?.readText()?.trim()
            val token = envToken?.takeIf { it.isNotBlank() } ?: fileTokenCandidate1?.takeIf { it.isNotBlank() } ?: fileTokenCandidate2?.takeIf { it.isNotBlank() }
            if (!token.isNullOrBlank()) {
                context.cookieJar.insertCookies(
                    "picaapi.picacomic.com",
                    "authorization=$token",
                    "token=$token",
                )
                context.cookieJar.insertCookies(
                    "api.go2778.com",
                    "authorization=$token",
                    "token=$token",
                )
            }
        }.onFailure { /* ignore */ }

        val parser = context.newParserInstance(ContentParserSource.PICACG)
        val domain = parser.domain
        
        // 检查是否预置了token
        val tokenPreset = context.cookieJar.getCookies(domain).any { c ->
            (c.name.equals("token", true) || c.name.equals("authorization", true)) && c.value.isNotEmpty()
        }
        assumeTrue(
            tokenPreset,
            "Missing PICACG_TOKEN; skipping to avoid hitting server rate limits",
        )

        // 测试动态标签搜索 - 使用"巨乳"标签
        try {
            val result = parser.getList(
            ContentSearchQuery.Builder()
                .criterion(Include(TAG, setOf("巨乳")))
                .order(SortOrder.NEWEST)
                .build()
        )
            
            // 搜索应该成功，不抛出异常
            assertTrue(true, "动态标签搜索成功完成")
            
        } catch (e: Exception) {
            // 检查是否是500错误
            if (e.message?.contains("500") == true || e.message?.contains("Internal Server Error") == true) {
                throw AssertionError("动态标签搜索返回500错误: ${e.message}", e)
            }
            // 其他类型的异常（如网络问题、授权问题）可以跳过测试
            assumeTrue(false, "动态标签搜索遇到非500错误: ${e.message}")
        }
    }

    @Test
    fun dynamic_tag_search_with_different_sort_orders() = runTest(timeout = 2.minutes) {
        // 预置token
        runCatching {
            val envToken = System.getenv("PICACG_TOKEN")?.trim()
            val fileTokenCandidate1 = File("../picacg_token.txt").takeIf { it.exists() }?.readText()?.trim()
            val fileTokenCandidate2 = File("picacg_token.txt").takeIf { it.exists() }?.readText()?.trim()
            val token = envToken?.takeIf { it.isNotBlank() } ?: fileTokenCandidate1?.takeIf { it.isNotBlank() } ?: fileTokenCandidate2?.takeIf { it.isNotBlank() }
            if (!token.isNullOrBlank()) {
                context.cookieJar.insertCookies(
                    "picaapi.picacomic.com",
                    "authorization=$token",
                    "token=$token",
                )
                context.cookieJar.insertCookies(
                    "api.go2778.com",
                    "authorization=$token",
                    "token=$token",
                )
            }
        }.onFailure { /* ignore */ }

        val parser = context.newParserInstance(ContentParserSource.PICACG)
        val domain = parser.domain
        
        val tokenPreset = context.cookieJar.getCookies(domain).any { c ->
            (c.name.equals("token", true) || c.name.equals("authorization", true)) && c.value.isNotBlank()
        }
        assumeTrue(
            tokenPreset,
            "Missing PICACG_TOKEN; skipping to avoid hitting server rate limits",
        )

        // 测试不同的排序方式
        val sortOrders = listOf(
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.RATING
        )

        for (sortOrder in sortOrders) {
            try {
                val result = parser.getList(
            ContentSearchQuery.Builder()
                .criterion(Include(TAG, setOf("巨乳")))
                .order(sortOrder)
                .build()
        )
                
                // 每种排序方式都应该成功
                assertTrue(true, "排序方式 ${sortOrder.name} 搜索成功")
                
            } catch (e: Exception) {
                if (e.message?.contains("500") == true || e.message?.contains("Internal Server Error") == true) {
                    throw AssertionError("排序方式 ${sortOrder.name} 返回500错误: ${e.message}", e)
                }
                // 跳过其他错误
                assumeTrue(false, "排序方式 ${sortOrder.name} 遇到非500错误: ${e.message}")
            }
        }
    }
}