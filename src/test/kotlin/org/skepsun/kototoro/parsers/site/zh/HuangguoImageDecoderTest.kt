package org.skepsun.kototoro.parsers.site.zh

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class HuangguoImageDecoderTest {

    /** 站点的固定常量；这里独立写一份，常量漂移时测试会失败提醒同步。 */
    private val key = "f5d965df75336270".toByteArray(Charsets.UTF_8)
    private val iv = "97b60394abc2fbe1".toByteArray(Charsets.UTF_8)

    @Test
    fun `decrypts real cover ciphertext from the site`() {
        // 夹具是线上真实封面密文的前 256 字节（CBC 前缀可正确解密），
        // 用来证明 key/iv 与站点一致，而不是自造数据自我验证。
        val cipherText = requireNotNull(javaClass.getResourceAsStream("/fixtures/huangguo/cover.bin"))
            .use { it.readBytes() }

        val plain = requireNotNull(HuangguoImageDecoder.decrypt(cipherText))

        assertEquals(0xff.toByte(), plain[0])
        assertEquals(0xd8.toByte(), plain[1])
        assertEquals("image/jpeg", HuangguoImageDecoder.detectMediaType(plain))
    }

    @Test
    fun `decodes encrypted response into an image body`() {
        val plain = Base64.getDecoder().decode(ONE_PIXEL_PNG)
        val response = imageResponse(encrypt(plain))

        val decoded = HuangguoImageDecoder.decodeResponse(response)

        assertEquals("image/png", decoded.body.contentType().toString())
        assertArrayEquals(plain, decoded.body.bytes())
    }

    @Test
    fun `passes through already decrypted images`() {
        val plain = Base64.getDecoder().decode(ONE_PIXEL_PNG)
        val response = imageResponse(plain)

        val decoded = HuangguoImageDecoder.decodeResponse(response)

        assertArrayEquals(plain, decoded.body.bytes())
        assertEquals("image/png", decoded.body.contentType().toString())
    }

    @Test
    fun `keeps original bytes when decryption does not yield an image`() {
        val garbage = ByteArray(64) { (it * 7 + 3).toByte() }
        val response = imageResponse(garbage)

        val decoded = HuangguoImageDecoder.decodeResponse(response)

        assertArrayEquals(garbage, decoded.body.bytes())
    }

    @Test
    fun `treats image paths as encrypted regardless of cdn host`() {
        // 站点换过 CDN 域名（drleao.cn → qnlycc.cn），判定必须与域名无关，否则封面会退化成密文
        assertTrue(HuangguoImageDecoder.isImageRequest("https://pic.qnlycc.cn/a/b.jpg".toHttpUrl()))
        assertTrue(HuangguoImageDecoder.isImageRequest("https://pic.drleao.cn/a/b.jpg".toHttpUrl()))
        assertTrue(HuangguoImageDecoder.isImageRequest("https://new2.drleao.cn/a/b.PNG".toHttpUrl()))
        assertTrue(HuangguoImageDecoder.isImageRequest("https://future-cdn.example/upload/x.webp".toHttpUrl()))
        // 同族主机还承载 HLS 的裸密钥、分片与播放列表，绝不能当图片解密
        assertFalse(HuangguoImageDecoder.isImageRequest("https://tp6.qnlycc.cn/videos5/x/crypt.key".toHttpUrl()))
        assertFalse(HuangguoImageDecoder.isImageRequest("https://tp2.drleao.cn/videos5/x/crypt.key".toHttpUrl()))
        assertFalse(HuangguoImageDecoder.isImageRequest("https://tp4.qnlycc.cn/videos5/x/x0.ts".toHttpUrl()))
        assertFalse(HuangguoImageDecoder.isImageRequest("https://yd-lphu-hls.tjyyjk.cn/videos5/x/x.m3u8".toHttpUrl()))
    }

    @Test
    fun `decodes covers served from the current cover host`() {
        val plain = Base64.getDecoder().decode(ONE_PIXEL_PNG)
        val response = imageResponse(encrypt(plain), url = "https://pic.qnlycc.cn/upload_01/upload/20261120/cover_x.png")

        val decoded = HuangguoImageDecoder.decodeResponse(response)

        assertArrayEquals(plain, decoded.body.bytes())
        assertEquals("image/png", decoded.body.contentType().toString())
    }

    @Test
    fun `strips pkcs7 padding added by the site`() {
        val plain = "0123456789abcdef".toByteArray(Charsets.UTF_8)
        val padded = plain + ByteArray(16) { 16.toByte() }

        val decrypted = requireNotNull(HuangguoImageDecoder.decrypt(encryptRaw(padded)))

        assertArrayEquals(plain, decrypted)
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val padding = 16 - plain.size % 16
        val padded = plain + ByteArray(padding) { padding.toByte() }
        return encryptRaw(padded)
    }

    private fun encryptRaw(padded: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(padded)
    }

    private fun imageResponse(
        body: ByteArray,
        url: String = "https://pic.qnlycc.cn/upload_01/upload/20260101/cover_a.jpg?auth_key=1-0-0-abc",
    ): Response {
        val request = Request.Builder()
            .url(url)
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "binary/octet-stream")
            .body(body.toResponseBody("binary/octet-stream".toMediaType()))
            .build()
    }

    private companion object {
        /** 1x1 透明 PNG */
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
