package org.skepsun.kototoro.parsers.site.en

import org.jsoup.Jsoup
import java.io.File
import java.util.Locale

fun main() {
    val file = File("e:/kototoro_demo/simpcity/What's New _ SimpCity Forums.html")
    val doc = Jsoup.parse(file, "UTF-8", "https://simpcity.cr")
    val rows = doc.select("li.block-row .contentRow, .contentRow")
    
    val STYLE_URL_REGEX = Regex("""background-image\s*:\s*url\(([^)]+)\)""", RegexOption.IGNORE_CASE)

    println("Found ${rows.size} rows")
    for (row in rows) {
        val link = row.selectFirst(".contentRow-main > a[href*='/threads/'], a[href*='/threads/']") ?: continue
        val title = link.ownText().trim().ifBlank { link.text().trim() }
        
        var styleUrlFound: String? = null
        val styleEls = row.select(".dcThumbnail, [style*='background-image'], .structItem-cell--icon, .avatar")
        for (el in styleEls) {
            val style = el.attr("style")
            if (style.isBlank()) continue
            val raw = STYLE_URL_REGEX.find(style)?.groupValues?.getOrNull(1)?.trim('"', '\'')
            if (raw != null && !raw.contains("no_image.jpg")) {
                styleUrlFound = raw
                break
            }
        }
        
        println("Title: \$title -- Cover: \$styleUrlFound")
    }
}
