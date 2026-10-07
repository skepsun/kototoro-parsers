package org.skepsun.kototoro.parsers.site.zh

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JmComicResponseTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `parse encrypted API responses with and without a UTF-8 BOM`(withBom: Boolean) = runBlocking {
        val ctx = OfflineContentLoaderContext(mapOf(
            "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt" to "jmcomic/domains.json",
            "https://www.cdnhjk.net/setting?app_img_shunt=1?express=" to "jmcomic/setting.json",
            "https://www.cdnhjk.net/promote?page=0&o=mr" to "jmcomic/list.json",
            "https://www.cdnhjk.net/album?id=100" to "jmcomic/detail.json",
            "https://www.cdnhjk.net/chapter?id=101" to "jmcomic/chapter.json",
        )) { request, body ->
            if (request.url.host.endsWith("bytepluses.com")) {
                encrypt(body, "diosfjckwpqpdfjkvnqQjsik")
            } else {
                val time = requireNotNull(request.header("tokenparam")).substringBefore(',')
                val json = JSONObject().put("code", 200).put("data", encrypt(body, "${time}185Hcomic3PAPP7R"))
                (if (withBom) "\uFEFF" else "") + json.toString()
            }
        }
        val parser = JmParser(ctx)
        val content = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter.EMPTY).single()
        assertEquals("测试漫画", content.title)
        assertEquals("https://images.example/media/albums/100_3x4.jpg", content.coverUrl)
        val details = parser.getDetails(content)
        assertEquals(setOf("测试作者"), details.authors)
        val chapter = requireNotNull(details.chapters).single()
        assertEquals("第1话", chapter.title)
        val pages = parser.getPages(chapter)
        assertEquals(listOf(
            "https://images.example/media/photos/101/00001.webp",
            "https://images.example/media/photos/101/00002.webp",
        ), pages.map { parser.getPageUrl(it) })
        assertEquals(5, ctx.requests.size)
    }

    private fun encrypt(body: String, secret: String): String {
        val key = MessageDigest.getInstance("MD5").digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(body.toByteArray(Charsets.UTF_8)))
    }
}
