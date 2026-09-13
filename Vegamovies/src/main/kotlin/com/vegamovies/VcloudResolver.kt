package com.vegamovies

import com.lagradost.cloudstream3.app
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * VcloudResolver.kt — resolves a `vcloud.fit/<id>` shortlink to the R2/FSL
 * direct file URLs that CSX's VCloud extractor emits as playable streams.
 *
 * Protocol (verified 2026-09-12 against a live Squid Game S02 EP1 V-Cloud link):
 *   GET https://vcloud.fit/<id>/                    (UA only, no referer check)
 *     → HTML: `var url = atob(atob("BASE64_BASE64"))` → decode twice → token URL
 *   GET <token URL> (Referer=vcloud.fit/<id>)
 *     → HTML with N `<a class="btn" href="...">Download [<server>]</a>` buttons;
 *     the FSLv2/FSL entries are Cloudflare R2 signed URLs:
 *       https://<account>.r2.cloudflarestorage.com/hub2/<name>.mkv?...
 *       https://pub-<id>.r2.dev/<sha>?token=<exp>
 *     Both support Range and answer 206 video/octet-stream → fully seekable.
 *
 * The plugin emits THESE as in-app stream links and drops the browser-only
 * googleusercontent fastdl path (it ignores Range → ExoPlayer treats the
 * entire 500MB+ body as an unbreakable initial segment: movies never start;
 * this is the "movie not streaming on device but fine in browser" symptom).
 */
internal data class VcloudStream(val url: String, val tag: String)

internal object VcloudResolver {

    private val cacheSize
        get() = 64

    /** vcloud.fit/<id> page: `var url = atob(atob("<b64>"))` double-encoded link. */
    val ATOB_ATOB = Regex(
        """var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""",
    )

    /** Token-page download buttons: <a href="..." class="btn">[FSLv2 Server]</a>. */
    val BTN = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
        RegexOption.IGNORE_CASE,
    )

    /** Only emit R2/FSL links — pixeldrain/gofile/hubcloud-10Gbps need more extractors than the site's real files. */
    val STREAMABLE_HOST = Regex("""(r2\.cloudflarestorage\.com|r2\.dev)""", RegexOption.IGNORE_CASE)

    private val directCache = ConcurrentHashMap<String, List<VcloudStream>>()

    /**
     * Chain-resolve one vcloud URL → 0..n R2 streamables. Best-effort: any
     * failure returns empty and the caller degrades to a browser-only row.
     * Memoised (vcloud pages are the same regardless of playback retry).
     */
    suspend fun resolve(
        vcloudUrl: String,
        referer: String,
        headers: Map<String, String>,
    ): List<VcloudStream> {
        directCache[vcloudUrl]?.let { return it }
        val page = runCatching {
            app.get(vcloudUrl, timeout = 15, headers = headers + mapOf("Referer" to referer)).text
        }.getOrNull() ?: return emptyList()
        val tokenUrl = page.let { p ->
            ATOB_ATOB.find(p)?.groupValues?.get(1)?.let { decodeDoubleAtob(it) }
        }?.takeIf { it.startsWith("http") } ?: return rememberEmpty(vcloudUrl)
        val token = runCatching {
            app.get(
                tokenUrl,
                timeout = 15,
                headers = headers + mapOf("Referer" to vcloudUrl),
            ).text
        }.getOrNull() ?: return rememberEmpty(vcloudUrl)
        val seen = ArrayList<VcloudStream>(4)
        val urls = LinkedHashSet<String>()
        BTN.findAll(token).forEach { m ->
            val raw = m.groupValues[1].trim()
            val label = m.groupValues[2].trim().let {
                Regex("""\[([^\]]+)\]""").find(it)?.groupValues?.get(1)?.trim() ?: it
            }
            val abs = if (raw.startsWith("http")) raw else {
                val origin = Regex("""^https?://[^/]+""").find(tokenUrl)?.value ?: return@forEach
                "$origin${if (raw.startsWith('/')) raw else "/$raw"}"
            }
            if (STREAMABLE_HOST.containsMatchIn(abs) && urls.add(abs)) {
                seen += VcloudStream(abs, label.take(20).ifBlank { "FSL" })
            }
        }
        if (seen.isNotEmpty() && directCache.size < cacheSize) directCache[vcloudUrl] = seen
        return seen
    }

    private fun rememberEmpty(vcloudUrl: String): List<VcloudStream> {
        if (directCache.size < cacheSize) directCache[vcloudUrl] = emptyList()
        return emptyList()
    }

    /** Pure: atob(atob(X)) — standard base64, padding-lenient (JS atob tolerates missing '='). */
    internal fun decodeDoubleAtob(blob: String): String? = try {
        val inner = String(decodeB64(blob.trim()))
        String(decodeB64(inner.trim()))
    } catch (e: Exception) {
        null
    }

    private fun decodeB64(s: String): ByteArray {
        val normalized = buildString {
            for (c in s) when (c) {
                '-' -> append('+'); '_' -> append('/'); else -> append(c)
            }
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }
}
