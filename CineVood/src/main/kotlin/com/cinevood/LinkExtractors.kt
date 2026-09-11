package com.cinevood

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.unshortenLinkSafe

/*
 * Turns a resolved download URL (post-gate) into playable ExtractorLinks.
 * Strategy, in order:
 *   1. Google Drive file links -> drive.usercontent direct download/stream
 *   2. Direct media URLs (.mkv/.mp4/.webm/.m3u8) -> passthrough
 *   3. GDFlix-family file pages -> scan for drive/direct links (grounded
 *      patterns only; nothing is fabricated), fetched through the gate's
 *      solved Cloudflare cookie jar
 *   4. Anything else -> CloudStream core extractors (345 hosts built-in),
 *      with a single unshorten retry for bare redirect pages.
 *
 * Every decision is logged (tag CineVood) so a device "no link found" always
 * has a per-stage paper trail.
 */

object LinkExtractors {

    private val DIRECT_MEDIA = Regex("""(?i)\.(mkv|mp4|webm|m3u8|ts)(\?|$)""")
    private val DRIVE_ID = Regex("""(?:/file/d/|[?&]id=)([a-zA-Z0-9_-]{10,})""")
    private val CHALLENGE = Regex("""_cf_chl_opt|Just a moment""")

    suspend fun emit(
        link: GroupInfo,
        referer: String,
        gate: CineVoodGate,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val gr = if (gate.isGate(link.url)) gate.resolve(link.url) else null
        if (gate.isGate(link.url) && gr == null) {
            // second chance: core's redirector walk may pass where the
            // WebView solve did not
            val via = runCatching { unshortenLinkSafe(link.url) }.getOrNull()
            if (via.isNullOrBlank() || via == link.url) {
                gate.diag("EMIT SKIP gate-unsolved ${link.url}")
                return false
            }
            gate.diag("EMIT gate-fail unshorten -> $via")
            return dispatch(via, link, referer, gate, subtitleCallback, callback)
        }
        val resolved = gr?.finalUrl ?: link.url
        if (resolved.isNullOrBlank()) return false

        // The gate now lands on a JS token interstitial (mobilejsr.rest/token/
        // <id>.<ts>.<hash>) served with HTTP 200. If we are STILL on the gate
        // host, the real target is inside the fetched page body: scan it for
        // external file-host/media links before giving up.
        if (gate.isGate(resolved)) {
            val body = gr?.pageText.orEmpty()
            val candidates = collectPageTargets(body).filterNot { gate.isGate(it) }
            gate.diag("TOKENPAGE candidates=${candidates.size} for $resolved")
            for (t in candidates) {
                if (dispatchSafe(t, link, resolved, gate, subtitleCallback, callback, true)) {
                    return true
                }
            }
            // reveal the obfuscation for the next debug round (chunked for logcat)
            val compact = body.replace(Regex("\\s+"), " ")
            var i = 0
            while (i < minOf(compact.length, 3600)) {
                gate.diag("TOKENPAGE[$i] ${compact.substring(i, minOf(i + 1200, compact.length))}")
                i += 1200
            }
            return false
        }
        return dispatch(resolved, link, referer, gate, subtitleCallback, callback)
    }

    /** External http(s) URLs inside an interstitial body, most-likely-host first. */
    private val URL_IN_BODY = Regex("""https?://[^"'<>()\s\\]+""")
    private val PREFERRED = Regex("""(?i)gdflix|drive\.google|usercontent|hubcloud|filepress|multicloud|vik1ng|pixeldrain|gofile|zipdisk|/file/|\.(mkv|mp4|webm|m3u8)(\?|$)""")
    private val JUNK_EXT = Regex("""(?i)\.(js|css|png|jpg|jpeg|webp|ico|svg|woff2?|gif|json)(\?|$)""")

    private fun collectPageTargets(body: String): List<String> {
        val plain = URL_IN_BODY.findAll(body)
            .map { it.value }
            .filter { !it.contains("mobilejsr") && !it.contains("cinevood") &&
                !it.contains("googleapis") && !it.contains("gstatic") &&
                !it.contains("w3.org") && !JUNK_EXT.containsMatchIn(it) }
        val ciphered = SiteCipher.decodeUrls(body)
        return (plain + ciphered).distinct()
            .sortedByDescending { PREFERRED.containsMatchIn(it) }
            .take(8)
            .toList()
    }

