package org.skepsun.kototoro.parsers.site.all

import okhttp3.HttpUrl
import java.util.Base64

/** 为 MangaFire 当前 REST API 添加必需的请求签名。 */
internal object MangaFireVrfSigner {

	fun sign(url: HttpUrl): HttpUrl {
		require(url.encodedPath.startsWith(API_PREFIX)) { "Only MangaFire API URLs can be signed" }

		val parameters = url.queryParameterNames
			.flatMap { name -> url.queryParameterValues(name).map { value -> name to value } }
			.sortedBy { it.first }
		val signatureInput = buildString {
			append(url.encodedPath.removePrefix(API_PREFIX))
			if (parameters.isNotEmpty()) {
				append('?')
				var arrayIndex = 0
				var previousName: String? = null
				append(parameters.joinToString("&") { (name, value) ->
					val normalizedName = if (name.endsWith("[]")) {
						if (name != previousName) arrayIndex = 0
						previousName = name
						name.replace("[]", "[${arrayIndex++}]")
					} else {
						name
					}
					"$normalizedName=$value"
				})
			}
		}

		return url.newBuilder().query(null).apply {
			parameters.forEach { (name, value) -> addQueryParameter(name, value) }
			addQueryParameter("vrf", generate(signatureInput))
		}.build()
	}

	internal fun generate(path: String): String {
		var data = path.toByteArray(Charsets.UTF_8)
		for ((table, key, iv) in stages) {
			data = encryptStage(data, table, key, iv)
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
	}

	private fun encryptStage(data: ByteArray, table: ByteArray, key: ByteArray, iv: Int): ByteArray {
		val output = ByteArray(data.size)
		var previous = iv
		for (index in data.indices) {
			previous = table[
				(data[index].toInt() xor key[index % key.size].toInt() xor previous) and 0xFF,
			].toInt() and 0xFF
			output[index] = previous.toByte()
		}
		return output
	}

	private const val API_PREFIX = "/api"
	private const val TABLE_1 =
		"yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKGFvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6kLNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwdxbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A=="
	private const val KEY_1 = "0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA="
	private const val TABLE_2 =
		"IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9VOhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41TezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342HL+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45UnifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7mL5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA=="
	private const val KEY_2 = "AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ=="
	private const val TABLE_3 =
		"NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybMHbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMNhzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDwIqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFeNl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWGCa6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ=="
	private const val KEY_3 = "DELOJgPsVaCcblDtTGMdHzM="

	private val decoder = Base64.getDecoder()
	private val stages by lazy {
		listOf(
			Triple(decoder.decode(TABLE_1), decoder.decode(KEY_1), 0x5A),
			Triple(decoder.decode(TABLE_2), decoder.decode(KEY_2), 0x35),
			Triple(decoder.decode(TABLE_3), decoder.decode(KEY_3), 0xBA),
		)
	}
}
