package com.vegamovies

import com.lagradost.cloudstream3.app
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Resolves V-Cloud shortlinks to seekable R2 stream URLs. */
internal data class VcloudStream(val url: String, val tag: String)

internal object VcloudResolver {

    private val cacheSize
        get() = 64

    /** Parses the double-encoded token URL. */
    val ATOB_ATOB = Regex(
        """var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""",
    )

    /** Parses download buttons. */
    val BTN = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
        RegexOption.IGNORE_CASE,
    )

    /** Emits only R2/FSL streamable links. */
    val STREAMABLE_HOST = Regex("""(r2\.cloudflarestorage\.com|r2\.dev)""", RegexOption.IGNORE_CASE)

    private val directCache = ConcurrentHashMap<String, List<VcloudStream>>()

    /** Resolves and caches one V-Cloud URL. */
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

    /** Decodes a double Base64 value. */
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
