package org.skepsun.kototoro.parsers.site.zh

import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 黄果短剧的封面 CDN（`*.drleao.cn`）返回的不是图片，而是 AES-128-CBC/NoPadding 密文，
 * `Content-Type` 为 `binary/octet-stream`。key/iv 是站点前端里的固定常量。
 *
 * 两个关键约束（详见 docs/huangguo_drama_api_research.md）:
 * 1. 同一个域下 `tp2/tp3.drleao.cn` **还**在发 HLS 的 `crypt.key`（16 字节裸密钥）和 `.ts` 分片，
 *    因此只能按「图片扩展名」判定，不能按域名泛匹配，否则会把播放打坏。
 * 2. 站点存在未加密的老图（直接就是 `FFD8...`），必须先探测魔数，命中就直接透传。
 */
internal object HuangguoImageDecoder {

    private const val IMAGE_KEY = "f5d965df75336270"
    private const val IMAGE_IV = "97b60394abc2fbe1"
    private const val IMAGE_HOST_SUFFIX = "drleao.cn"

    private val IMAGE_PATH_REGEX = Regex("""\.(?:jpe?g|png|webp|gif)$""", RegexOption.IGNORE_CASE)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    fun isImageRequest(url: HttpUrl): Boolean {
        if (!url.host.endsWith(IMAGE_HOST_SUFFIX, ignoreCase = true)) {
            return false
        }
        return IMAGE_PATH_REGEX.containsMatchIn(url.encodedPath)
    }

    fun decodeResponse(response: Response): Response {
        if (!response.isSuccessful || !isImageRequest(response.request.url)) {
            return response
        }
        val body: ResponseBody = response.body ?: return response
        val originalType = body.contentType()
        val encrypted = body.bytes()
        // 未加密的老图：魔数已经是图片，直接透传
        detectMediaType(encrypted)?.let { return rebuild(response, encrypted, it.toMediaType()) }
        val decrypted = decrypt(encrypted) ?: return rebuild(response, encrypted, originalType)
        val decryptedType = detectMediaType(decrypted) ?: return rebuild(response, encrypted, originalType)
        return rebuild(response, decrypted, decryptedType.toMediaType())
    }

    fun decrypt(data: ByteArray): ByteArray? = runCatching {
        require(data.size % AES_BLOCK_SIZE == 0)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(IMAGE_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IMAGE_IV.toByteArray(Charsets.UTF_8)),
        )
        stripPkcs7(cipher.doFinal(data))
    }.getOrNull()

    fun detectMediaType(data: ByteArray): String? = when {
        data.size >= 3 &&
            data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte() -> "image/jpeg"
        data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
        data.size >= 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WEBP" -> "image/webp"
        data.size >= 4 && String(data, 0, 4) == "GIF8" -> "image/gif"
        else -> null
    }

    /**
     * 站点用 NoPadding 加密并自行补 PKCS#7，这里只在不破坏数据的前提下裁掉尾部填充。
     */
    private fun stripPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padding = data.last().toInt() and 0xff
        if (padding !in 1..AES_BLOCK_SIZE || padding > data.size) return data
        if (data.takeLast(padding).any { (it.toInt() and 0xff) != padding }) return data
        return data.copyOfRange(0, data.size - padding)
    }

    private fun rebuild(response: Response, data: ByteArray, mediaType: MediaType?): Response {
        val builder = response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(data.toResponseBody(mediaType))
        return if (mediaType == null) {
            builder.removeHeader("Content-Type").build()
        } else {
            builder.header("Content-Type", mediaType.toString()).build()
        }
    }

    private const val AES_BLOCK_SIZE = 16
}
