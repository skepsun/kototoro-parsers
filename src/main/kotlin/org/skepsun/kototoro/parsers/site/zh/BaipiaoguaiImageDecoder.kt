package org.skepsun.kototoro.parsers.site.zh

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Decodes AES-encrypted image responses used by the shared Baipiaoguai image backend. */
internal object BaipiaoguaiImageDecoder {

	const val HOST = "https://img1.baipiaoguai.org"

	fun decodeResponse(response: Response, marker: String): Response {
		if (response.request.url.fragment != marker || !response.isSuccessful) return response
		val encrypted = response.body.bytes()
		val decrypted = decrypt(encrypted)
		if (decrypted == null) {
			return response.newBuilder().body(encrypted.toResponseBody(response.body.contentType())).build()
		}
		val mediaType = detectMediaType(decrypted).toMediaType()
		return response.newBuilder()
			.removeHeader("Content-Encoding")
			.removeHeader("Content-Length")
			.header("Content-Type", mediaType.toString())
			.body(decrypted.toResponseBody(mediaType))
			.build()
	}

	fun decrypt(data: ByteArray): ByteArray? {
		val key = IMAGE_KEY.toByteArray(Charsets.UTF_8)
		return AesCbcDecoder.decrypt(data, key, key)
	}

	fun detectMediaType(data: ByteArray): String = when {
		data.size >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte() -> "image/jpeg"
		data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
		data.size >= 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WEBP" -> "image/webp"
		data.size >= 4 && String(data, 0, 4) == "GIF8" -> "image/gif"
		else -> "application/octet-stream"
	}

	private const val IMAGE_KEY = "my2ecret782ecret"
	private val PNG_SIGNATURE = byteArrayOf(
		0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
	)
}
