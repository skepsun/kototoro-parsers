package org.skepsun.kototoro.parsers.util.json

public fun String.unescapeJson(): String {
	val builder = StringBuilder()
	var i = 0
	while (i < length) {
		val delimiter = this[i]
		i++ // consume letter or backslash
		if (delimiter == '\\' && i < length) {
			val ch = this[i]
			i++

			when (ch) {
				'\\', '/', '"', '\'' -> builder.append(ch)
				'n' -> builder.append('\n')
				'r' -> builder.append('\r')
				't' -> builder.append('\t')
				'b' -> builder.append('\b')
				'f' -> builder.append('\u000c')
				'u' -> {
					if (i + 4 <= length) {
						val hex = substring(i, i + 4)
						val code = hex.toIntOrNull(16)
						if (code != null) {
							builder.append(code.toChar())
							i += 4
						} else {
							builder.append(ch)
						}
					} else {
						builder.append(ch)
					}
				}

				else -> builder.append(ch)
			}
		} else {
			builder.append(delimiter)
		}
	}
	return builder.toString()
}