    private suspend fun dispatch(
        url: String,
        link: GroupInfo,
        referer: String,
        gate: CineVoodGate,
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
                    direct, "Google Drive", link, "https://drive.google.com/",
                    ExtractorLinkType.VIDEO,
                    mapOf("User-Agent" to SharedServices.userAgent()),
                    callback
                )
            }
        }

        // 2) Direct media
        if (DIRECT_MEDIA.containsMatchIn(url)) {
            val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
            return push(url, hostLabel(host), link, referer, type, emptyMap(), callback)
        }

        // 3) GDFlix-family file pages (fetched with the solved cookie jar)
        if (host.contains("gdflix")) {
            val page = gate.fetchText(url, referer)
            val text = page?.second
            if (text.isNullOrBlank() || CHALLENGE.containsMatchIn(text)) {
                gate.diag("GDFLIX page blocked/empty code=${page?.first} $url")
            } else {
                for (href in collectAnchors(text)) {
                    if (dispatchSafe(href, link, url, gate, subtitleCallback, callback, false)) {
                        return true
                    }
                }
                gate.diag("GDFLIX no usable anchors $url")
            }
        }

        // 4) Core extractor registry
        if (allowGeneric) {
            // tagged runs in loadExtractor's non-suspend callback, so it uses the
            // (deprecated but still-supported) constructor; push() below uses the
            // newExtractorLink factory where we are in a suspend context.
            @Suppress("DEPRECATION")
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
            gate.diag("GENERIC $url found=$found")
            if (found) return true

            // one redirect retry, then re-dispatch without recursion
            val unshortened = runCatching { unshortenLinkSafe(url) }.getOrNull()
            if (!unshortened.isNullOrBlank() && unshortened != url) {
                return dispatchSafe(
                    unshortened, link, referer, gate, subtitleCallback, callback, false
                )
            }
        }
        gate.diag("EMIT DEAD-END $url (host=$host generic=$allowGeneric)")
        return false
    }

    private suspend fun dispatchSafe(
        url: String, link: GroupInfo, referer: String, gate: CineVoodGate,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit, allowGeneric: Boolean
    ): Boolean = runCatching {
        dispatch(url, link, referer, gate, subtitleCallback, callback, allowGeneric)
    }.onFailure { gate.diag("DISPATCH EXC ${it.javaClass.simpleName} $url") }
        .getOrDefault(false)

    private suspend fun push(
        url: String, hostName: String, link: GroupInfo, referer: String,
        type: ExtractorLinkType, headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val name = displayName(link, hostName)
        callback(
            newExtractorLink(
                source = "CineVood",
                name = name,
                url = url,
                type = type
            ) {
                this.referer = referer
                quality = qualityInt(link)
                this.headers = headers + mapOf("User-Agent" to SharedServices.userAgent())
            }
        )
        gatelessDiag("EMIT $name $url")
        return true
    }

    private fun gatelessDiag(msg: String) {
        runCatching { com.lagradost.api.Log.d("CineVood", msg) }
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

/**
 * The network's own obfuscation fingerprint (seen verbatim on cinevood pages):
 * a 72-char alphabet where the last 36 chars map one-to-one onto the first
 * 36. Interstitial/token pages embed their real target as a cipher-substituted
 * string; decoding is a plain char swap. Pure + unit-testable.
 */
object SiteCipher {
    private val ALPHABET = Regex("""['"]?a['"]?\s*[:=]\s*['"]([a-z0-9]{72})['"]""")
    private val ENCODED_STR = Regex("""["']([A-Za-z0-9./:_+-]{10,})["']""")
    private val URLISH = Regex("""//[\w.-]+\.[a-z]{2,}/""")

    fun key(html: String): Pair<String, String>? =
        ALPHABET.find(html)?.groupValues?.get(1)?.let { it.take(36) to it.drop(36) }

    fun decodeString(s: String, h1: String, h2: String): String =
        s.map { c -> h2.indexOf(c).takeIf { it >= 0 }?.let { h1[it] } ?: c }.joinToString("")

    fun encodeString(s: String, h1: String, h2: String): String =
        s.map { c -> h1.indexOf(c).takeIf { it >= 0 }?.let { h2[it] } ?: c }.joinToString("")

    fun decodeUrls(html: String): List<String> {
        val (h1, h2) = key(html) ?: return emptyList()
        return ENCODED_STR.findAll(html)
            .map { decodeString(it.groupValues[1], h1, h2) }
            .filter { URLISH.containsMatchIn(it) && !it.contains("mobilejsr") }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()
            .toList()
    }
}
