package org.skepsun.kototoro.parsers.site.zh

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared decoder for CMS payloads that prefix AES-CBC ciphertext with a 16-byte IV. */
internal object AesCbcDecoder {
	private const val IV_SIZE = 16

	fun decodeJsonWithPrefixedIv(encoded: String, key: String): JSONObject? = runCatching {
		val bytes = Base64.getDecoder().decode(encoded)
		require(bytes.size > IV_SIZE)
		val plaintext = decrypt(
			data = bytes.copyOfRange(IV_SIZE, bytes.size),
			key = key.toByteArray(Charsets.UTF_8),
			iv = bytes.copyOfRange(0, IV_SIZE),
		) ?: error("Unable to decrypt AES-CBC payload")
		JSONObject(String(plaintext, Charsets.UTF_8))
	}.getOrNull()

	fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = runCatching {
		require(key.size == IV_SIZE)
		require(iv.size == IV_SIZE)
		val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
		cipher.doFinal(data)
	}.getOrNull()
}
