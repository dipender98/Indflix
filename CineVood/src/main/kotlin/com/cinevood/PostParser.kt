package com.cinevood

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/*
 * Pure parsing of CineVood post bodies (from wp-json `content.rendered` or
 * saved HTML). No network, no Android — unit-testable.
 */

data class PostLinks(
    val groups: List<GroupInfo>,
    val imdbId: String?,
    val infoLines: Map<String, String>
)

object PostParser {

    private val IMDB_HREF = Regex("""imdb\.com/title/(tt\d+)""")

    fun extractImdbId(html: String): String? = IMDB_HREF.find(html)?.groupValues?.get(1)

    /** Parse the `.mfx-download-group` blocks into (label, gate-url) pairs. */
    fun parseDownloadGroups(contentHtml: String): List<GroupInfo> {
        val body = Jsoup.parseBodyFragment(contentHtml).body()
        val out = ArrayList<GroupInfo>()
        for (group in body.select("div.mfx-download-group")) {
            val label = group.selectFirst("h3.mfx-quality-title")?.text()?.trim() ?: continue
            for (a in group.select("a.mfx-download-link[href]")) {
                val href = a.attr("href").trim()
                TitleParser.parseGroup(label, href)?.let { out.add(it) }
            }
        }
        // Fall back to a flat scan when the wrapper class layout ever changes.
        if (out.isEmpty()) {
            for (a in body.select("a[href]")) {
                val href = a.attr("href")
                if (href.contains("genx") || href.contains("mobilejsr")) {
                    val label = a.text().ifBlank { a.parent()?.text() ?: "" }
                    TitleParser.parseGroup(label, href)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    /** Parse a full post (content.rendered or page body). */
    fun parsePost(contentHtml: String): PostLinks {
        val root = Jsoup.parseBodyFragment(contentHtml)
        val info = LinkedHashMap<String, String>()
        for (row in root.select("div.mfx-info-box > div, div.mfx-info-box dl > div, .extra-info .row")) {
            val text = row.text().trim()
            if (text.contains(":")) {
                val idx = text.indexOf(":")
                val k = text.substring(0, idx).trim().lowercase().replace(" ", "")
                val v = text.substring(idx + 1).trim()
                if (k.isNotBlank()) info[k] = v
            }
        }
        return PostLinks(
            groups = parseDownloadGroups(contentHtml),
            imdbId = extractImdbId(contentHtml),
            infoLines = info
        )
    }
}
