package com.vegamovies

import com.lagradost.cloudstream3.app
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Expands nexdrive gateways and resolves fastdl embeds. Concrete link, server family, and family-relative index. */
internal data class Concrete(val kind: String, val url: String, val idx: Int)

/** Parsed gateway content. */
internal data class GatewayExpansion(
    /** Concrete links in document order with server families. */
    val links: List<Concrete>,
    /** Gateway page title. */
    val pageTitle: String?,
) {
    companion object {
        val EMPTY = GatewayExpansion(emptyList(), null)
    }

    /** Maximum link count across server families. */
    val episodeCount: Int
        get() = links.groupingBy { it.kind }.eachCount().values.maxOrNull() ?: 0
}

internal object NexdriveResolver {

    /** Direct media URL pattern. */
    val DIRECT_REGEX = Regex("""https://video-downloads\.googleusercontent\.com/\S+""")

    /** Fastdl embed URL forms. */
    val FASTDL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""",
        RegexOption.IGNORE_CASE,
    )

    val VCLOUD_REGEX = Regex(
        """https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""",
    )

    /** Direct-link patterns in gateway HTML. */
    internal val REURL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/dl\.php\?link=(https?://[^\s"'<>)\\]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** URL-encoded direct-link pattern. */
    internal val REURL_PARAM = Regex("""dl\.php\?link=([^"'<>\s\\]+)""", RegexOption.IGNORE_CASE)

    /** Fetches and caches one gateway expansion. */
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

        // Classify by host, not by the title tag: a movie gate carries BOTH a fastdl embed and a vcloud page (users want every.
// server of the page). idx =.
        val links = ArrayList<Concrete>(16)
        FASTDL_REGEX.findAll(html).forEachIndexed { i, m -> links += Concrete(Servers.GDRIVE, m.value, i) }
        VCLOUD_REGEX.findAll(html).forEachIndexed { i, m -> links += Concrete(Servers.VCLOUD, m.value, i) }
        val result = GatewayExpansion(links, title)
        if (result.links.isNotEmpty() && cache.size < 96) cache[gatewayUrl] = result
        return result
    }

    /** Resolves a fastdl embed to a direct URL. */
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

    /** Extracts a direct media URL. */
    internal fun extractDirect(body: String): String? {
        // 1. Plain reurl: ".
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

    /** Gateway expansions keyed by URL. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, GatewayExpansion>()

    /** Cached embed resolutions; empty means failure. */
    private val directCache = java.util.concurrent.ConcurrentHashMap<String, String>()
}
