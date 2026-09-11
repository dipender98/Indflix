package com.cinevood

import org.jsoup.Jsoup

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
    private val GATE_HREF = Regex("""genx|mobilejsr""", RegexOption.IGNORE_CASE)

    fun extractImdbId(html: String): String? = IMDB_HREF.find(html)?.groupValues?.get(1)

    /**
     * Parse the `.mfx-download-group` blocks into labelled link groups.
     * Falls back to a wider scan when the wrapper classes drift (RC-1D):
     * any download-labelled container anchor, then any gate-shaped href.
     */
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
        if (out.isEmpty()) {
            // fallback 1: any anchor inside a download-labelled container
            for (a in body.select("div[class*=download] a[href], a[class*=download][href]")) {
                val href = a.attr("href").trim()
                val label = a.text().ifBlank { a.parent()?.text() ?: "" }
                TitleParser.parseGroup(label, href)?.let { out.add(it) }
            }
        }
        if (out.isEmpty()) {
            // fallback 2: any gate-shaped href anywhere in the body
            for (a in body.select("a[href]")) {
                val href = a.attr("href").trim()
                if (GATE_HREF.containsMatchIn(href)) {
                    val label = a.text().ifBlank { a.parent()?.text() ?: "" }
                    TitleParser.parseGroup(label, href)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    /** Real synopsis from the site's plot box (F7), when present. */
    fun parsePlot(contentHtml: String): String? {
        val text = Jsoup.parseBodyFragment(contentHtml)
            .selectFirst(".mfx-plot-box, div[class*=plot]")?.text()?.trim()
        return text?.takeIf { it.length > 20 }
    }

    /** First content image — last-resort poster when featured media is absent (F8). */
    fun parseFirstImage(contentHtml: String): String? {
        val body = Jsoup.parseBodyFragment(contentHtml)
        for (img in body.select("img[src], img[data-src], img[data-lazy-src]")) {
            val src = (img.attr("src").ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("data-lazy-src") }).trim()
            if (src.startsWith("http") &&
                !src.contains("emoji") && !src.contains("gravatar")
            ) return src
        }
        return null
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
