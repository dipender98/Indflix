package com.vegamovies

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * NexdriveResolver.kt — turns a Vegamovies nexdrive.fit gateway page into
 * server links (verified protocol, tools/nexdrive_*.py, Sept 2026):
 *
 *   GET https://<nexdrive>/genxfm{ID}/         (WP page, ~46 KB)
 *     ├─ https://fastdl.zip/embed?download=T   ← playable server
 *     │    GET embed → obfuscated JS whose cleartext holds
 *     │    var reurl = "https://fastdl.zip/dl.php?link=DIRECT media URL"
 *     │    DIRECT = https://video-downloads.googleusercontent.com/...
 *     │    VERIFIED: HTTP 200, MKV attachment.
 *     └─ https://vcloud.fit/{id}               ← browser-gated server
 *          (hubcloud Telegram-bot gateway; NOT automatable — exposed to the
 *           user as a "browser" link so ALL website servers are present.)
 *
 * The countdown on the genxfm page is purely cosmetic client JS — both URLs
 * are in the raw HTML on first fetch, so resolution needs no waiting: the
 * Kotlin "first ~10s" window is the fetch + embed unwrap round-trips.
 *
 * Every resolution is cached per gateway URL and deduplicated in-flight;
 * failures return the empty list so the caller can degrade gracefully.
 */

internal data class RawServer(
    /** Final URL for the ExtractorLink. */
    val url: String,
    /** Display name contribution, e.g. "Direct" / "V-Cloud". */
    val name: String,
    /** True = direct playable file; false = browser-gated page. */
    val direct: Boolean,
    /** Referer header required by the media URL. */
    val referer: String,
)

internal object NexdriveResolver {

    /** Verified-playable Google Drive media direct-link pattern. */
    val DIRECT_REGEX = Regex("""https://video-downloads\.googleusercontent\.com/\S+""")

    private const val FASTDL_EMBED = """https?://fastdl\.[a-z]{2,10}/embed\?download=[A-Za-z0-9]+"""
    private const val VCLOUD = """https?://vcloud\.[a-z]{2,10}/[a-z0-9]{6,}"""
    private val FASTDL_EMBED_REGEX = Regex(FASTDL_EMBED, RegexOption.IGNORE_CASE)
    private val VCLOUD_REGEX = Regex(VCLOUD, RegexOption.IGNORE_CASE)

    /** reurl survives obfuscation in cleartext (both a quoted var-init and a
     *  string-array entry — verified on fastdl.zip). */
    internal val REURL_REGEX = Regex(
        """https://fastdl\.[a-z]{2,10}/dl\.php\?link=(https?://[^\s"'<>)\\]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** genxfm pages are large WP docs; cap the fastdl reurl scan window. */
    private const val HEAD_CHARS = 8_000

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<RawServer>>()

    /**
     * Resolve one gateway URL into up to two servers (direct first when the
     * fastdl chain succeeds, the vcloud browser server when present).
     * Best-effort: returns an empty list on network failure so the caller can
     * emit the raw gateway link as the final fallback.
     */
    suspend fun resolve(
        gatewayUrl: String,
        pageReferer: String,
        headers: Map<String, String>,
    ): List<RawServer> = coroutineScope {
        cache[gatewayUrl]?.let { return@coroutineScope it }
        val fastdl = async { resolveFastdl(gatewayUrl, pageReferer, headers) }
        val vcloud = async { resolveVcloud(gatewayUrl, pageReferer, headers) }
        val result = fastdl.await() + vcloud.await()
        if (result.isNotEmpty()) cache[gatewayUrl] = result
        result
    }

    /** genxfm page → fastdl embed → dl.php reurl → googleusercontent direct. */
    private suspend fun resolveFastdl(
        gatewayUrl: String,
        pageReferer: String,
        headers: Map<String, String>,
    ): List<RawServer> {
        val page = gatewayPage(gatewayUrl, pageReferer, headers) ?: return emptyList()
        val embed = FASTDL_EMBED_REGEX.find(page)?.value ?: return emptyList()
        // The embed body holds the reurl; the gateway page sometimes inlines it too.
        val embedBody = runCatching {
            com.lagradost.cloudstream3.app
                .get(embed, timeout = 10, headers = headers + mapOf(
                    "Referer" to hostBase(gatewayUrl),
                    "Accept" to "text/html,application/xhtml+xml",
                )).text
        }.getOrNull()
        val reurl = (embedBody?.let { REURL_REGEX.find(it)?.groupValues?.get(1) })
            ?: REURL_REGEX.find(page?.take(HEAD_CHARS) ?: "")?.groupValues?.get(1)
        val direct = reurl?.substringAfter("link=")?.takeIf { DIRECT_REGEX.matches(it) }
            ?: reurl?.let { DIRECT_REGEX.find(it)?.value }
        if (direct != null) {
            return listOf(RawServer(url = direct, name = "Direct", direct = true, referer = "https://fastdl.zip/"))
        }
        // Dead/changed fastdl chain: surface the embed itself as browser link.
        return listOf(RawServer(url = embed, name = "Fastdl", direct = false, referer = hostBase(gatewayUrl)))
    }

    /** genxfm page → vcloud page (Telegram-gated; browser link only). */
    private suspend fun resolveVcloud(
        gatewayUrl: String,
        pageReferer: String,
        headers: Map<String, String>,
    ): List<RawServer> {
        val page = gatewayPage(gatewayUrl, pageReferer, headers) ?: return emptyList()
        val vc = VCLOUD_REGEX.find(page)?.value ?: return emptyList()
        return listOf(RawServer(url = vc, name = "V-Cloud", direct = false, referer = hostBase(gatewayUrl)))
    }

    /** Fetch + memoize a genxfm/gateway page body. */
    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun gatewayPage(url: String, referer: String, headers: Map<String, String>): String? {
        pageCache[url]?.let { return it }
        val body = runCatching {
            com.lagradost.cloudstream3.app
                .get(url, timeout = 12, headers = headers + mapOf("Referer" to referer))
                .text
        }.getOrNull() ?: return null
        if (pageCache.size > 24) pageCache.keys.firstOrNull()?.let { pageCache.remove(it) }
        pageCache[url] = body
        return body
    }

    private fun hostBase(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: url
}
