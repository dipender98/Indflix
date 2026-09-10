package com.cinevood

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.unshortenLinkSafe

/*
 * Turns a resolved download URL (post-gate) into playable ExtractorLinks.
 * Strategy, in order:
 *   1. Google Drive file links -> drive.usercontent direct download/stream
 *   2. Direct media URLs (.mkv/.mp4/.webm/.m3u8) -> passthrough
 *   3. GDFlix-family file pages -> scan for drive/direct links (grounded
 *      patterns only; nothing is fabricated)
 *   4. Anything else -> CloudStream core extractors (345 hosts built-in),
 *      with a single unshorten retry for bare redirect pages.
 */

object LinkExtractors {

    private val DIRECT_MEDIA = Regex("""(?i)\.(mkv|mp4|webm|m3u8|ts)(\?|$)""")
    private val DRIVE_ID = Regex("""(?:/file/d/|[?&]id=)([a-zA-Z0-9_-]{10,})""")

    suspend fun emit(
        link: GroupInfo,
        referer: String,
        gate: CineVoodGate,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolved = if (gate.isGate(link.url)) gate.resolve(link.url)?.finalUrl else link.url
        if (resolved.isNullOrBlank()) return false
        return dispatch(resolved, link, referer, subtitleCallback, callback)
    }

    private suspend fun dispatch(
        url: String,
        link: GroupInfo,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        allowGeneric: Boolean = true
    ): Boolean {
        val host = url.substringAfter("://").substringBefore("/")

        // 1) Google Drive
        if (host.contains("drive.google") || host.contains("docs.google")) {
            DRIVE_ID.find(url)?.groupValues?.get(1)?.let { id ->
                val direct =
                    "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
                return push(
                    direct, "Google Drive", link,
                    referer = "https://drive.google.com/",
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("User-Agent" to SharedServices.userAgent()),
                    callback = callback
                )
            }
        }

        // 2) Direct media
        if (DIRECT_MEDIA.containsMatchIn(url)) {
            val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
            return push(url, hostLabel(host), link, referer, type, emptyMap(), callback)
        }

        // 3) GDFlix-family file pages
        if (host.contains("gdflix")) {
            val text = runCatching {
                val res = com.lagradost.cloudstream3.app.get(
                    url,
                    headers = SharedServices.browserHeaders(referer),
                    timeout = 30
                )
                if (res.code in 200..399) res.text else null
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                for (href in collectAnchors(text)) {
                    if (dispatchSafe(href, link, url, subtitleCallback, callback, allowGeneric = false)) {
                        return true
                    }
                }
            }
        }

        // 4) Core extractor registry
        if (allowGeneric) {
            val tagged = { extra: ExtractorLink ->
                callback(
                    ExtractorLink(
                        source = extra.source,
                        name = displayName(link, extra.name),
                        url = extra.url,
                        referer = extra.referer,
                        quality = if (extra.quality == 0) qualityInt(link) else extra.quality,
                        type = extra.type,
                        headers = extra.headers
                    )
                )
            }
            val found = runCatching {
                loadExtractor(url, referer, subtitleCallback, tagged)
            }.getOrDefault(false)
            if (found) return true

            // one redirect retry, then re-dispatch without recursion
            val unshortened = runCatching { unshortenLinkSafe(url) }.getOrNull()
            if (!unshortened.isNullOrBlank() && unshortened != url) {
                return dispatchSafe(unshortened, link, referer, subtitleCallback, callback, allowGeneric = false)
            }
        }
        return false
    }

    private suspend fun dispatchSafe(
        url: String, link: GroupInfo, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit, allowGeneric: Boolean
    ): Boolean = runCatching { dispatch(url, link, referer, subtitleCallback, callback, allowGeneric) }
        .getOrDefault(false)

    private fun push(
        url: String, hostName: String, link: GroupInfo, referer: String,
        type: ExtractorLinkType, headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            ExtractorLink(
                source = "CineVood",
                name = displayName(link, hostName),
                url = url,
                referer = referer,
                quality = qualityInt(link),
                type = type,
                headers = headers + mapOf("User-Agent" to SharedServices.userAgent())
            )
        )
        return true
    }

    private fun displayName(link: GroupInfo, fallback: String): String {
        val audio = TitleParser.audioLabel(link.languages)
        val parts = listOfNotNull(
            "${link.quality}p",
            audio.ifBlank { null },
            link.sizeText?.let { it },
            fallback
        )
        return parts.joinToString(" · ")
    }

    private fun qualityInt(link: GroupInfo): Int = getQualityFromName("${link.quality}p")

    private fun hostLabel(host: String): String =
        host.removePrefix("www.").substringBefore(".").uppercase()

    private val collect = Regex("""href=["']([^"']+)["']""")

    private fun collectAnchors(html: String): List<String> =
        collect.findAll(html).map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct()
            .take(10)
            .toList()
}
