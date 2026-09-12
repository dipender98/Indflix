package com.vegamovies

import com.lagradost.cloudstream3.app
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * NexdriveResolver.kt — expands a Vegamovies nexdrive gateway (genxfm) page
 * into CONCRETE server links, and resolves fastdl embeds to direct files.
 * (Protocol verified via tools/nexdrive_*.py + tools/vg_*.py probes, Sept 2026.)
 *
 *   GET https://<nexdrive>/genxfm{ID}/  — one WP page per download chip on the
 *   post ("⚡ G-Direct", "⚡ V-Cloud", "🗜 Batch/Zip"). Its H1 ends with the
 *   server tag:
 *       "... 480p x264 [200MB/E] = G-Direct"  → N x fastdl.zip/embed(.php)?download=T
 *       "... 720p x264 [800MB/E] = V-Cloud"   → N x vcloud.fit/{id}
 *       "... = Batch(h)"                      → 1 x zip-gate page
 *   N == 1 on single-file (movie) gates; N == episode count on per-season
 *   episode gates, in document order — that is how episode files are recovered.
 *
 *   fastdl chain (verified): the embed page holds the reurl in cleartext —
 *       https://fastdl.zip/dl.php?link=<media URL>
 *   where <media URL> is a direct video-downloads.googleusercontent.com file
 *   (HTTP 200, video/mp4 | video/mkv, content-disposition attachment).
 *   Google's endpoint IGNORES Range (never 206): it STREAMS progressively
 *   without in-player seek, and DOWNLOADS at full speed — link names say that.
 *
 *   vcloud chain is browser-gated (double-atob token loop → hubcloud Telegram
 *   bot): NOT automatable. Emitted verbatim as a browser-download link.
 */



/** A concrete server URL, its family, and its position within that family on
 *  the gateway page (== episode index for per-episode series chips). */
internal data class Concrete(val kind: String, val url: String, val idx: Int)

/** Parsed content of one genxfm gateway page. */
internal data class GatewayExpansion(
    /** Concrete servers in document order, each tagged with its family: a
     *  movie gate holds ONE fastdl embed + ONE vcloud page; a series chip
     *  holds N of one family (N = episodes, document order preserved). */
    val links: List<Concrete>,
    /** Page H1 — e.g. "Title (S01) ... 480p x264 [200MB/E] = G-Direct". */
    val pageTitle: String?,
) {
    companion object {
        val EMPTY = GatewayExpansion(emptyList(), null)
    }

    /** Episode count this gate exposes: max link count across families. */
    val episodeCount: Int
        get() = links.groupingBy { it.kind }.eachCount().values.maxOrNull() ?: 0
}

internal object NexdriveResolver {

    /** Verified direct-media host (Google Drive file server). */
    val DIRECT_REGEX = Regex("""https://video-downloads\.googleusercontent\.com/\S+""")

    /** fastdl embed forms: /embed?download= (movies) and /embed.php?download= (series). */
    val FASTDL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""",
        RegexOption.IGNORE_CASE,
    )

    val VCLOUD_REGEX = Regex(
        """https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""",
    )

    /** reurl survives fastdl obfuscation in cleartext (both embed forms verified). */
    internal val REURL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/dl\.php\?link=(https?://[^\s"'<>)\\]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Secondary spelling: link= param inside JS string arrays (may be URL-encoded). */
    internal val REURL_PARAM = Regex("""dl\.php\?link=([^"'<>\s\\]+)""", RegexOption.IGNORE_CASE)

    /**
     * Fetch + parse a genxfm gateway page into ordered concrete server links.
     * Memoized; returns [GatewayExpansion.EMPTY] on failure so callers can
     * degrade to presenting the gateway URL as a browser download.
     */
    suspend fun expand(
        gatewayUrl: String,
        pageReferer: String,
        headers: Map<String, String>,
    ): GatewayExpansion {
        cache[gatewayUrl]?.let { return it }
        val html = runCatching {
            app.get(
                gatewayUrl,
                timeout = 12,
                headers = headers + mapOf(
                    "Referer" to pageReferer,
                    "Accept" to "text/html,application/xhtml+xml",
                ),
            ).text
        }.getOrNull() ?: return GatewayExpansion.EMPTY

        val title = Regex("""<h1[^>]*>([^<]{1,260})""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()

        // Classify by host, not by the title tag: a movie gate carries BOTH a
        // fastdl embed and a vcloud page (users want every server of the page).
        // idx = position WITHIN THE FAMILY — episode ordinal for per-episode gates.
        val links = ArrayList<Concrete>(16)
        FASTDL_REGEX.findAll(html).forEachIndexed { i, m -> links += Concrete(Servers.GDRIVE, m.value, i) }
        VCLOUD_REGEX.findAll(html).forEachIndexed { i, m -> links += Concrete(Servers.VCLOUD, m.value, i) }
        val result = GatewayExpansion(links, title)
        if (result.links.isNotEmpty() && cache.size < 96) cache[gatewayUrl] = result
        return result
    }

    /**
     * Resolve a fastdl embed URL to its direct googleusercontent file URL.
     * Null when the chain fails (dead link / anti-bot) → the caller emits the
     * embed page itself as a browser-download link instead.
     */
    suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        headers: Map<String, String>,
    ): String? {
        directCache[embedUrl]?.let { return it.ifEmpty { null } }
        val body = runCatching {
            app.get(
                embedUrl,
                timeout = 10,
                headers = headers + mapOf(
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml",
                ),
            ).text
        }.getOrNull()
        val direct = body?.let { extractDirect(it) }
        // Memoize failures as "" so a dead embed isn't retried every playback.
        if (directCache.size < 96) directCache[embedUrl] = direct ?: ""
        return direct
    }

    /** Pure: pull the direct media URL out of a fastdl embed / dl.php page body. */
    internal fun extractDirect(body: String): String? {
        // 1. Plain reurl: "https://fastdl.tld/dl.php?link=https://video-downloads.googleusercontent.com/..."
        REURL_REGEX.find(body)?.groupValues?.get(1)?.let { cand ->
            DIRECT_REGEX.find(cand)?.value?.let { return it }
        }
        // 2. link= param in JS string-array form, possibly URL-encoded.
        REURL_PARAM.findAll(body).forEach { m ->
            val raw = m.groupValues[1]
            DIRECT_REGEX.find(raw)?.value?.let { return it }
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
                ?.let { DIRECT_REGEX.find(it)?.value }?.let { return it }
        }
        // 3. A direct URL inlined anywhere in the body.
        return DIRECT_REGEX.find(body)?.value
    }

    /** Gateway-page expansions, keyed by genxfm URL. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, GatewayExpansion>()

    /** Embed→direct resolutions; "" value = memoized failure. */
    private val directCache = java.util.concurrent.ConcurrentHashMap<String, String>()
}
